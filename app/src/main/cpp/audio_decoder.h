#ifndef AUDIO_DECODER_H
#define AUDIO_DECODER_H

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <atomic>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/log.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>
}

#include "audio_time.h"
#include "log_sink.h"
#include "timeline_buffer.h"

static constexpr int CT_ALAC = 2, CT_AAC_LC = 4, CT_AAC_ELD = 8;

class LatencyReporter {
public:
    LatencyReporter(const char *name, LogSink &log) : mName(name), mLog(log) {}

    // no concurrent callers
    void record(int64_t ns) {
        if (mN == 0 || ns < mMinNs) mMinNs = ns;
        if (ns > mMaxNs) mMaxNs = ns;
        mSumNs += ns;
        if (++mN >= UPDATE_FREQ) {
            const int32_t meanUs = (int32_t)(mSumNs / mN / 1000);
            mMeanUs.store(meanUs, std::memory_order_relaxed);
            mMaxUs.store((int32_t)(mMaxNs / 1000), std::memory_order_relaxed);
            if (mLogging.load(std::memory_order_relaxed)) {
                mLog.info("%s latency: n=%lld min=%.2fms mean=%.2fms max=%.2fms (held=%zu)",
                          mName, (long long)mN, mMinNs / 1e6, meanUs / 1000.0, mMaxNs / 1e6,
                          mHeld.load(std::memory_order_relaxed));
            }
            mN = 0; mSumNs = 0; mMinNs = 0; mMaxNs = 0;
        }
    }

    // # of operations currently in flight
    void setHeld(size_t n) { mHeld.store(n, std::memory_order_relaxed); }

    void setEnableLogging(bool on) { mLogging.store(on, std::memory_order_relaxed); }

    // packed: nests into AudioDebugData with fixed layout
    struct __attribute__((packed)) Debug {
        int32_t meanUs, maxUs;  // latency in us
        uint16_t held;          // operations in flight
    };
    Debug debugInfo() const {
        Debug d{};
        d.meanUs = mMeanUs.load(std::memory_order_relaxed);
        d.maxUs = mMaxUs.load(std::memory_order_relaxed);
        d.held = (uint16_t)mHeld.load(std::memory_order_relaxed);
        return d;
    }

private:
    static constexpr int64_t UPDATE_FREQ = 200;

    const char *mName;
    LogSink &mLog;                                       // not owned
    std::atomic<bool> mLogging{false};
    int64_t mN = 0, mSumNs = 0, mMinNs = 0, mMaxNs = 0;  // producer-thread accumulators
    std::atomic<int32_t> mMeanUs{0}, mMaxUs{0};
    std::atomic<size_t> mHeld{0};
};

// https://stackoverflow.com/a/44095383
/*
 * SPSC queue to measure AMediaCodec decode latency: MediaCodec can't carry side data
 * across decode, so track queue times ourselves
 */
struct LatencyProbe {
    struct Entry { int64_t ptsUs; int64_t queueNs; };
    static constexpr uint64_t CAP = 64;
    Entry mBuf[CAP] = {};
    std::atomic<uint64_t> mWrite{0};
    std::atomic<uint64_t> mRead{0};

    // call before feeding frame into MediaCodec
    void record(int64_t ptsUs, int64_t queueNs) {
        const uint64_t w = mWrite.load(std::memory_order_relaxed);
        mBuf[w % CAP] = {ptsUs, queueNs};
        mWrite.store(w + 1, std::memory_order_release);
    }

    // call after frame comes back; latency ns for ptsUs, or -1 if not recorded
    int64_t match(int64_t ptsUs, int64_t nowNs) {
        uint64_t r = mRead.load(std::memory_order_relaxed);
        const uint64_t w = mWrite.load(std::memory_order_acquire);
        for (; r < w; r++) {
            if (mBuf[r % CAP].ptsUs == ptsUs) {
                const int64_t lat = nowNs - mBuf[r % CAP].queueNs;
                mRead.store(r + 1, std::memory_order_release);
                return lat;
            }
        }
        return -1;
    }

    size_t inFlight() const {
        return (size_t)(mWrite.load(std::memory_order_relaxed) -
                        mRead.load(std::memory_order_relaxed));
    }
};

