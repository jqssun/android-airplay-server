#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

enum class PacketInputStage { Queued, InvalidPacket, InputTimeout, DequeueFailure,
                              MissingBuffer, OversizePacket, QueueFailure };
struct PacketInputResult {
    PacketInputStage stage;
    int64_t code = 0;
    int retries = 0;
    size_t capacity = 0;
    bool ok() const { return stage == PacketInputStage::Queued; }
};

// The drain thread continues releasing inputs while we retry. Bound total wait to
// 20ms; never submit a truncated compressed packet. The adapter returns unused
// slots with an empty buffer (no EOS), preserving codec ownership on rejection.
template<class Codec>
PacketInputResult queueCodecPacket(Codec &codec, const uint8_t *data, size_t len, int64_t ptsUs) {
    if (!data || !len) return {PacketInputStage::InvalidPacket};
    int64_t slot = -1;
    int attempt = 0;
    for (; attempt < 4; ++attempt) {
        slot = codec.dequeue(5000);
        if (slot >= 0) break;
        if (slot != -1) return {PacketInputStage::DequeueFailure, slot, attempt};
    }
    if (slot < 0) return {PacketInputStage::InputTimeout, slot, attempt};
    size_t cap = 0;
    auto *in = codec.buffer(static_cast<size_t>(slot), cap);
    if (!in || len > cap) {
        const auto status = codec.cancel(static_cast<size_t>(slot), ptsUs);
        return {in ? PacketInputStage::OversizePacket : PacketInputStage::MissingBuffer,
                status, attempt, cap};
    }
    memcpy(in, data, len);
    const auto status = codec.queue(static_cast<size_t>(slot), len, ptsUs);
    return {status == 0 ? PacketInputStage::Queued : PacketInputStage::QueueFailure,
            status, attempt, cap};
}

inline const char *packetInputStageName(PacketInputStage stage) {
    switch (stage) {
        case PacketInputStage::Queued: return "queued";
        case PacketInputStage::InvalidPacket: return "invalid-packet";
        case PacketInputStage::InputTimeout: return "input-timeout";
        case PacketInputStage::DequeueFailure: return "dequeue-failure";
        case PacketInputStage::MissingBuffer: return "missing-buffer";
        case PacketInputStage::OversizePacket: return "oversize-packet";
        case PacketInputStage::QueueFailure: return "queue-failure";
    }
    return "unknown";
}
