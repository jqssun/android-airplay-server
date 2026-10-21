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
#   tools/verify-screen-extension.sh link        show the wifi link and the
#                                                receive counters (the numbers
#                                                that say where the frames go)
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
    # the stream profile bundles resolution, frame rate and codec;
    # use 4k30 for a weak 5 GHz link, 4k60 for a strong one
    set_pref stream_profile auto
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

# the wifi band of a channel number (802.11)
band_of_channel() {
    case "$1" in
        1|2|3|4|5|6|7|8|9|10|11|12|13) echo "2.4 GHz" ;;
        32|36|40|44|48|52|56|60|64|68|96|100|104|108|112|116|120|124|128|132|136|140|144|149|153|157|161|165) echo "5 GHz" ;;
        *) echo "6 GHz" ;;
    esac
}

cmd_link() {
    require_device
    echo "== wifi link =="
    local wifi rssi freq
    wifi=$(adbs cmd wifi status 2>/dev/null | tr -d '\r' || true)
    if [ -n "$wifi" ]; then
        printf '%s\n' "$wifi" | grep -iE "Wifi is|SSID|BSSID|LinkSpeed|TxLinkSpeed|RxLinkSpeed|RSSI|frequency|Validated|networkId" || printf '%s\n' "$wifi"
        freq=$(printf '%s\n' "$wifi" | grep -oiE "Frequency: ?[0-9]+" | grep -oE "[0-9]+" | head -1)
        rssi=$(printf '%s\n' "$wifi" | grep -oiE "RSSI: ?-?[0-9]+" | grep -oE -- "-?[0-9]+" | head -1)
        if [ -n "$freq" ]; then
            echo "band: $(band_of_channel "$freq") (channel $freq)"
        fi
        if [ -n "$rssi" ]; then
            if [ "$rssi" -ge -50 ]; then
                echo "rssi: $rssi dBm (excellent)"
            elif [ "$rssi" -ge -60 ]; then
                echo "rssi: $rssi dBm (good)"
            elif [ "$rssi" -ge -70 ]; then
                echo "rssi: $rssi dBm (weak - 4K60 may not hold)"
            else
                echo "rssi: $rssi dBm (very weak - use 4K30 or 1080p60)"
            fi
        fi
    else
        echo "(cmd wifi status unavailable)"
    fi
    echo
    echo "== kernel receive counters =="
    adbs cat /proc/net/snmp 2>/dev/null | grep -A1 "^Udp:" || true
    echo
    echo "== sockets of the receiver (drops are in the last column) =="
    adbs cat /proc/net/udp 2>/dev/null | head -20 || true
    echo
    echo "== stream statistics (from the app log) =="
    adb logcat -d -v brief 2>/dev/null | grep -iE "benchmark|fps=|bitrate|jitter|dropped|Video size|socket rcvbuf|rcvbuf" | tail -25 || true
}

case "${1:-}" in
    install) cmd_install ;;
    log) cmd_log ;;
    watch) cmd_watch ;;
    status) cmd_status ;;
    link) cmd_link ;;
    *)
        echo "usage: $0 {install|log|watch|status|link}" >&2
        exit 1
        ;;
esac