// decodes one encoded airplay audio packet and pushes PCM onto the timeline
class Decoder {
public:
    virtual ~Decoder() = default;
    // called on RAOP receive thread; returns false = non-critical decode error
    virtual bool decode(const uint8_t *data, size_t len, int64_t ptsNs) = 0;
};

// 24-byte ALACSpecificConfig "magic cookie" (big-endian)
static inline void buildAlacMagicCookie(uint8_t out[24], int sampleRate, int channels, int spf) {
    const int frameLength = spf;
    const int bitDepth = 16, pb = 40, mb = 10, kb = 14;
    out[0] = (frameLength >> 24) & 0xFF;
    out[1] = (frameLength >> 16) & 0xFF;
    out[2] = (frameLength >> 8) & 0xFF;
    out[3] = frameLength & 0xFF;
    out[4] = 0;                /* compatibleVersion */
    out[5] = (uint8_t)bitDepth;
    out[6] = (uint8_t)pb;
    out[7] = (uint8_t)mb;
    out[8] = (uint8_t)kb;
    out[9] = (uint8_t)channels;
    out[10] = 0; out[11] = 0xFF; /* maxRun = 255 (big-endian) */
    out[12] = out[13] = out[14] = out[15] = 0; /* maxFrameBytes */
    out[16] = out[17] = out[18] = out[19] = 0; /* avgBitRate */
    out[20] = (sampleRate >> 24) & 0xFF;
    out[21] = (sampleRate >> 16) & 0xFF;
    out[22] = (sampleRate >> 8) & 0xFF;
    out[23] = sampleRate & 0xFF;
}

// cookie wrapped in its atom: size (4) + 'alac' + version/flags (4) + ALACSpecificConfig
static inline void buildAlacAtomCookie(uint8_t out[36], int sampleRate, int channels, int spf) {
    static const uint8_t hdr[12] = {0x00, 0x00, 0x00, 0x24, 'a', 'l', 'a', 'c', 0, 0, 0, 0};
    memcpy(out, hdr, sizeof(hdr));
    buildAlacMagicCookie(out + 12, sampleRate, channels, spf);
}

// AMediaCodec decoder; output dequeued on separate thread, shaves ~10ms latency
class MediaCodecDecoder : public Decoder {
public:
    // takes ownership of already-created+started codec
    MediaCodecDecoder(AMediaCodec *codec, TimelineBuffer &timeline, LatencyReporter &lat)
        : mCodec(codec), mTimeline(timeline), mLat(lat) {
        mDrainRun.store(true, std::memory_order_relaxed);
        mDrainThread = std::thread(&MediaCodecDecoder::drainLoop, this);
    }

    ~MediaCodecDecoder() override {
        // stop drain thread before touching the codec it reads from
        if (mDrainThread.joinable()) {
            mDrainRun.store(false, std::memory_order_relaxed);
            mDrainThread.join();
        }
        if (mCodec) { AMediaCodec_stop(mCodec); AMediaCodec_delete(mCodec); }
    }

    bool decode(const uint8_t *data, size_t len, int64_t ptsNs) override {
        ssize_t ii = AMediaCodec_dequeueInputBuffer(mCodec, 5000);
        if (ii < 0) return false;
        size_t cap = 0;
        uint8_t *in = AMediaCodec_getInputBuffer(mCodec, (size_t)ii, &cap);
        if (!in) return false;
        const size_t n = len < cap ? len : cap;
        memcpy(in, data, n);
        mProbe.record((int64_t)(ptsNs / 1000), monoNs());
        // still queue packets that are too large, but truncate them and report as error
        return AMediaCodec_queueInputBuffer(mCodec, (size_t)ii, 0, n, (uint64_t)(ptsNs / 1000), 0) ==
                   AMEDIA_OK && n == len;
    }

private:
    static constexpr int64_t DRAIN_TIMEOUT_US = 20000;

