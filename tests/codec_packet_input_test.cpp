#include "codec_packet_input.h"
#include <array>
#include <cassert>
#include <iostream>
#include <vector>
struct FakeCodec {
    std::vector<int64_t> slots{0};
    size_t position = 0, capacity = 16;
    std::array<uint8_t, 16> bytes{};
    bool missing = false;
    int status = 0, queued = 0, cancelled = 0;
    size_t queuedBytes = 0;
    int64_t queuedPts = 0;
    int64_t dequeue(int64_t timeout) {
        assert(timeout == 5000);
        const auto p = position++;
        return p < slots.size() ? slots[p] : -1;
    }
    uint8_t *buffer(size_t slot, size_t &cap) {
        assert(slot == 0); cap = capacity; return missing ? nullptr : bytes.data();
    }
    int64_t cancel(size_t slot, int64_t pts) {
        assert(slot == 0); ++cancelled; queuedPts = pts; return 0;
    }
    int64_t queue(size_t slot, size_t len, int64_t pts) {
        assert(slot == 0); ++queued; queuedBytes = len; queuedPts = pts; return status;
    }
};
int main() {
    const uint8_t packet[]{1, 2, 3, 4};
    FakeCodec retry; retry.slots = {-1, -1, 0};
    auto r = queueCodecPacket(retry, packet, sizeof(packet), 1234567);
    assert(r.ok() && r.retries == 2 && retry.queued == 1 && retry.queuedBytes == 4);
    assert(retry.queuedPts == 1234567 && retry.bytes[3] == 4);
    FakeCodec timeout; timeout.slots = {-1};
    r = queueCodecPacket(timeout, packet, 4, 0);
    assert(r.stage == PacketInputStage::InputTimeout && timeout.position == 4 && !timeout.queued);
    FakeCodec oversize; oversize.capacity = 2; oversize.bytes.fill(0xAA);
    r = queueCodecPacket(oversize, packet, 4, 9);
    assert(r.stage == PacketInputStage::OversizePacket && !oversize.queued && oversize.cancelled == 1);
    assert(oversize.bytes[0] == 0xAA && oversize.bytes[2] == 0xAA);
    FakeCodec absent; absent.missing = true;
    r = queueCodecPacket(absent, packet, 4, 0);
    assert(r.stage == PacketInputStage::MissingBuffer && absent.cancelled == 1);
    FakeCodec error; error.slots = {-10000};
    r = queueCodecPacket(error, packet, 4, 0);
    assert(r.stage == PacketInputStage::DequeueFailure && error.position == 1);
    FakeCodec queueError; queueError.status = -10000;
    r = queueCodecPacket(queueError, packet, 4, 0);
    assert(r.stage == PacketInputStage::QueueFailure && r.code == -10000);
    FakeCodec empty;
    assert(queueCodecPacket(empty, nullptr, 4, 0).stage == PacketInputStage::InvalidPacket);
    assert(queueCodecPacket(empty, packet, 0, 0).stage == PacketInputStage::InvalidPacket);
    assert(empty.position == 0);
    std::cout << "PASS codec input: retry, bounded timeout, whole packet, missing buffer, dequeue/queue errors, invalid input\n";
}
