#!/usr/bin/env bash
#
# Collects the log of one test session and puts it in one block of text that
# can be pasted into a chat.
#
# Usage:
#   tools/collect-log.sh            the filtered log of the session
#   tools/collect-log.sh --raw      everything logcat has
#   tools/collect-log.sh --keep     the same, but the file is kept
#
# What it does:
#   1. clears the log, so what follows is only this test
#   2. waits for you to press Enter
#   3. collects the receiver's own log, a crash report if there is one, the
#      link state and the settings the receiver is running with
#
# Do the test between step 2 and step 3: start the receiver, connect from the
# Mac, and leave the picture running for a minute.

set -uo pipefail

PKG=io.github.jqssun.airplay
RECV="$PKG/.SettingsReceiver"
OUT="$HOME/airplay-log.txt"
RAW=0
KEEP=1

for arg in "$@"; do
    case "$arg" in
        --raw) RAW=1 ;;
        --keep) KEEP=1 ;;
        --no-keep) KEEP=0 ;;
        *) echo "usage: $0 [--raw] [--no-keep]" >&2; exit 1 ;;
    esac
done

adbs() { adb shell "$@"; }

if ! adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "no device connected (adb devices)" >&2
    exit 1
fi

echo "== the log is cleared =="
echo "now: start the receiver on the tv, connect from the mac, leave the"
echo "picture running for a minute. then press Enter here."
read -r _
echo
echo "== collecting =="

: > "$OUT"

# the summary first: it is what the log is read for, the rest is the detail
{
    echo "### what the receiver advertises"
    adb logcat -d -v brief 2>/dev/null | grep -iE "Advertising display|Advertised features|profile asks for|decoders:|Frame rate clamped|server started|Listening|wifi lock" | tail -14 || true
} >> "$OUT"

{
    echo
    echo "### receiver log"
    if [ "$RAW" = "1" ]; then
        adb logcat -d -v time 2>/dev/null
    else
        adb logcat -d -v time 2>/dev/null | grep -E \
            "AirPlayService|AirPlayNative|WifiTuner|DecoderSelector|VideoRenderer|VideoPipeline|BENCHMARK|NsdServiceManager|UsbMdnsAdvertiser|DacpController|FATAL|AndroidRuntime|$PKG"
    fi
} >> "$OUT"

{
    echo
    echo "### crash report"
    adb logcat -d -v time 2>/dev/null | grep -B 5 -A 40 "FATAL EXCEPTION" | head -120 || true
    echo "(nothing above means no crash)"
} >> "$OUT"

{
    echo
    echo "### the link"
    adbs cmd wifi status 2>/dev/null | tr -d '\r' | head -20 || true
    echo
    adbs cat /proc/net/snmp 2>/dev/null | grep -A 1 "^Udp:" || true
} >> "$OUT"

{
    echo
    echo "### the settings the receiver runs with"
    adbs am broadcast -n "$RECV" -a io.github.jqssun.airplay.LIST_SETTINGS 2>/dev/null | tail -1 || true
} >> "$OUT"

{
    echo
    echo "### the display"
    adbs dumpsys display 2>/dev/null | grep -E "real [0-9]+ x [0-9]+|mBaseDisplayInfo|DisplayDeviceInfo" | head -10 || true
} >> "$OUT"

echo
echo "=== the log ($(wc -l < "$OUT") lines) ==="
echo
cat "$OUT"

if [ "$KEEP" = "1" ]; then
    echo
    echo "--- also written to $OUT ---"
fi