    void drainLoop() {
        AMediaCodecBufferInfo info;
        while (mDrainRun.load(std::memory_order_relaxed)) {
            ssize_t oi = AMediaCodec_dequeueOutputBuffer(mCodec, &info, DRAIN_TIMEOUT_US);
            if (oi < 0) continue;  // timeout / format / buffers changed
            size_t osz = 0;
            uint8_t *out = AMediaCodec_getOutputBuffer(mCodec, (size_t)oi, &osz);
            if (out && info.size > 0) {
                mTimeline.write((const int16_t *)(out + info.offset),
                                (size_t)info.size / sizeof(int16_t),  // bytes -> samples
                                (int64_t)info.presentationTimeUs * 1000);
            }
            const int64_t nowNs = monoNs();
            AMediaCodec_releaseOutputBuffer(mCodec, (size_t)oi, false);
            const int64_t lat = mProbe.match((int64_t)info.presentationTimeUs, nowNs);
            if (lat >= 0) mLat.record(lat);
            mLat.setHeld(mProbe.inFlight());
        }
    }

    AMediaCodec *mCodec;                  // owned
    TimelineBuffer &mTimeline;            // not owned
    LatencyReporter &mLat;                // not owned
    LatencyProbe mProbe;
    std::thread mDrainThread;
    std::atomic<bool> mDrainRun{false};   // drain thread stop signal
};

// route ffmpeg warnings/errors to logcat
static inline void installFfmpegLogcat() {
    static std::once_flag once;
    std::call_once(once, [] {
        av_log_set_level(AV_LOG_WARNING);
        av_log_set_callback([](void *, int level, const char *fmt, va_list vl) {
            if (level > av_log_get_level()) return;
            __android_log_vprint(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                                 "ffmpeg", fmt, vl);
        });
    });
}

// software ALAC via ffmpeg libavcodec; synchronous, decodes on caller's thread
class FfmpegAlacDecoder : public Decoder {
public:
    // returns nullptr if codec can't be created/opened
    static std::unique_ptr<FfmpegAlacDecoder> make(int sampleRate, int channels, int spf,
                                                   TimelineBuffer &timeline, LatencyReporter &lat,
                                                   LogSink &log) {
        installFfmpegLogcat();
        auto dec = std::unique_ptr<FfmpegAlacDecoder>(new FfmpegAlacDecoder(timeline, lat, log));
        if (!dec->init(sampleRate, channels, spf)) return nullptr;
        return dec;
    }

    ~FfmpegAlacDecoder() override {
        if (mFrame) av_frame_free(&mFrame);
        if (mPkt) av_packet_free(&mPkt);
        if (mCtx) avcodec_free_context(&mCtx);
    }

    bool decode(const uint8_t *data, size_t len, int64_t ptsNs) override {
        if (len == 0 || len > INT_MAX - AV_INPUT_BUFFER_PADDING_SIZE) return false;
        const int64_t t0 = monoNs();

        if (mPkt->size < (int)len) {
            if (av_grow_packet(mPkt, (int)len - mPkt->size) < 0) return false;
        } else {
            av_shrink_packet(mPkt, (int)len);
        }
        memcpy(mPkt->data, data, len);

        if (avcodec_send_packet(mCtx, mPkt) < 0) return false;

        int64_t pts = ptsNs;
        // the current libavcodec ALAC decoder always produces exactly 1 output
        // frame for each input packet, this loop is only for completeness
        while (avcodec_receive_frame(mCtx, mFrame) == 0) {
            const int n = mFrame->nb_samples;
            if (n <= 0) continue;
            if (mFrame->format == AV_SAMPLE_FMT_S16) {
                // this branch is not used as current libavcodec decoder cannot produce AV_SAMPLE_FMT_S16
                mTimeline.write((const int16_t *)mFrame->data[0], (size_t)n * mChannels, pts);
            } else if (mFrame->format == AV_SAMPLE_FMT_S16P) {
                // timeline expects packed/interleaved (AV_SAMPLE_FMT_S16), decoder gives planar
                if (mPcm.size() < (size_t)n * mChannels) mPcm.resize((size_t)n * mChannels);
                for (int c = 0; c < mChannels; c++) {
                    const int16_t *p = (const int16_t *)mFrame->data[c];
                    for (int i = 0; i < n; i++) mPcm[(size_t)i * mChannels + c] = p[i];
                }
                mTimeline.write(mPcm.data(), (size_t)n * mChannels, pts);
            } else {
                mLog.error("ffmpeg ALAC: unsupported sample format %d", mFrame->format);
                return false;
            }
            pts += (int64_t)n * NS_PER_SEC / mCtx->sample_rate;
        }

        mLat.record(monoNs() - t0);
        mLat.setHeld(0);
        return true;
    }

private:
    FfmpegAlacDecoder(TimelineBuffer &timeline, LatencyReporter &lat, LogSink &log)
        : mTimeline(timeline), mLat(lat), mLog(log) {}

