#ifndef ANDROID_RAOP_CALLBACKS_H
#define ANDROID_RAOP_CALLBACKS_H

#include <jni.h>
#include <pthread.h>
#include "raop.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    JavaVM *jvm;
    jobject callback_obj;
    raop_t *raop; /* set once by native_bridge.cpp after raop_init(); used to force-close
                     lingering AirPlay Video sub-connections on RESET_TYPE_HLS_SHUTDOWN */
    jmethodID on_video_data;
    jmethodID on_audio_data;
    jmethodID on_audio_format;
    jmethodID on_video_size;
    jmethodID on_volume_change;
    jmethodID on_conn_init;
    jmethodID on_conn_destroy;
    jmethodID on_conn_reset;
    jmethodID on_display_pin;
    jmethodID on_metadata;
    jmethodID on_coverart;
    jmethodID on_progress;
    jmethodID on_dacp_id;
    jmethodID on_audio_only;
    jmethodID on_video_play;
    jmethodID on_video_scrub;
    jmethodID on_video_rate;
    jmethodID on_video_stop;
    int h265_enabled;
    int require_pin;
    char *registered_keys[16];
    int registered_count;
    /* AirPlay Video (HLS) playback info snapshot, pushed by Kotlin, read by
       on_video_acquire_playback_info on the native httpd thread. Kept as a
       cheap async snapshot instead of a synchronous JNI round-trip into the
       player, to avoid blocking/deadlocking the httpd thread on the main thread. */
    pthread_mutex_t playback_info_lock;
    double playback_position;
    double playback_duration;
    float playback_rate;
    int playback_ready;
} android_callback_ctx_t;

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj);
void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env);
void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx);
void android_callbacks_update_playback_info(android_callback_ctx_t *ctx, double position,
                                             double duration, float rate, int ready);

#ifdef __cplusplus
}
#endif

#endif
