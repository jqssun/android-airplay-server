#include "timeline_buffer.h"
#include <array>
#include <cassert>
#include <iostream>

int main() {
    TimelineBuffer buffer(44100, 2, 1, 95);
    std::array<int16_t, 960> oldSound, newSound, output;
    oldSound.fill(111);
    newSound.fill(222);
    buffer.write(oldSound.data(), oldSound.size(), 10 * NS_PER_SEC);
    buffer.flush();
    // New audio may be decoded before the output thread sees the FLUSH.
    buffer.write(newSound.data(), newSound.size(), 20 * NS_PER_SEC);
    buffer.read(output.data(), 480);
    for (auto sample : output) assert(sample == 222);
    assert(buffer.debugInfo().metrics.drops == 0);
    // Repeated flush, even while empty, must preserve the latest generation.
    for (int i = 0; i < 100; i++) {
        buffer.write(oldSound.data(), oldSound.size(), (30 + i) * NS_PER_SEC);
        buffer.flush();
        buffer.write(oldSound.data(), oldSound.size(), (40 + i) * NS_PER_SEC);
        buffer.flush();
        buffer.write(newSound.data(), newSound.size(), (50 + i) * NS_PER_SEC);
        buffer.read(output.data(), 480);
        for (auto sample : output) assert(sample == 222);
    }
    buffer.flush();
    buffer.read(output.data(), 480);
    for (auto sample : output) assert(sample == 0);
    std::cout << "PASS FLUSH discards old PCM, keeps new audio, resets timestamps; 100 repeated seeks\n";

    DelayTracker tracker(44100, 2, 0, 95);
    for (int i = 0; i < 100; i++)
        tracker.observe(i * 10000000LL, i * 10000000LL + (i % 2) * 500000000LL, 10000000);
    assert(tracker.target() > 0);
    tracker.reset();
    assert(tracker.target() == 0);
    std::cout << "PASS adaptive jitter history reset on new timeline\n";
}
