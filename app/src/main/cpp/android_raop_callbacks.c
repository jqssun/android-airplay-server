/*
 * Implements raop_callbacks_t by forwarding to Java/Kotlin via JNI.
 * All callbacks fire from RAOP's internal pthreads, so we AttachCurrentThread.
 */

#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <android/log.h>
#include "android_raop_callbacks.h"

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JNIEnv *_get_env(android_callback_ctx_t *ctx) {
    JNIEnv *env = NULL;
    int status = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);
    }
    /* Clear any pending exception from a previous callback on this thread,
       otherwise JNI calls like NewByteArray will fatally abort. */
    if (env && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    return env;
}

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj) {
    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->callback_obj = (*env)->NewGlobalRef(env, callback_obj);
    ctx->h265_enabled = 1;
    ctx->require_pin = 0;
    ctx->registered_count = 0;
    memset(ctx->registered_keys, 0, sizeof(ctx->registered_keys));

    pthread_mutex_init(&ctx->playback_info_lock, NULL);
    ctx->last_video_request_ns = 0;
    ctx->video_request_count = 0;
    pthread_cond_init(&ctx->play_ready_cond, NULL);
    ctx->play_ready = 0;
    ctx->playback_position = 0.0;
    /* NOT -1.0: raop.c's GET /playback-info handler treats duration==-1.0 as "video
       finished, reset the session" -- and the client polls this speculatively before
       any /play has happened, so a -1.0 default here tears down the session before it
       starts. -1.0 is reserved for _video_stop, once a session has actually ended. */
    ctx->playback_duration = 0.0;
    ctx->playback_rate = 0.0f;
    ctx->playback_ready = 0;

    jclass cls = (*env)->GetObjectClass(env, callback_obj);
    ctx->on_video_data = (*env)->GetMethodID(env, cls, "onVideoData", "([BJZ)V");
    ctx->on_audio_data = (*env)->GetMethodID(env, cls, "onAudioData", "([BIJI)V");
    ctx->on_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(IIZ)V");
    ctx->on_video_size = (*env)->GetMethodID(env, cls, "onVideoSize", "(FFFF)V");
    ctx->on_volume_change = (*env)->GetMethodID(env, cls, "onVolumeChange", "(F)V");
    ctx->on_conn_init = (*env)->GetMethodID(env, cls, "onConnectionInit", "()V");
    ctx->on_conn_destroy = (*env)->GetMethodID(env, cls, "onConnectionDestroy", "()V");
    ctx->on_conn_reset = (*env)->GetMethodID(env, cls, "onConnectionReset", "(I)V");
    ctx->on_display_pin = (*env)->GetMethodID(env, cls, "onDisplayPin", "(Ljava/lang/String;)V");
    ctx->on_metadata = (*env)->GetMethodID(env, cls, "onMetadata", "([B)V");
    ctx->on_coverart = (*env)->GetMethodID(env, cls, "onCoverArt", "([B)V");
    ctx->on_progress = (*env)->GetMethodID(env, cls, "onProgress", "(JJJ)V");
    ctx->on_dacp_id = (*env)->GetMethodID(env, cls, "onDacpId", "(Ljava/lang/String;Ljava/lang/String;)V");
    ctx->on_audio_only = (*env)->GetMethodID(env, cls, "onAudioOnly", "(Z)V");
    ctx->on_video_play = (*env)->GetMethodID(env, cls, "onVideoPlay", "(Ljava/lang/String;F)V");
    ctx->on_video_scrub = (*env)->GetMethodID(env, cls, "onVideoScrub", "(F)V");
    ctx->on_video_rate = (*env)->GetMethodID(env, cls, "onVideoRate", "(F)V");
    ctx->on_video_stop = (*env)->GetMethodID(env, cls, "onVideoStop", "()V");
    ctx->on_video_session_poll = (*env)->GetMethodID(env, cls, "onVideoSessionPoll", "()V");
    (*env)->DeleteLocalRef(env, cls);
}

