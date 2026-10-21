#!/usr/bin/env bash
#
# The best picture with the least delay, as a set of receiver settings.
#
# The receiver's defaults are already close to this, so the script is mostly a
# written down decision: it says which knob is turned which way and why, and it
# puts a device that was experimented on back into this state.
#
# Usage:
#   tools/optimal-config.sh show             what the device is set to now
#   tools/optimal-config.sh apply [profile]  apply the config
#                                            profile: auto | 4k30 | 4k60 |
#                                                     1080p60 | custom
#   tools/optimal-config.sh measure [profile] the config plus the diagnostics
#   tools/optimal-config.sh status           what the receiver advertises
#
# The picture comes first: the frame rate is given up before the resolution,
# because a 4k30 stream that arrives complete looks better than a 4k60 stream
# that has to drop frames to keep up. The "auto" profile takes the panel's
# native resolution and the frame rate the decoder can actually sustain at
# that size, so it is the profile to use unless the link is too weak.

set -euo pipefail

PKG=io.github.jqssun.airplay
RECV="$PKG/.SettingsReceiver"
SET=io.github.jqssun.airplay.SET_SETTING
LIST=io.github.jqssun.airplay.LIST_SETTINGS

adbs() { adb shell "$@"; }

REJECTED=0

require_device() {
    if ! adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
        echo "no device connected (adb devices)" >&2
        exit 1
    fi
}

# the receiver answers with the key it stored, so a setting it did not take
# (a renamed key, a value out of range) shows up here instead of being
# silently dropped
set_pref() {
    local key=$1 value=$2 out
    printf '  %-28s %s\n' "$key" "$value"
    out=$(adbs am broadcast -n "$RECV" -a "$SET" --es key "$key" --es value "$value" 2>&1 | tail -1 || true)
    if ! printf '%s' "$out" | grep -q "result=0" || ! printf '%s' "$out" | grep -qF "$key=$value"; then
        REJECTED=$((REJECTED + 1))
        echo "    rejected: $out" >&2
    fi
}

report() {
    if [ "$REJECTED" -gt 0 ]; then
        echo >&2
        echo "$REJECTED setting(s) were refused by the receiver, see above" >&2
        exit 1
    fi
    echo
    echo "the receiver restarts the server a moment after the last setting"
}

apply_config() {
    local profile=${1:-auto}
    case "$profile" in
        auto | 4k30 | 4k60 | 1080p60 | custom) ;;
        *) echo "unknown profile: $profile (auto | 4k30 | 4k60 | 1080p60 | custom)" >&2; exit 1 ;;
    esac
    require_device

    echo "== picture =="
    # the panel's native resolution, clamped to what the decoder can decode,
    # with the frame rate clamped to what it can sustain at that size
    set_pref stream_profile "$profile"
    set_pref resolution auto
    set_pref max_fps 60
    # hevc: about half the bitrate of h264 at the same quality
    set_pref h265 on
    # the picture keeps its shape and fills the panel
    set_pref fit_aspect true
    set_pref overscanned false
    # the sender sends sdr, so the decoder is told to output bt.709
    set_pref enforce_sdr true
    # a frame that arrives late may be dropped rather than stalling the
    # picture; the decode queue drops the oldest frame for the same reason
    set_pref allow-frame-drop true
    # realtime priority for the codec
    set_pref priority true

    echo "== delay =="
    # the radio must not sleep between beacons, that is jitter the stream
    # cannot get back
    set_pref wifi_low_latency_lock true
    # oboe's low latency output and the decoder's low latency flag; oboe falls
    # back to a normal stream when the tv has no fast path
    set_pref low_latency true
    # the cushion follows the arrival delay instead of a fixed value
    set_pref audio_auto_buffer true
    # the 90th percentile: less delay than the 95th percentile default, still
    # stable. 1 or 0 is faster and clicks more often
    set_pref audio_adaptive_step 2
    # 256 frames is about 5 ms: enough that a busy tv does not run the output
    # dry, small enough that the delay stays below the eye's notice. 0 lets
    # oboe pick, which in low latency mode is two bursts - that clicks on a
    # weak tv
    set_pref oboe_buffer_frames 256
    # no fixed delay, the adaptive buffer covers it
    set_pref audio_latency_ms -1

    echo "== session =="
    # the screen extension: the tv is a separate display, not a mirror
    set_pref separate_display true
    # a new sender may take the panel
    set_pref allow_new_conn true
    set_pref require_pin false
    set_pref keep_screen_on true
    set_pref run_in_background true
    set_pref auto_start true
    # on a tv this is a toast now, not a jump into fullscreen
    set_pref auto_fullscreen true
    # the idle preview would burn cpu for nothing
    set_pref idle_preview false
    # publish on both links, set usb_only for a wired only setup
    set_pref usb_only false

    echo "== logging =="
    # the protocol log is a debug level log and costs throughput
    set_pref verbose_protocol_log false
    set_pref benchmark_log false
    set_pref debug_enabled false
}

apply_measure() {
    local profile=${1:-auto}
    apply_config "$profile"
    echo "== diagnostics =="
    # these make the numbers in docs/WIFI-OPTIMIZATION.md measurable
    set_pref verbose_protocol_log true
    set_pref benchmark_log true
    set_pref debug_enabled true
    # shows the link state and the stream counters in the overlay
    set_pref developer_options true
}

cmd_show() {
    local raw
    raw=$(adbs am broadcast -n "$RECV" -a "$LIST" | tail -1 || true)
    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "$raw" | python3 -c '
import json, re, sys

raw = sys.stdin.read()
# am quotes the payload and escapes the quotes inside it
match = re.search(r"data=\"(.*)\"\s*$", raw, re.S)
payload = match.group(1) if match else raw
payload = payload.replace(chr(92) + chr(34), chr(34)).replace(chr(92) * 2, chr(92))
try:
    data = json.loads(payload)
except ValueError:
    print(raw)
    raise SystemExit
for key in sorted(data):
    value = data[key]["value"]
    default = data[key]["default"]
    mark = "" if value == default else "   (default: %s)" % default
    print("%-28s %s%s" % (key, value, mark))
' || echo "$raw"
    else
        echo "$raw"
    fi
}

cmd_status() {
    echo "== what the receiver advertises =="
    adb logcat -d -v brief 2>/dev/null | grep -iE "Advertising display|decoders:|Frame rate clamped" | tail -6 || true
    echo
    echo "== the link =="
    adbs cmd wifi status 2>/dev/null | tr -d '\r' | grep -iE "Wifi is|RSSI|frequency|LinkSpeed" || true
}

case "${1:-}" in
    show) cmd_show ;;
    apply) apply_config "${2:-auto}"; report ;;
    measure) apply_measure "${2:-auto}"; report ;;
    status) cmd_status ;;
    *)
        echo "usage: $0 {show|apply [profile]|measure [profile]|status}" >&2
        exit 1
        ;;
esac
