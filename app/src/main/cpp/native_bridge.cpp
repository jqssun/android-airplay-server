/*
 * JNI bridge between Kotlin NativeBridge and the C RAOP library.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "android_raop_callbacks.h"
#include "android_dnssd_shim.h"
}

#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Holds all native state for one server instance */
typedef struct {
    raop_t *raop;
    dnssd_t *dnssd;
    android_callback_ctx_t cb_ctx;
    raop_callbacks_t callbacks;
    char hw_addr[6];
} server_ctx_t;

static void _log_callback(void *cls, int level, const char *msg) {
    int prio = ANDROID_LOG_DEBUG;
    if (level >= 5) prio = ANDROID_LOG_ERROR;
    else if (level >= 4) prio = ANDROID_LOG_WARN;
    else if (level >= 3) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, TAG, "%s", msg);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeInit(
        JNIEnv *env, jobject thiz,
        jobject callback, jbyteArray hwAddr, jstring name, jstring keyFile,
        jboolean nohold, jboolean requirePin) {

    server_ctx_t *ctx = (server_ctx_t *)calloc(1, sizeof(server_ctx_t));
    if (!ctx) return 0;

    /* Copy hw address */
    jsize hw_len = env->GetArrayLength(hwAddr);
    if (hw_len > 6) hw_len = 6;
    env->GetByteArrayRegion(hwAddr, 0, hw_len, (jbyte *)ctx->hw_addr);

    /* Init JNI callbacks */
    android_callbacks_init(&ctx->cb_ctx, env, callback);
    ctx->cb_ctx.require_pin = requirePin ? 1 : 0;
    android_callbacks_fill(&ctx->callbacks, &ctx->cb_ctx);

    /* Init RAOP */
    ctx->raop = raop_init(&ctx->callbacks);
    if (!ctx->raop) {
        LOGE("raop_init failed");
        android_callbacks_destroy(&ctx->cb_ctx, env);
        free(ctx);
        return 0;
    }

    raop_set_log_level(ctx->raop, 3); /* INFO */
    raop_set_log_callback(ctx->raop, _log_callback, NULL);

    /* Init2 with device_id and keyfile */
    const char *keyfile_c = env->GetStringUTFChars(keyFile, NULL);
    const char *name_c = env->GetStringUTFChars(name, NULL);

    /* Build device_id from hw_addr */
    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02X:%02X:%02X:%02X:%02X:%02X",
             (unsigned char)ctx->hw_addr[0], (unsigned char)ctx->hw_addr[1],
             (unsigned char)ctx->hw_addr[2], (unsigned char)ctx->hw_addr[3],
             (unsigned char)ctx->hw_addr[4], (unsigned char)ctx->hw_addr[5]);

    int ret = raop_init2(ctx->raop, nohold ? 1 : 0, device_id, keyfile_c);
    if (ret < 0) {
        LOGE("raop_init2 failed: %d", ret);
    }

    if (requirePin) {
        raop_set_plist(ctx->raop, "pin", 0);
    }

    /* Init dnssd shim */
    int dns_err = 0;
    unsigned char pin_pw = requirePin ? 1 : 0;
    ctx->dnssd = dnssd_init(name_c, (int)strlen(name_c), ctx->hw_addr, 6, &dns_err, pin_pw);
    if (!ctx->dnssd) {
        LOGE("dnssd_init failed: %d", dns_err);
    } else {
        raop_set_dnssd(ctx->raop, ctx->dnssd);
    }

    env->ReleaseStringUTFChars(keyFile, keyfile_c);
    env->ReleaseStringUTFChars(name, name_c);

    return (jlong)(intptr_t)ctx;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeStart(
        JNIEnv *env, jobject thiz, jlong handle) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop) return -1;

    unsigned short port = 7000;
    int ret = raop_start_httpd(ctx->raop, &port);
    if (ret < 0) {
        LOGE("raop_start_httpd failed: %d", ret);
        return -1;
    }

    LOGI("AirPlay server started on port %d", port);

    /* Register dnssd records (stored in shim, Kotlin reads them) */
    if (ctx->dnssd) {
        dnssd_register_raop(ctx->dnssd, port);
        dnssd_register_airplay(ctx->dnssd, port);
    }

    return (jint)port;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeStop(
        JNIEnv *env, jobject thiz, jlong handle) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop) return;

    raop_stop_httpd(ctx->raop);
    LOGI("AirPlay server stopped");

    if (ctx->dnssd) {
        dnssd_unregister_raop(ctx->dnssd);
        dnssd_unregister_airplay(ctx->dnssd);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx) return;

    if (ctx->raop) {
        raop_destroy(ctx->raop);
        ctx->raop = NULL;
    }
    if (ctx->dnssd) {
        dnssd_destroy(ctx->dnssd);
        ctx->dnssd = NULL;
    }
    android_callbacks_destroy(&ctx->cb_ctx, env);
    free(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeSetDisplaySize(
        JNIEnv *env, jobject thiz, jlong handle, jint w, jint h, jint fps) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop) return;

    raop_set_plist(ctx->raop, "width", w);
    raop_set_plist(ctx->raop, "height", h);
    raop_set_plist(ctx->raop, "refreshRate", fps);
}

