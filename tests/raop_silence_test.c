// Compile the actual receiver queue, not a copy of its algorithm.
// Crypto is an identity stub: these tests cover RTP ordering/silence, not AES.
#include "../app/src/main/cpp/third_party/UxPlay/lib/raop_buffer.c"

void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    (void)ctx;
    memcpy(out, in, len);
}
void aes_cbc_reset(aes_ctx_t *ctx) { (void)ctx; }

static void packet(unsigned char *out, unsigned short seq, int empty) {
    memset(out, 0, 44);
    out[0] = 0x80; out[1] = 0x60;
    out[2] = seq >> 8; out[3] = seq;
    if (empty) {
        out[12] = 0x00; out[13] = 0x68; out[14] = 0x34; out[15] = 0x00;
    } else {
        out[12] = seq >> 8; out[13] = seq;
    }
}

static int pop(raop_buffer_t *queue, int no_resend, unsigned short expected) {
    unsigned int length = 0;
    uint32_t timestamp = 0;
    unsigned short seq = 0;
    unsigned char *payload = raop_buffer_dequeue(queue, &length, &timestamp, &seq, no_resend);
    if (!payload) return 0;
    assert(length == 32 && seq == expected);
    assert(payload[0] == (expected >> 8) && payload[1] == (unsigned char)expected);
    free(payload);
    return 1;
}

static void short_tones(int no_resend, unsigned short start) {
    raop_buffer_t queue = {0};
    queue.is_empty = 1;
    unsigned char data[44];
    // 8 AAC packets with sound, then 176 explicitly empty packets (~2 seconds).
    // The original queue loses every second burst until its 256-slot window overflows.
    for (int burst = 0; burst < 12; ++burst) {
        for (int n = 0; n < 8; ++n) {
            const unsigned short seq = start + burst * 184 + n;
            packet(data, seq, 0);
            raop_buffer_enqueue(&queue, data, 44, 1);
            if (!pop(&queue, no_resend, seq)) {
                fprintf(stderr, "FAIL: silence stalled burst=%d seq=%u no_resend=%d\n",
                        burst, seq, no_resend);
                raop_buffer_flush(&queue, -1);
                abort();
            }
        }
        for (int n = 8; n < 184; ++n) {
            const unsigned short seq = start + burst * 184 + n;
            packet(data, seq, 1);
            // Test both forms of empty RTP packets (12-byte header and 16-byte marker).
            raop_buffer_enqueue(&queue, data, n % 2 ? 12 : 16, 1);
            assert(!pop(&queue, no_resend, seq));
        }
    }
    raop_buffer_flush(&queue, -1);
}

static void retransmit_and_gaps(void) {
    raop_buffer_t queue = {0}; queue.is_empty = 1;
    unsigned char data[44];
    packet(data, 10, 0); raop_buffer_enqueue(&queue, data, 44, 1);
    assert(pop(&queue, 0, 10));
    packet(data, 12, 0); raop_buffer_enqueue(&queue, data, 44, 1);
    assert(!pop(&queue, 0, 12)); // Real missing packet still waits for retransmission.
    packet(data, 11, 0); raop_buffer_enqueue(&queue, data, 44, 1);
    assert(pop(&queue, 0, 11));
    assert(pop(&queue, 0, 12));
    assert(raop_buffer_enqueue(&queue, data, 44, 1) == 0); // Duplicate stays ignored.
    packet(data, 100, 0); raop_buffer_enqueue(&queue, data, 44, 1);
    assert(pop(&queue, 1, 100)); // Without retransmits, skip holes to ready data.
    raop_buffer_flush(&queue, -1);
}

int main(void) {
    short_tones(0, 100);
    short_tones(1, 100);
    short_tones(0, 65500);
    short_tones(1, 65500);
    retransmit_and_gaps();
    puts("PASS: 12 short tones across empty RTP, both resend modes, sequence wrap, real loss, duplicates");
    return 0;
}