void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env) {
    if (ctx->callback_obj) {
        (*env)->DeleteGlobalRef(env, ctx->callback_obj);
        ctx->callback_obj = NULL;
    }
    for (int i = 0; i < ctx->registered_count; i++) {
        free(ctx->registered_keys[i]);
        ctx->registered_keys[i] = NULL;
    }
    ctx->registered_count = 0;
    pthread_cond_destroy(&ctx->play_ready_cond);
    pthread_mutex_destroy(&ctx->playback_info_lock);
}

static uint64_t _monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/* Records that the sender just made a request touching the AirPlay Video session
   (see last_video_request_ns in the header). Called from httpd threads. */
static void _touch_video_request(android_callback_ctx_t *ctx) {
    uint64_t now = _monotonic_ns();
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->last_video_request_ns = now;
    ctx->video_request_count++;
    pthread_mutex_unlock(&ctx->playback_info_lock);
}

uint64_t android_callbacks_video_request_count(android_callback_ctx_t *ctx) {
    pthread_mutex_lock(&ctx->playback_info_lock);
    uint64_t count = ctx->video_request_count;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    return count;
}

int64_t android_callbacks_ms_since_video_request(android_callback_ctx_t *ctx) {
    pthread_mutex_lock(&ctx->playback_info_lock);
    uint64_t last = ctx->last_video_request_ns;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    if (last == 0) return -1;
    return (int64_t)((_monotonic_ns() - last) / 1000000ull);
}

void android_callbacks_update_playback_info(android_callback_ctx_t *ctx, double position,
                                             double duration, float rate, int ready) {
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->playback_position = position;
    ctx->playback_duration = duration;
    ctx->playback_rate = rate;
    ctx->playback_ready = ready;
    /* wake a play() hold-until-ready wait (see _video_play) as soon as the outcome
       is known, not just on success: duration == -1.0 is the "stopped/finished"
       sentinel, sent on a real stop, player error, or disconnect. Without this,
       a session that fails before ever reporting ready would block the httpd
       thread for the full timeout below instead of returning immediately. */
    if (!ctx->play_ready && (ready || duration == -1.0)) {
        ctx->play_ready = 1;
        pthread_cond_signal(&ctx->play_ready_cond);
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
}

/* --- RAOP callback implementations --- */

static void _audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !data->data || data->data_len <= 0) return;

    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (jbyte *)data->data);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_data,
                           arr, (jint)data->ct, (jlong)data->ntp_time_local, (jint)data->seqnum);
    (*env)->DeleteLocalRef(env, arr);
}

static void _video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !data->data || data->data_len <= 0) return;

    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (jbyte *)data->data);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_data,
                           arr, (jlong)data->ntp_time_local, (jboolean)data->is_h265);
    (*env)->DeleteLocalRef(env, arr);
}

static void _conn_init(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    /* fires for every connection including the player's own hls fetches: clear only a
       stale "finished" (-1.0) sentinel left by a previous AirPlay Video session, so a
       new connection's speculative /playback-info poll (which can arrive before its
       own /play) doesn't get misread as "already finished". Must not unconditionally
       reset here, or an incidental reconnect mid-session would clobber live state. */
    pthread_mutex_lock(&ctx->playback_info_lock);
    if (ctx->playback_duration == -1.0) {
        ctx->playback_position = 0.0;
        ctx->playback_duration = 0.0;
        ctx->playback_rate = 0.0f;
        ctx->playback_ready = 0;
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_init);
}

static void _conn_destroy(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_destroy);
}

static void _conn_reset(void *cls, int reason) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_reset, (jint)reason);
}

static void _audio_set_volume(void *cls, float volume) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_volume_change, (jfloat)volume);
}

static void _audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                               bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_format,
                           (jint)*ct, (jint)*spf, (jboolean)*usingScreen);
}

static void _video_report_size(void *cls, float *w_src, float *h_src, float *w, float *h) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_size,
                           (jfloat)*w_src, (jfloat)*h_src, (jfloat)*w, (jfloat)*h);
}

static void _display_pin(void *cls, char *pin) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    
    /* certain senders trigger pair-pin-start even when we advertise no auth */
    if (!ctx->require_pin) return;

    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jpin = (*env)->NewStringUTF(env, pin);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_display_pin, jpin);
    (*env)->DeleteLocalRef(env, jpin);
}

