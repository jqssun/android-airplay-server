#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdint.h>
#include <stdatomic.h>

/* Fake only the buffer's monotonic clock so deadline tests are deterministic. */
static uint64_t fake_ns = 1000000000ULL;
static int udp_mode;
static int buffer_test_clock(clockid_t clock, struct timespec *ts) {
    if (udp_mode) return clock_gettime(clock, ts);
    (void)clock;
    ts->tv_sec = (time_t)(fake_ns / 1000000000ULL);
    ts->tv_nsec = (long)(fake_ns % 1000000000ULL);
    return 0;
}
#define clock_gettime buffer_test_clock
#include "raop_buffer.c"
#undef clock_gettime
#include "raop_rtp.c"

/* No actual encryption or remote clock negotiation is exercised here. */
struct aes_ctx_s { int unused; };
aes_ctx_t *aes_cbc_init(const uint8_t *key, const uint8_t *iv, aes_direction_t dir) {
    (void)key; (void)iv; (void)dir;
    return calloc(1, sizeof(aes_ctx_t));
}
void aes_cbc_reset(aes_ctx_t *ctx) { (void)ctx; }
void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    (void)ctx; memcpy(out, in, (size_t)len);
}
void aes_cbc_destroy(aes_ctx_t *ctx) { free(ctx); }
char *utils_data_to_string(const unsigned char *data, int size, int width) {
    (void)data; (void)size; (void)width; return strdup("");
}
uint64_t raop_ntp_get_local_time(void) { return fake_ns; }
uint64_t raop_ntp_get_video_arrival_offset(raop_ntp_t *ntp) { (void)ntp; return 0; }
uint64_t raop_remote_timestamp_to_nano_seconds(raop_ntp_t *ntp, uint64_t ts) { (void)ntp; return ts; }
uint64_t raop_ntp_convert_remote_time(raop_ntp_t *ntp, uint64_t ts) { (void)ntp; return ts; }