    bool init(int sampleRate, int channels, int spf) {
        const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_ALAC);
        if (!codec) { mLog.error("ffmpeg ALAC decoder not compiled in"); return false; }
        mCtx = avcodec_alloc_context3(codec);
        if (!mCtx) return false;

        // decoder expects atom + magic cookie via extradata
        // extradata must be alloced with av_malloc* and have AV_INPUT_BUFFER_PADDING_SIZE of
        // 0-padding at the end
        mCtx->extradata = (uint8_t *)av_mallocz(36 + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!mCtx->extradata) return false;
        buildAlacAtomCookie(mCtx->extradata, sampleRate, channels, spf);
        mCtx->extradata_size = 36;

        mCtx->sample_rate = sampleRate;
        av_channel_layout_default(&mCtx->ch_layout, channels);
        mCtx->thread_count = 1;

        if (avcodec_open2(mCtx, codec, nullptr) < 0) {
            mLog.error("ffmpeg ALAC open failed (rate=%d ch=%d spf=%d)", sampleRate, channels, spf);
            return false;
        }

        mPkt = av_packet_alloc();
        mFrame = av_frame_alloc();
        if (!mPkt || !mFrame) return false;
        // alloc packet buffer, sized for largest possible ALAC packet ("escape" frame + 8-byte header).
        if (av_new_packet(mPkt, spf * channels * (int)sizeof(int16_t) + 8) < 0) return false;
        mChannels = channels;
        mPcm.resize((size_t)spf * channels);
        return true;
    }

    AVCodecContext *mCtx = nullptr;  // owned
    AVPacket *mPkt = nullptr;        // owned
    AVFrame *mFrame = nullptr;       // owned
    std::vector<int16_t> mPcm;       // interleave scratch
    TimelineBuffer &mTimeline;
    LatencyReporter &mLat;
    LogSink &mLog;
    int mChannels;
};

// ---- decoder factories ----
// create + configure + start AMediaCodec for `mime` with `fmt` (consumes fmt); tries
// `preferName` first if set, falls back to platform default; nullptr if none start
static inline AMediaCodec *startCodec(const char *mime, const char *preferName, AMediaFormat *fmt,
                                      LogSink &log) {
    AMediaCodec *codec = preferName ? AMediaCodec_createCodecByName(preferName) : nullptr;
    bool ok = codec &&
              AMediaCodec_configure(codec, fmt, nullptr, nullptr, 0) == AMEDIA_OK &&
              AMediaCodec_start(codec) == AMEDIA_OK;
    if (ok) {
        log.info("%s decoder started (%s)", mime, preferName);
    } else {
        if (codec) { AMediaCodec_delete(codec); codec = nullptr; }
        codec = AMediaCodec_createDecoderByType(mime);
        ok = codec &&
             AMediaCodec_configure(codec, fmt, nullptr, nullptr, 0) == AMEDIA_OK &&
             AMediaCodec_start(codec) == AMEDIA_OK;
        if (ok) log.info("%s decoder started (default)", mime);
    }
    AMediaFormat_delete(fmt);
    if (!ok && codec) { AMediaCodec_delete(codec); codec = nullptr; }
    return ok ? codec : nullptr;
}

// default AAC decoder runs sandboxed on newer devices; sandbox IPC costs ~15-30ms
// (Pixel 10), so request the in-process AAC decoder when available
static constexpr const char *AAC_INPROC_DECODER = "c2.android.inproc.aac.decoder";