/* Stubs for less critical callbacks */
static void _noop(void *cls) { (void)cls; }
static void _noop_teardown(void *cls, bool *a, bool *b) { (void)cls; (void)a; (void)b; }
static void _video_pause(void *cls) { LOGI("video_pause"); }
static void _video_resume(void *cls) { LOGI("video_resume"); }
static void _conn_feedback(void *cls) { (void)cls; }
static void _video_stop(void *cls);
static void _video_reset(void *cls, reset_type_t t) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_reset %d", t);
    /* an AirPlay Video (HLS) session ended without an explicit POST /stop */
    if (t == RESET_TYPE_HLS_SHUTDOWN || t == RESET_TYPE_HLS_EOS) {
        _video_stop(cls);
    }
    if (t == RESET_TYPE_HLS_CONN_CLOSED) {
        /* the connection that carried POST /play died with no /stop and no
           replacement /play (iFit's 'end': /rate 0, then iOS closes the session's
           connections ~60s later while the route's other connections stay open and
           keep polling /playback-info). Only end the session if playback isn't
           actively running: a sender vanishing mid-play (app killed) leaves a
           direct-URL video that can and should keep playing to its natural end.
           Checking ready as well as rate<=0 avoids misreading "still buffering"
           (also rate 0) as abandonment. */
        pthread_mutex_lock(&ctx->playback_info_lock);
        int paused = ctx->playback_ready && ctx->playback_rate <= 0.0f;
        float rate = ctx->playback_rate;
        pthread_mutex_unlock(&ctx->playback_info_lock);
        if (paused) {
            _video_stop(cls);
        } else {
            LOGI("play connection closed while playing/buffering (rate %.2f): leaving video running", rate);
        }
    }
    if (t == RESET_TYPE_HLS_SHUTDOWN && ctx->raop) {
        /* mirrors uxplay.cpp's own handling of this reset type: without this, the
           HLS/reverse-http/airplay sub-connections for that video are left open,
           and our connection counter never returns to the count it had before
           the video started. */
        raop_remove_hls_connections(ctx->raop);
    }
}
static void _audio_flush(void *cls) { LOGI("audio_flush"); }
static void _video_flush(void *cls) { LOGI("video_flush"); }
static double _audio_set_client_volume(void *cls) { return 0.0; }
static void _audio_set_metadata(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_metadata, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_set_coverart(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_coverart, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jdacp = (*env)->NewStringUTF(env, dacp_id ? dacp_id : "");
    jstring jremote = (*env)->NewStringUTF(env, active_remote ? active_remote : "");
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_dacp_id, jdacp, jremote);
    (*env)->DeleteLocalRef(env, jdacp);
    (*env)->DeleteLocalRef(env, jremote);
}

static void _audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !start || !curr || !end) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_progress,
                           (jlong)*start, (jlong)*curr, (jlong)*end);
}

static void _mirror_video_running(void *cls, bool running) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    LOGI("mirror running: %d", running);
    /* audio-only = mirror NOT running */
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_only, (jboolean)!running);
}
static void _register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    (void)device_id; (void)name;
    if (ctx->registered_count >= 16) return;
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return;
    }
    ctx->registered_keys[ctx->registered_count++] = strdup(pk_str);
    LOGI("registered client pk (slot %d)", ctx->registered_count);
}

static bool _check_register(void *cls, const char *pk_str) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return true;
    }
    return false;
}

/* --- AirPlay Video (HLS) playback callbacks --- */

static void _video_play(void *cls, const char *location, const float start_position) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_play: %s @ %.2fs", location ? location : "(null)", start_position);
    _touch_video_request(ctx);
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->play_ready = 0;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    android_callbacks_update_playback_info(ctx, start_position, 0.0, 0.0f, 0);
    JNIEnv *env = _get_env(ctx);
    if (!env || !location) return;
    jstring jloc = (*env)->NewStringUTF(env, location);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_play, jloc, (jfloat)start_position);
    (*env)->DeleteLocalRef(env, jloc);
    /* self-driven senders (macOS) latch their scrubber timeline at /play; hold the response
       until the player reports ready so that read must carry the real duration */
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 10; // hold for max 10s
    pthread_mutex_lock(&ctx->playback_info_lock);
    while (!ctx->play_ready) {
        if (pthread_cond_timedwait(&ctx->play_ready_cond, &ctx->playback_info_lock, &ts) == ETIMEDOUT) break;
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
}

