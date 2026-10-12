#include "timeline_buffer.h"
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>

static constexpr int64_t MS = 1'000'000LL;
static constexpr int64_t PTS = 1'760'000'000LL * NS_PER_SEC;
static constexpr int64_t NOW = 100LL * NS_PER_SEC;

static void expectNear(int64_t actual, int64_t expected) {
    assert(std::llabs(actual - expected) <= 1);
}

int main() {
    assert(!ptsDiscontinuity(PTS + 2 * NS_PER_SEC, PTS, NOW + 2 * NS_PER_SEC, NOW));
    assert(!ptsDiscontinuity(PTS + 10 * MS, PTS, NOW + 20 * MS, NOW));
    assert(ptsDiscontinuity(PTS - NS_PER_SEC, PTS, NOW + 10 * MS, NOW));
    assert(ptsDiscontinuity(PTS + 10 * NS_PER_SEC, PTS, NOW + 10 * MS, NOW));
    assert(!ptsDiscontinuity(PTS, 0, NOW, 0));
    {
        AudioPlayoutClock clock;
        clock.publish(PTS, NOW + 70 * MS); // 60ms reported HAL + 10ms app queue
        expectNear(clock.renderTime(PTS, NOW), NOW + 70 * MS);
    }
    {
        TimelineBuffer timeline(1000, 2, 20, 95);
        std::vector<int16_t> pcm(20, 11), out(10);
        timeline.write(pcm.data(), pcm.size(), PTS);
        timeline.read(out.data(), 5, NOW, 10 * MS);
        for (auto sample : out) assert(sample == 0); // cushion still priming
        assert(timeline.renderTime(PTS, NOW) == 0);
        timeline.write(pcm.data(), pcm.size(), PTS + 10 * MS);
        timeline.read(out.data(), 5, NOW + 5 * MS, 10 * MS);
        for (auto sample : out) assert(sample == 11);
        expectNear(timeline.renderTime(PTS, NOW + 5 * MS), NOW + 15 * MS);
        expectNear(timeline.renderTime(PTS + 15 * MS, NOW + 5 * MS), NOW + 30 * MS);
        timeline.read(out.data(), 5, NOW + 10 * MS, 10 * MS);
        expectNear(timeline.renderTime(PTS + 5 * MS, NOW + 10 * MS), NOW + 20 * MS);

        // An old queue and a backward PTS must not contaminate a resumed clip.
        timeline.flushAndReprime();
        assert(timeline.debugInfo().backlogMs == 0);
        assert(timeline.renderTime(PTS, NOW + 10 * MS) == 0);
        pcm.assign(40, 22);
        const int64_t seekPts = PTS - 200 * MS;
        timeline.write(pcm.data(), pcm.size(), seekPts);
        timeline.read(out.data(), 5, NOW + 20 * MS, 10 * MS);
        for (auto sample : out) assert(sample == 22);
        expectNear(timeline.renderTime(seekPts, NOW + 20 * MS), NOW + 30 * MS);
        assert(timeline.debugInfo().metrics.drops == 0);
        assert(timeline.renderTime(seekPts, NOW + 331 * MS) == 0); // expired clock
        assert(timeline.renderTime(seekPts + 1 * NS_PER_SEC, NOW + 20 * MS) == 0);

        // Silence must not refresh the last PCM clock indefinitely. Its mapping
        // survives briefly for late video frames, then expires without new PCM.
        std::vector<int16_t> largeOut(60);
        timeline.read(largeOut.data(), 30, NOW + 25 * MS, 0);
        timeline.read(out.data(), 5, NOW + 55 * MS, 0);
        assert(timeline.renderTime(seekPts, NOW + 326 * MS) == 0);
        timeline.flushAndReprime();
        timeline.write(pcm.data(), pcm.size(), 0); // NTP not synchronized
        timeline.read(out.data(), 5, NOW + 60 * MS, 0);
        assert(timeline.renderTime(seekPts, NOW + 60 * MS) == 0);
    }
    {
        // A gap is played once as silence and preserves the PCM/PTS relationship.
        TimelineBuffer timeline(1000, 2, 1, 95);
        std::vector<int16_t> pcm(20, 33), out(80);
        timeline.write(pcm.data(), pcm.size(), PTS);
        timeline.write(pcm.data(), pcm.size(), PTS + 50 * MS);
        timeline.read(out.data(), 40, NOW, 0);
        for (int i = 0; i < 20; ++i) assert(out[i] == 33);
        for (int i = 20; i < 80; ++i) assert(out[i] == 0);
        expectNear(timeline.renderTime(PTS + 50 * MS, NOW), NOW + 50 * MS);
    }
    {
        // Concurrent anchor reads must never combine PTS from one callback with
        // monotonic time from another, including on a 32-bit target.
        TimestampAnchor anchor;
        std::atomic<bool> done{false};
        std::thread writer([&] {
            for (int64_t n = 1; n <= 200000; ++n) anchor.publish(n, n * 7);
            done.store(true);
        });
        do {
            TimestampAnchor::Snapshot snapshot{};
            if (anchor.snapshot(snapshot)) assert(snapshot.reference == snapshot.ptsNs * 7);
        } while (!done.load());
        writer.join();
    }
    {
        SpscRing ring(16);
        std::vector<int16_t> src(24, 44), out(16);
        assert(ring.write(src.data(), src.size()) == 16);
        assert(ring.available() == 16);
        ring.skip(8);
        assert(ring.read(out.data(), 16) == 8);
        assert(ring.available() == 0);
    }
    {
        // Exercise the actual H10 configuration: 44.1kHz stereo, 480-frame
        // callbacks, 40ms cushion, a short sound every 2s with no PCM in between.
        TimelineBuffer timeline(44100, 2, 40, 95, true);
        std::vector<int16_t> pcm(960, 4096), out(960);
        uint64_t samplesWritten = 0;
        int bursts = 0;
        bool previouslyActive = false;
        for (int64_t frame = 0; frame < 24 * 44100; frame += 480) {
            const int64_t stepNs = frame * NS_PER_SEC / 44100;
            if (frame % (2 * 44100) < 8 * 480) {
                timeline.write(pcm.data(), pcm.size(), PTS + stepNs);
                samplesWritten += pcm.size();
            }
            timeline.read(out.data(), 480, NOW + stepNs, 70 * MS);
            const bool active = std::any_of(out.begin(), out.end(), [](int16_t s) { return s != 0; });
            if (active && !previouslyActive) ++bursts;
            previouslyActive = active;
        }
        const auto signal = timeline.signalDebug();
        assert(bursts == 12);
        assert(signal.decodedActive == samplesWritten);
        assert(signal.acceptedActive == samplesWritten);
        assert(signal.outputActive == samplesWritten);
        assert(timeline.debugInfo().metrics.drops == 0);
    }
    std::cout << "PASS: priming, audio/video clock mapping, queue reset, backward PTS, "
                 "silence gap, clock expiry, unknown NTP, concurrent snapshot, ring bounds, "
                 "idle versus clock jump, reported HAL latency, 12 short sounds at H10 settings\n";
}
