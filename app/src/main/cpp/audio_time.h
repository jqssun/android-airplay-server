#ifndef AUDIO_TIME_H
#define AUDIO_TIME_H

#include <cstdint>
#include <ctime>

static constexpr int64_t NS_PER_SEC = 1000000000LL;

static inline int64_t monoNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * NS_PER_SEC + ts.tv_nsec;
}

// Compare sender PTS advancement with elapsed receive time. Silence/pause often
// stops AAC packets entirely, so PTS advancement by itself is not a clock jump.
static inline bool ptsDiscontinuity(int64_t ptsNs, int64_t previousPtsNs,
                                    int64_t nowNs, int64_t previousArrivalNs) {
    if (ptsNs <= 0 || previousPtsNs <= 0 || previousArrivalNs <= 0) return false;
    const int64_t shiftNs = (ptsNs - previousPtsNs) - (nowNs - previousArrivalNs);
    return shiftNs < -750'000'000LL || shiftNs > 750'000'000LL;
}

#endif  // AUDIO_TIME_H