static logger_t *log_instance;
static _Atomic int flushes;
static raop_buffer_t *new_buffer(void) {
    unsigned char key[16] = {0};
    return raop_buffer_init(log_instance, key, key);
}
static void enqueue(raop_buffer_t *b, unsigned short seq, int kind) {
    unsigned char packet[32] = {0x80, 0x60};
    packet[2] = seq >> 8; packet[3] = seq & 255;
    uint32_t pts = (uint32_t)seq * 480;
    packet[4] = pts >> 24; packet[5] = pts >> 16;
    packet[6] = pts >> 8; packet[7] = pts;
    unsigned short len = 32;
    if (kind == 1) {
        unsigned char marker[] = {0x00, 0x68, 0x34, 0x00};
        memcpy(packet + 12, marker, 4); len = 16;
    } else if (kind == 2) len = 12;
    else memset(packet + 12, 0x42, 20);
    assert(raop_buffer_enqueue(b, packet, len, 1) >= 0);
}
static int pop(raop_buffer_t *b, int no_resend, unsigned short *seq, unsigned int *len) {
    uint32_t pts;
    void *p = raop_buffer_dequeue(b, len, &pts, seq, no_resend);
    if (!p) return 0;
    free(p); return 1;
}
static void test_silent_restart(void) {
    for (int start = 100; start <= 65530; start += 65430) {
        raop_buffer_t *b = new_buffer();
        unsigned short seq; unsigned int len;
        enqueue(b, (unsigned short)start, 0);
        assert(pop(b, 0, &seq, &len) && len == 20);
        for (int i = 1; i <= 8; i++) {
            enqueue(b, (unsigned short)(start + i), i % 2 ? 1 : 2);
            assert(pop(b, 0, &seq, &len) && len == 0);
            assert(seq == (unsigned short)(start + i));
            /* Duplicate silent packets must not rewind the read cursor. */
            enqueue(b, seq, 1);
            assert(!pop(b, 0, &seq, &len));
        }
        enqueue(b, (unsigned short)(start + 9), 0);
        assert(pop(b, 0, &seq, &len) && len == 20);
        assert(seq == (unsigned short)(start + 9));
        raop_buffer_destroy(b);
    }
    puts("PASS silent packets and restart, including sequence wraparound");
}
static void test_reordering(void) {
    raop_buffer_t *b = new_buffer();
    unsigned short seq; unsigned int len;
    enqueue(b, 100, 0); assert(pop(b, 0, &seq, &len));
    enqueue(b, 102, 0); assert(!pop(b, 0, &seq, &len));
    fake_ns += 50000000;
    enqueue(b, 101, 0);
    assert(pop(b, 0, &seq, &len) && seq == 101);
    assert(pop(b, 0, &seq, &len) && seq == 102);
    raop_buffer_destroy(b);
    puts("PASS reordered packets recovered inside resend window");
}
static void test_deadline(void) {
    raop_buffer_t *b = new_buffer();
    unsigned short seq; unsigned int len;
    enqueue(b, 65534, 0); assert(pop(b, 0, &seq, &len));
    enqueue(b, 5, 0); assert(!pop(b, 0, &seq, &len));
    fake_ns += 99000000; assert(!pop(b, 0, &seq, &len));
    fake_ns += 1000000;
    /* No new packet has arrived: a timeout poll must release this short tail. */
    assert(pop(b, 0, &seq, &len) && seq == 5);
    enqueue(b, 65535, 0); assert(!pop(b, 0, &seq, &len));
    enqueue(b, 9, 0); assert(pop(b, 1, &seq, &len) && seq == 9);
    raop_buffer_destroy(b);
    puts("PASS missing packets expire after 100 ms; no-resend skips immediately");
}
static void on_flush(void *cls) { (void)cls; flushes++; }
static void test_flush(void) {
    raop_rtp_t rtp = {0};
    rtp.logger = log_instance;
    rtp.buffer = new_buffer();
    rtp.running = 1; rtp.flush = NO_FLUSH;
    rtp.callbacks.audio_flush = on_flush;
    MUTEX_CREATE(rtp.run_mutex);
    enqueue(rtp.buffer, 100, 0);
    enqueue(rtp.buffer, 102, 0);
    raop_rtp_flush(&rtp, 900);
    assert(raop_rtp_process_events(&rtp, NULL) == 0);
    assert(flushes == 1);
    unsigned short seq; unsigned int len;
    assert(!pop(rtp.buffer, 0, &seq, &len));
    enqueue(rtp.buffer, 899, 0); assert(!pop(rtp.buffer, 0, &seq, &len));
    enqueue(rtp.buffer, 900, 0); assert(pop(rtp.buffer, 0, &seq, &len) && seq == 900);
    raop_rtp_flush(&rtp, -1);
    assert(raop_rtp_process_events(&rtp, NULL) == 0);
    // FLUSH on an already-empty/new buffer still rejects older sequence numbers.
    raop_rtp_flush(&rtp, 1000);
    assert(raop_rtp_process_events(&rtp, NULL) == 0);
    enqueue(rtp.buffer, 999, 0); assert(!pop(rtp.buffer, 0, &seq, &len));
    enqueue(rtp.buffer, 1000, 0); assert(pop(rtp.buffer, 0, &seq, &len) && seq == 1000);
    raop_rtp_flush(&rtp, -1);
    assert(raop_rtp_process_events(&rtp, NULL) == 0);
    enqueue(rtp.buffer, 10, 0); assert(pop(rtp.buffer, 0, &seq, &len) && seq == 10);
    MUTEX_DESTROY(rtp.run_mutex);
    raop_buffer_destroy(rtp.buffer);
    puts("PASS sender FLUSH clears RTP buffer and invokes downstream callback");
}
static void reproduce_baseline_stall(void) {
    raop_buffer_t *b = new_buffer();
    unsigned short seq; unsigned int len;
    enqueue(b, 100, 0); assert(pop(b, 0, &seq, &len));
    enqueue(b, 101, 1); /* Ignored by the original implementation. */
    enqueue(b, 102, 0);
    int frames = 0;
    for (;;) {
        if (pop(b, 0, &seq, &len)) {
            if (len > 0) break;
            continue;
        }
        if (frames >= 300) break;
        frames++; fake_ns += 480ULL * 1000000000ULL / 44100;
        enqueue(b, (unsigned short)(102 + frames), 0);
    }
    printf("Sequence-gap recovery: %d additional packets, %.3f seconds of audio\n",
           frames, frames * 480.0 / 44100);
    raop_buffer_destroy(b);
    assert(frames < 20);
}
static _Atomic int received, last_seq;
static void on_audio(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void)cls; (void)ntp;
    assert(data->data_len == 20); // no silent placeholders forwarded to decoder
    atomic_store(&last_seq, data->seqnum);
    atomic_fetch_add(&received, 1);
}
static void wait_for(_Atomic int *counter, int target) {
    struct timespec delay = {0, 1000000};
    for (int i = 0; i < 500 && atomic_load(counter) < target; i++) nanosleep(&delay, NULL);
    assert(atomic_load(counter) == target);
}
static void udp_send(int sock, unsigned short port, const void *packet, size_t len) {
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET; addr.sin_port = htons(port);
    assert(inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr) == 1);
    assert(sendto(sock, packet, len, 0, (struct sockaddr *)&addr, sizeof(addr)) == (ssize_t)len);
}
static void udp_audio(int sock, unsigned short port, unsigned short seq, int silent) {
    unsigned char packet[32] = {0x80, 0x60};
    packet[2] = seq >> 8; packet[3] = seq;
    uint32_t pts = (uint32_t)seq * 480;
    packet[4] = pts >> 24; packet[5] = pts >> 16;
    packet[6] = pts >> 8; packet[7] = pts;
    if (silent) {
        unsigned char marker[] = {0x00, 0x68, 0x34, 0x00};
        memcpy(packet + 12, marker, 4);
    } else memset(packet + 12, 0x42, 20);
    udp_send(sock, port, packet, silent ? 16 : 32);
}
static void test_udp(void) {
    udp_mode = 1;
    raop_callbacks_t callbacks = {0};
    callbacks.audio_process = on_audio;
    callbacks.audio_flush = on_flush;
    unsigned char key[16] = {0};
    const char ip[] = "127.0.0.1";
    raop_rtp_t *rtp = raop_rtp_init(log_instance, &callbacks, NULL, ip, 4, key, key);
    assert(rtp);
    unsigned short sender_port = 0, control_port = 0, audio_port = 0;
    int sock = netutils_init_socket(&sender_port, 0, 1);
    assert(sock >= 0);
    unsigned char ct = 8;
    unsigned int rate = 44100;
    raop_rtp_start_audio(rtp, &sender_port, &control_port, &audio_port, &ct, &rate);
    assert(control_port && audio_port);
    unsigned char sync[20] = {0x80, 0xd4};
    udp_send(sock, control_port, sync, sizeof(sync));
    udp_audio(sock, audio_port, 100, 0);
    wait_for(&received, 1);
    udp_audio(sock, audio_port, 101, 1);
    udp_audio(sock, audio_port, 102, 0);
    wait_for(&received, 2);
    assert(atomic_load(&last_seq) == 102);
    udp_audio(sock, audio_port, 104, 0); // 103 lost; no later packets to wake drain
    struct timespec delay = {0, 40000000};
    nanosleep(&delay, NULL);
    assert(atomic_load(&received) == 2);
    wait_for(&received, 3);
    assert(atomic_load(&last_seq) == 104);
    int next_flush = atomic_load(&flushes) + 1;
    raop_rtp_flush(rtp, 900);
    wait_for(&flushes, next_flush);
    udp_audio(sock, audio_port, 900, 0);
    wait_for(&received, 4);
    assert(atomic_load(&last_seq) == 900);
    raop_rtp_destroy(rtp);
    close(sock);
    udp_mode = 0;
    puts("PASS real UDP receive loop: silent restart, 100 ms timeout drain, FLUSH");
}
int main(void) {
    setbuf(stdout, NULL);
    log_instance = logger_init();
    logger_set_level(log_instance, LOGGER_ERR);
    reproduce_baseline_stall();
    test_silent_restart();
    test_reordering();
    test_deadline();
    test_flush();
    if (getenv("AIRPLAY_TEST_UDP")) test_udp();
    logger_destroy(log_instance);
    return 0;
}
