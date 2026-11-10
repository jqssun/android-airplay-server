# Keep JNI callback interface (called from native code)
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }

# Keep NativeBridge native methods
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }

# Media3/ExoPlayer: on older APIs (<31) classes like android.media.metrics.LogSessionId
# do not exist. R8's aggressive optimization can merge/inline these refs into code paths
# that load on older devices, causing NoClassDefFoundError at runtime. Keep Media3 class
# structure intact and suppress warnings about missing API 31+ system classes.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn android.media.metrics.**
-keep class android.media.metrics.** { *; }