// priority=0 requests realtime scheduling; low-latency=1 minimizes decoder buffering
static inline void setSchedulingHints(AMediaFormat *fmt, bool realtimePriority, bool lowLatency) {
    if (realtimePriority) AMediaFormat_setInt32(fmt, "priority", 0);
    if (lowLatency) AMediaFormat_setInt32(fmt, "low-latency", 1);
}

static inline AMediaCodec *startAacCodec(int ct, int spf, int sampleRate, int channels, LogSink &log,
                                         bool realtimePriority, bool lowLatency) {
    uint8_t csd[4]; size_t csdLen; int profile;
    if (ct == CT_AAC_ELD) {
        // AAC-ELD AudioSpecificConfig: AOT=39, 44100, stereo, then ELDSpecificConfig whose
        // first bit is frameLengthFlag (byte2 bit3): 1 = 480 samples/frame (0x50), 0 = 512 (0x40)
        profile = 39; csd[0] = 0xF8; csd[1] = 0xE8;
        csd[2] = (spf == 512) ? 0x40 : 0x50; csd[3] = 0x00; csdLen = 4;
    } else if (ct == CT_AAC_LC) {
        // AAC-LC AudioSpecificConfig: AOT=2, 44100, stereo, then GASpecificConfig whose
        // first bit is frameLengthFlag (byte1 bit5): 0 = 1024 samples/frame (0x10), 1 = 960 (0x14)
        profile = 2; csd[0] = 0x12;
        csd[1] = (spf == 960) ? 0x14 : 0x10; csdLen = 2;
    } else {
        log.error("Unknown audio ct=%d", ct); return nullptr;
    }
    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, "mime", "audio/mp4a-latm");
    AMediaFormat_setInt32(fmt, "sample-rate", sampleRate);
    AMediaFormat_setInt32(fmt, "channel-count", channels);
    AMediaFormat_setInt32(fmt, "aac-profile", profile);
    AMediaFormat_setInt32(fmt, "is-adts", 0);
    setSchedulingHints(fmt, realtimePriority, lowLatency);
    AMediaFormat_setBuffer(fmt, "csd-0", csd, csdLen);
    return startCodec("audio/mp4a-latm", AAC_INPROC_DECODER, fmt, log);
}

static inline AMediaCodec *startAlacHwCodec(int sampleRate, int channels, int spf, LogSink &log,
                                            bool realtimePriority, bool lowLatency) {
    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, "mime", "audio/alac");
    AMediaFormat_setInt32(fmt, "sample-rate", sampleRate);
    AMediaFormat_setInt32(fmt, "channel-count", channels);
    setSchedulingHints(fmt, realtimePriority, lowLatency);
    uint8_t csd[36];
    buildAlacAtomCookie(csd, sampleRate, channels, spf);
    AMediaFormat_setBuffer(fmt, "csd-0", csd, sizeof(csd));
    return startCodec("audio/alac", nullptr, fmt, log);
}

static inline std::unique_ptr<Decoder> makeDecoder(int ct, int spf, int sampleRate, int channels,
                                                   TimelineBuffer &timeline, LatencyReporter &lat,
                                                   LogSink &log, bool forceSwAlac,
                                                   bool realtimePriority, bool lowLatency) {
    if (ct == CT_ALAC) {
        if (!forceSwAlac) {
            if (AMediaCodec *codec = startAlacHwCodec(sampleRate, channels, spf, log,
                                                      realtimePriority, lowLatency)) {
                log.info("ALAC: hardware decoder");
                return std::make_unique<MediaCodecDecoder>(codec, timeline, lat);
            }
        }
        if (auto sw = FfmpegAlacDecoder::make(sampleRate, channels, spf, timeline, lat, log)) {
            log.info("ALAC: software decoder (ffmpeg)%s", forceSwAlac ? " (forced)" : "");
            return sw;
        }
        log.error("ALAC init failed (hw and sw)");
        return nullptr;
    }
    if (AMediaCodec *codec = startAacCodec(ct, spf, sampleRate, channels, log,
                                           realtimePriority, lowLatency)) {
        return std::make_unique<MediaCodecDecoder>(codec, timeline, lat);
    }
    log.error("AAC codec init failed (ct=%d)", ct);
    return nullptr;
}

#endif  // AUDIO_DECODER_H