/* Returns a HashMap<String, String> of TXT records */
static jobject _build_txt_map(JNIEnv *env, dnssd_t *dnssd, int is_raop) {
    jclass mapClass = env->FindClass("java/util/HashMap");
    jmethodID mapInit = env->GetMethodID(mapClass, "<init>", "()V");
    jmethodID mapPut = env->GetMethodID(mapClass, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject map = env->NewObject(mapClass, mapInit);

    int count = is_raop ? android_dnssd_get_raop_txt_count(dnssd)
                        : android_dnssd_get_airplay_txt_count(dnssd);

    for (int i = 0; i < count; i++) {
        const char *key = is_raop ? android_dnssd_get_raop_txt_key(dnssd, i)
                                  : android_dnssd_get_airplay_txt_key(dnssd, i);
        const char *val = is_raop ? android_dnssd_get_raop_txt_val(dnssd, i)
                                  : android_dnssd_get_airplay_txt_val(dnssd, i);
        if (key && val) {
            jstring jkey = env->NewStringUTF(key);
            jstring jval = env->NewStringUTF(val);
            env->CallObjectMethod(map, mapPut, jkey, jval);
            env->DeleteLocalRef(jkey);
            env->DeleteLocalRef(jval);
        }
    }

    env->DeleteLocalRef(mapClass);
    return map;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetRaopTxtRecords(
        JNIEnv *env, jobject thiz, jlong handle) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->dnssd) return NULL;
    return _build_txt_map(env, ctx->dnssd, 1);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetAirplayTxtRecords(
        JNIEnv *env, jobject thiz, jlong handle) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->dnssd) return NULL;
    return _build_txt_map(env, ctx->dnssd, 0);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetRaopServiceName(
        JNIEnv *env, jobject thiz, jlong handle) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->dnssd) return NULL;
    return env->NewStringUTF(android_dnssd_get_raop_servname(ctx->dnssd));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeGetServerName(
        JNIEnv *env, jobject thiz, jlong handle) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->dnssd) return NULL;
    int len = 0;
    const char *name = dnssd_get_name(ctx->dnssd, &len);
    return env->NewStringUTF(name);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeSetPlist(
        JNIEnv *env, jobject thiz, jlong handle, jstring key, jint value) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop) return;
    const char *key_c = env->GetStringUTFChars(key, NULL);
    raop_set_plist(ctx->raop, key_c, value);
    env->ReleaseStringUTFChars(key, key_c);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeSetH265Enabled(
        JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx) return;
    ctx->cb_ctx.h265_enabled = enabled ? 1 : 0;
    /* Set DNS-SD feature bit 42 (SupportsScreenMultiCodec) for H265 */
    if (ctx->dnssd) {
        dnssd_set_airplay_features(ctx->dnssd, 42, enabled ? 1 : 0);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeSetCodecs(
        JNIEnv *env, jobject thiz, jlong handle, jboolean alac, jboolean aac) {

    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->dnssd) return;
    android_dnssd_set_codecs(ctx->dnssd, alac ? 1 : 0, aac ? 1 : 0);
}

