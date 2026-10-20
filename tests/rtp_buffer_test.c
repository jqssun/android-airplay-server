/* Run: sh tests/test_rtp_buffer.sh
 * Exercises the real queue; marker filtering models the RTP caller.
 * Does not test the socket loop, legacy 12-byte normalization, or codecs.
 */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "raop_buffer.h"
#include "crypto.h"

/* Four-byte payloads have no full AES block; encryption is outside this test. */
aes_ctx_t *aes_cbc_init(const uint8_t *key, const uint8_t *iv, aes_direction_t dir) {
    static unsigned char context;
    return (aes_ctx_t *)&context;
}
void aes_cbc_reset(aes_ctx_t *ctx) {}
void aes_cbc_destroy(aes_ctx_t *ctx) {}
void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    assert(len == 0);
}
void logger_log(logger_t *logger, int level, const char *fmt, ...) {}

static raop_buffer_t *create(void) {
    unsigned char key[16] = {0};
    raop_buffer_t *b = raop_buffer_init(NULL, key, key);
    assert(b);
    return b;
}
static unsigned markers_seen, resend_requests;
static int feed(raop_buffer_t *b, uint16_t seq, int marker) {
    unsigned char packet[16] = {0x80, 0x60};
    packet[2] = seq >> 8; packet[3] = seq;
    packet[4] = seq >> 8; packet[5] = seq;
    packet[12] = marker ? 0 : 0x81;
    packet[13] = marker ? 0x68 : 1;
    packet[14] = marker ? 0x34 : 2;
    packet[15] = marker ? 0 : 3;
    int result = raop_buffer_enqueue(b, packet, sizeof(packet), 1);
    return result;
}
static unsigned drain(raop_buffer_t *b, int no_resend, uint16_t *first) {
    unsigned count = 0, len;
    uint32_t timestamp;
    uint16_t seq;
    void *data;
    while ((data = raop_buffer_dequeue(b, &len, &timestamp, &seq, no_resend))) {
        assert(len == 4);
        assert(timestamp == (uint32_t)seq << 16);
        if (memcmp(data, "\x00\x68\x34\x00", 4) == 0) {
            ++markers_seen; /* The RTP caller consumes these, never the codec. */
            free(data);
            continue;
        }
        assert(memcmp(data, "\x81\x01\x02\x03", 4) == 0);
        if (!count && first) *first = seq;
        free(data);
        ++count;
    }
    return count;
}
static int resend(void *opaque, unsigned short first, unsigned short count) {
    ++resend_requests;
    return 0;
}
/* Drive the queue as the RTP receive loop does after initial sync. */
static void silence(int fixed) {
    const unsigned starts[] = {100, 65535};
    const unsigned lengths[] = {20, 600};
    for (unsigned i = 0; i < 2; ++i) {
        for (unsigned j = 0; j < 2; ++j) {
            raop_buffer_t *b = create();
            unsigned start = starts[i], count = lengths[j];
            unsigned before = markers_seen;
            feed(b, start, 0); assert(drain(b, 0, NULL) == 1);
            for (unsigned k = 1; k <= count; ++k) {
                assert(feed(b, start + k, 1) == fixed);
                assert(drain(b, 0, NULL) == 0);
            }
            assert(markers_seen - before == (fixed ? count : 0));
            feed(b, start + count + 1, 0);
            unsigned delivered = drain(b, 0, NULL);
            resend_requests = 0;
            raop_buffer_handle_resends(b, resend, NULL);
            if (fixed || count >= 256) {
                assert(delivered == 1 && resend_requests == 0);
            } else {
                assert(delivered == 0 && resend_requests == 1);
            }
            raop_buffer_destroy(b);
        }
    }

    raop_buffer_t *b = create();
    feed(b, 100, 0); assert(drain(b, 0, NULL) == 1);
    feed(b, 102, 0); assert(drain(b, 0, NULL) == 0);
    resend_requests = 0;
    raop_buffer_handle_resends(b, resend, NULL);
    assert(resend_requests == 1); /* A genuinely lost marker still needs recovery. */
    assert(feed(b, 101, 1) == fixed);
    assert(drain(b, 0, NULL) == (fixed ? 1u : 0u));
    raop_buffer_destroy(b);

    b = create();
    feed(b, 100, 0); assert(drain(b, 0, NULL) == 1);
    feed(b, 103, 0); feed(b, 102, 0);
    assert(drain(b, 0, NULL) == 0);
    resend_requests = 0;
    raop_buffer_handle_resends(b, resend, NULL);
    assert(resend_requests == 1); /* Real loss still waits for retransmission. */
    feed(b, 101, 0);
    uint16_t first;
    assert(drain(b, 0, &first) == 3 && first == 101);
    raop_buffer_destroy(b);

    /* Flush and destroy while markers are still queued behind a real gap. */
    for (int flush = 0; flush < 2; ++flush) {
        b = create();
        feed(b, 100, 0); assert(drain(b, 0, NULL) == 1);
        feed(b, 102, 1);
        if (flush) {
            raop_buffer_flush(b, -1);
            assert(drain(b, 0, NULL) == 0);
            feed(b, 500, 0); assert(drain(b, 0, NULL) == 1);
        }
        raop_buffer_destroy(b);
    }
    puts(fixed ? "PASS silence: immediate audio, no false resend, marker recovery" :
                 "REPRODUCED original silence gap and false resend");
}
int main(int argc, char **argv) {
    assert(argc == 2);
    assert(strcmp(argv[1], "original") == 0 || strcmp(argv[1], "fixed") == 0);
    silence(strcmp(argv[1], "fixed") == 0);
    return 0;
}