static void _video_scrub(void *cls, const float position) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    _touch_video_request(ctx);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_scrub, (jfloat)position);
}

static void _video_rate(void *cls, const float rate) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    _touch_video_request(ctx);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_rate, (jfloat)rate);
}

static void _video_stop(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    _touch_video_request(ctx);
    android_callbacks_update_playback_info(ctx, 0.0, -1.0, 0.0f, 0);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_stop);
}

/* Called synchronously from the native httpd thread (GET /playback-info). Must not
   block on the app's main thread, so it only reads the snapshot Kotlin last pushed
   via nativeUpdatePlaybackInfo, rather than querying the player directly. */
static void _video_acquire_playback_info(void *cls, playback_info_t *info) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    pthread_mutex_lock(&ctx->playback_info_lock);
    /* the sender's periodic GET /playback-info poll is its liveness heartbeat */
    ctx->last_video_request_ns = _monotonic_ns();
    ctx->video_request_count++;
    info->position = ctx->playback_position;
    info->duration = ctx->playback_duration;
    info->rate = ctx->playback_rate;
    info->ready_to_play = ctx->playback_ready;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    info->playback_likely_to_keep_up = true;
    info->seek_start = 0.0;
    info->seek_duration = ctx->playback_duration > 0.0 ? ctx->playback_duration : 0.0;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    /* polls are the earliest video-channel signal, starting ~1s before /play */
    JNIEnv *env = _get_env(ctx);
    if (env) {
        (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_session_poll);
    }
}

static float _video_playlist_remove(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->last_video_request_ns = _monotonic_ns();
    ctx->video_request_count++;
    double position = ctx->playback_position;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    return (float) position;
}

static int _video_set_codec(void *cls, video_codec_t codec) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_set_codec: %d (h265_enabled=%d)", codec, ctx->h265_enabled);
    if (codec == VIDEO_CODEC_H265 && !ctx->h265_enabled) return -1;
    return 0;
}

void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx) {
    memset(cbs, 0, sizeof(raop_callbacks_t));
    cbs->cls = ctx;

    cbs->audio_process = _audio_process;
    cbs->video_process = _video_process;
    cbs->video_pause = _video_pause;
    cbs->video_resume = _video_resume;
    cbs->conn_feedback = _conn_feedback;
    cbs->conn_reset = _conn_reset;
    cbs->video_reset = _video_reset;
    cbs->conn_init = _conn_init;
    cbs->conn_destroy = _conn_destroy;
    cbs->conn_teardown = _noop_teardown;
    cbs->audio_flush = _audio_flush;
    cbs->video_flush = _video_flush;
    cbs->audio_set_client_volume = _audio_set_client_volume;
    cbs->audio_set_volume = _audio_set_volume;
    cbs->audio_set_metadata = _audio_set_metadata;
    cbs->audio_set_coverart = _audio_set_coverart;
    cbs->audio_remote_control_id = _audio_remote_control_id;
    cbs->audio_set_progress = _audio_set_progress;
    cbs->audio_get_format = _audio_get_format;
    cbs->video_report_size = _video_report_size;
    cbs->mirror_video_running = _mirror_video_running;
    cbs->display_pin = _display_pin;
    cbs->video_set_codec = _video_set_codec;
    cbs->on_video_play = _video_play;
    cbs->on_video_scrub = _video_scrub;
    cbs->on_video_rate = _video_rate;
    cbs->on_video_stop = _video_stop;
    cbs->on_video_acquire_playback_info = _video_acquire_playback_info;
    cbs->on_video_playlist_remove = _video_playlist_remove;
    if (ctx->require_pin) {
        cbs->check_register = _check_register;
        cbs->register_client = _register_client;
    }
}
