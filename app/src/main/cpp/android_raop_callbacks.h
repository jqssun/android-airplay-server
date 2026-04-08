#ifndef ANDROID_RAOP_CALLBACKS_H
#define ANDROID_RAOP_CALLBACKS_H

#include <jni.h>
#include "raop.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    JavaVM *jvm;
    jobject callback_obj;
    jmethodID on_video_data;
    jmethodID on_audio_data;
    jmethodID on_audio_format;
    jmethodID on_video_size;
    jmethodID on_volume_change;
    jmethodID on_conn_init;
    jmethodID on_conn_destroy;
    jmethodID on_conn_reset;
    jmethodID on_display_pin;
    int h265_enabled;
    int require_pin;
    char *registered_keys[16];
    int registered_count;
} android_callback_ctx_t;

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj);
void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env);
void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx);

#ifdef __cplusplus
}
#endif

#endif
