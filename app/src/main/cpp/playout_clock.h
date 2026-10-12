#ifndef PLAYOUT_CLOCK_H
#define PLAYOUT_CLOCK_H

#include <atomic>
#include <cstdint>

// One writer, many readers. Bounded retries keep the audio callback non-blocking.
// Both fields are atomic: unlike a plain seqlock, this has no C++ data race.
class TimestampAnchor {
public:
    struct Snapshot { int64_t ptsNs; int64_t reference; };

    void publish(int64_t ptsNs, int64_t reference) {
        mSequence.fetch_add(1);
        mPtsNs.store(ptsNs);
        mReference.store(reference);
        mSequence.fetch_add(1);
    }

    bool snapshot(Snapshot &out) const {
        for (int attempt = 0; attempt < 3; ++attempt) {
            const uint32_t before = mSequence.load();
            if (before & 1) continue;
            const Snapshot value{mPtsNs.load(), mReference.load()};
            if (before == mSequence.load()) {
                out = value;
                return value.ptsNs > 0;
            }
        }
        return false;
    }

private:
    std::atomic<uint32_t> mSequence{0};
    std::atomic<int64_t> mPtsNs{0};
    std::atomic<int64_t> mReference{0};
};

class AudioPlayoutClock {
public:
    void publish(int64_t ptsNs, int64_t outputMonoNs) { mAnchor.publish(ptsNs, outputMonoNs); }
    void invalidate() { mAnchor.publish(0, 0); }

    // This is PCM submission time + estimated backend queue duration, not a
    // hardware/speaker timestamp. API 24's OpenSL ES backend lacks that timestamp.
    int64_t renderTime(int64_t ptsNs, int64_t nowNs) const {
        TimestampAnchor::Snapshot anchor{};
        if (ptsNs <= 0 || !mAnchor.snapshot(anchor)) return 0;
        if (nowNs - anchor.reference > 300'000'000LL) return 0;
        const int64_t time = anchor.reference + (ptsNs - anchor.ptsNs);
        // Ignore unmatched/unsynchronized clock epochs rather than holding a
        // MediaCodec output buffer for seconds. Silent mirroring falls back.
        if (time < nowNs - 500'000'000LL || time > nowNs + 250'000'000LL) return 0;
        return time;
    }

private:
    TimestampAnchor mAnchor;
};

#endif
