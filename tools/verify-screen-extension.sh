#!/usr/bin/env bash
#
# Screen extension test helper.
#
# Installs the debug build on a connected device, switches the receiver into
# the settings the screen extension needs and collects everything that is
# interesting from the log while the sender (a mac) is used.
#
# Usage:
#   tools/verify-screen-extension.sh install     build, install and configure
#   tools/verify-screen-extension.sh log         show the interesting log lines
#   tools/verify-screen-extension.sh watch       follow the interesting log lines
#   tools/verify-screen-extension.sh status       show the device's display mode
#
# The Mac does the rest: Control Center -> Screen Mirroring -> the device ->
# "Use As Separate Display".

set -euo pipefail

PKG=io.github.jqssun.airplay
RECV="$PKG/.SettingsReceiver"
ACTION=io.github.jqssun.airplay.SET_SETTING
APK=app/build/outputs/apk/debug/app-debug.apk

# the lines that tell how the extended desktop is set up
GREP_PATTERNS='Advertising display|Display session|Video size|Video codec started|new video session|SETUP request|displays|decoders:'

adbs() { adb shell "$@"; }

set_pref() {
    local key=$1 value=$2
    printf 'set %s=%s ... ' "$key" "$value"
    adbs am broadcast -n "$RECV" -a "$ACTION" --es key "$key" --es value "$value" >/dev/null
    echo ok
}

require_device() {
    if ! adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
        echo "no device connected (adb devices)" >&2
        exit 1
    fi
}

cmd_install() {
    require_device
    echo "== building =="
    ./gradlew assembleDebug -Pandroid.injected.build.abi=arm64-v8a
    echo "== installing =="
    adb install -r -g "$APK"
    echo "== configuring =="
    # the sender may offer the extended desktop
    set_pref separate_display true
    # the extended desktop keeps its aspect ratio
    set_pref fit_aspect true
    # the panel's native resolution, not the app's window size
    set_pref resolution auto
    # log the protocol, this is where the sender's setup request shows up
    set_pref verbose_protocol_log true
    echo
    echo "start the server on the device, then run: $0 watch"
}

cmd_log() {
    adb logcat -d -v time | grep -E "$GREP_PATTERNS" || true
}

cmd_watch() {
    adb logcat -v time | grep -E --line-buffered "$GREP_PATTERNS" || true
}

cmd_status() {
    echo "== display mode =="
    adbs dumpsys display | grep -E "mBaseDisplayInfo|DisplayDeviceInfo|real [0-9]+ x [0-9]+" | head -20 || true
    echo
    echo "== receiver settings =="
    adbs am broadcast -n "$RECV" -a io.github.jqssun.airplay.LIST_SETTINGS | tail -1 || true
}

case "${1:-}" in
    install) cmd_install ;;
    log) cmd_log ;;
    watch) cmd_watch ;;
    status) cmd_status ;;
    *)
        echo "usage: $0 {install|log|watch|status}" >&2
        exit 1
        ;;
esac
