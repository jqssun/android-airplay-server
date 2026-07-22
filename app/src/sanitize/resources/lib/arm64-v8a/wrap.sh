#!/system/bin/sh
# https://developer.android.com/ndk/guides/hwasan#wrapsh
LD_HWASAN=1 exec "$@"