/* ---------- Software ALAC decoder (Apple reference, Apache 2.0) ---------- */

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeAlacInit(
        JNIEnv *env, jobject thiz,
        jint frameLength, jint numChannels, jint bitDepth,
        jint pb, jint mb, jint kb) {

    ALACDecoder *dec = new ALACDecoder();
    if (!dec) return 0;

    /* Build the 24-byte ALACSpecificConfig (big-endian) */
    uint8_t cookie[24];
    cookie[0] = (frameLength >> 24) & 0xFF;
    cookie[1] = (frameLength >> 16) & 0xFF;
    cookie[2] = (frameLength >> 8) & 0xFF;
    cookie[3] = frameLength & 0xFF;
    cookie[4] = 0;                /* compatibleVersion */
    cookie[5] = (uint8_t)bitDepth;
    cookie[6] = (uint8_t)pb;
    cookie[7] = (uint8_t)mb;
    cookie[8] = (uint8_t)kb;
    cookie[9] = (uint8_t)numChannels;
    cookie[10] = 0; cookie[11] = 0xFF; /* maxRun = 255 (big-endian) */
    cookie[12] = cookie[13] = cookie[14] = cookie[15] = 0; /* maxFrameBytes */
    cookie[16] = cookie[17] = cookie[18] = cookie[19] = 0; /* avgBitRate */
    cookie[20] = 0; cookie[21] = 0;                        /* sampleRate 44100 */
    cookie[22] = 0xAC; cookie[23] = 0x44;                  /* (big-endian) */

    int32_t status = dec->Init(cookie, sizeof(cookie));
    if (status != 0) {
        LOGE("ALACDecoder::Init failed: %d", status);
        delete dec;
        return 0;
    }
    LOGI("ALACDecoder initialized: %dx%d @%d-bit", frameLength, numChannels, bitDepth);
    return (jlong)(intptr_t)dec;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeAlacDecode(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray input) {

    ALACDecoder *dec = (ALACDecoder *)(intptr_t)handle;
    if (!dec || !input) return NULL;

    int input_len = env->GetArrayLength(input);
    jbyte *input_data = env->GetByteArrayElements(input, NULL);

    /* Set up BitBuffer for the Apple decoder */
    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *)input_data, input_len);

    uint32_t numFrames = dec->mConfig.frameLength;
    uint32_t numChannels = dec->mConfig.numChannels;
    uint32_t outBytes = numFrames * numChannels * (dec->mConfig.bitDepth / 8);
    uint8_t *pcm = (uint8_t *)calloc(outBytes, 1);
    if (!pcm) {
        env->ReleaseByteArrayElements(input, input_data, JNI_ABORT);
        return NULL;
    }

    uint32_t outSamples = 0;
    int32_t status = dec->Decode(&bits, pcm, numFrames, numChannels, &outSamples);
    env->ReleaseByteArrayElements(input, input_data, JNI_ABORT);

    if (status != 0 || outSamples == 0) {
        free(pcm);
        return NULL;
    }

    int pcm_bytes = outSamples * numChannels * (dec->mConfig.bitDepth / 8);
    jbyteArray result = env->NewByteArray(pcm_bytes);
    env->SetByteArrayRegion(result, 0, pcm_bytes, (jbyte *)pcm);
    free(pcm);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_jqssun_airplay_bridge_NativeBridge_nativeAlacDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {

    ALACDecoder *dec = (ALACDecoder *)(intptr_t)handle;
    delete dec;
}
