#!/usr/bin/env bash
#
# benchmark.sh — AirPlay Streaming Benchmark for Physical Android TV
#
# Collects frame pacing, jitter, FPS, bitrate, and dropped frame metrics
# from a physical Android TV device running the AirPlay server app.
#
# Prerequisites:
#   - adb installed and device connected (USB or WiFi)
#   - AirPlay server app installed on the device
#   - Active AirPlay streaming session from Mac
#
# Usage:
#   ./scripts/benchmark.sh [duration_seconds] [output_dir]
#
# Examples:
#   ./scripts/benchmark.sh              # 30-second benchmark, results in ./benchmark-results/
#   ./scripts/benchmark.sh 60           # 60-second benchmark
#   ./scripts/benchmark.sh 30 ./results # custom output directory

set -euo pipefail

DURATION="${1:-30}"
OUTPUT_DIR="${2:-./benchmark-results}"
PACKAGE="io.github.jqssun.airplay"
BENCH_TAG="BENCHMARK"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
RESULT_DIR="$OUTPUT_DIR/$TIMESTAMP"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

header() { echo -e "\n${BOLD}${CYAN}▸ $1${NC}"; }
info()   { echo -e "  ${GREEN}✓${NC} $1"; }
warn()   { echo -e "  ${YELLOW}⚠${NC} $1"; }
fail()   { echo -e "  ${RED}✗${NC} $1"; exit 1; }

# ─── Preflight ───────────────────────────────────────────────────────

header "Preflight Checks"

# Check adb
command -v adb &>/dev/null || fail "adb not found. Install via: brew install android-platform-tools"

# Check device
DEVICE_COUNT=$(adb devices | grep -c -E '\t(device|unauthorized)')
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    fail "No Android device connected. Connect via USB or: adb connect <IP>:5555"
fi
DEVICE_INFO=$(adb devices -l | grep -E '\t(device)' | head -1)
info "Device: $DEVICE_INFO"

# Check app is installed
adb shell pm list packages | grep -q "$PACKAGE" || fail "App not installed: $PACKAGE"
info "App found: $PACKAGE"

# Check app is running
APP_PID=$(adb shell pidof "$PACKAGE" 2>/dev/null || echo "")
if [[ -z "$APP_PID" ]]; then
    warn "App not running. Launching..."
    adb shell am start -n "$PACKAGE/.MainActivity" 2>/dev/null
    sleep 3
    APP_PID=$(adb shell pidof "$PACKAGE" 2>/dev/null || echo "")
    [[ -n "$APP_PID" ]] || fail "Could not start app"
fi
info "App running (PID: $APP_PID)"

# Get device details
DEVICE_MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
ANDROID_VERSION=$(adb shell getprop ro.build.version.release | tr -d '\r')
DEVICE_SOC=$(adb shell getprop ro.hardware | tr -d '\r')
info "Model: $DEVICE_MODEL (Android $ANDROID_VERSION, SoC: $DEVICE_SOC)"

# ─── Create output directory ─────────────────────────────────────────

mkdir -p "$RESULT_DIR"
LOGCAT_FILE="$RESULT_DIR/logcat_raw.txt"
BENCH_FILE="$RESULT_DIR/benchmark_samples.csv"
SF_FILE="$RESULT_DIR/surfaceflinger.txt"
SUMMARY_FILE="$RESULT_DIR/summary.json"

# ─── Collect Benchmark Data ──────────────────────────────────────────

header "Starting ${DURATION}s Benchmark"
echo -e "  Recording to: ${CYAN}$RESULT_DIR${NC}"

# Clear old logcat
adb logcat -c

# Enable SurfaceFlinger timestats
adb shell dumpsys SurfaceFlinger --timestats -clear -enable 2>/dev/null || true

# Start logcat collection in background
adb logcat -v time "$BENCH_TAG:I" "*:S" > "$LOGCAT_FILE" &
LOGCAT_PID=$!

# Progress bar
echo -ne "  Collecting: ["
for ((i=1; i<=DURATION; i++)); do
    sleep 1
    PCT=$((i * 40 / DURATION))
    printf "\r  Collecting: [%-40s] %d/%ds" "$(printf '#%.0s' $(seq 1 $PCT))" "$i" "$DURATION"
done
echo -e "] ${GREEN}Done${NC}"

# Stop logcat collection
kill $LOGCAT_PID 2>/dev/null || true
wait $LOGCAT_PID 2>/dev/null || true

# Collect SurfaceFlinger data
header "Collecting SurfaceFlinger Data"
adb shell dumpsys SurfaceFlinger --timestats -dump > "$SF_FILE" 2>/dev/null || warn "SurfaceFlinger timestats not available"
adb shell dumpsys SurfaceFlinger --timestats -disable 2>/dev/null || true

# Also get frame latency for the app window
SF_WINDOW=$(adb shell dumpsys SurfaceFlinger --list 2>/dev/null | grep -i "$PACKAGE" | head -1 | tr -d '\r')
if [[ -n "$SF_WINDOW" ]]; then
    adb shell dumpsys SurfaceFlinger --latency "$SF_WINDOW" > "$RESULT_DIR/frame_latency.txt" 2>/dev/null || true
    info "Frame latency collected for: $SF_WINDOW"
else
    warn "Could not find SurfaceFlinger window for $PACKAGE"
fi

# ─── Parse Results ───────────────────────────────────────────────────

header "Parsing Results"

# Write CSV header
echo "timestamp,fps,bitrate_kbps,jitter_us,frames,dropped,codec,resolution" > "$BENCH_FILE"

# Parse BENCHMARK lines from logcat
SAMPLE_COUNT=0
TOTAL_FPS=0
TOTAL_JITTER=0
MAX_JITTER=0
TOTAL_BITRATE=0
LAST_DROPPED=0
LAST_CODEC=""
LAST_RES=""

while IFS= read -r line; do
    # Extract fields from: fps=31 bitrate=12500kbps jitter=1200us frames=930 dropped=0 codec=H.265 res=1920x1080
    fps=$(echo "$line" | grep -oP 'fps=\K[0-9]+' || echo "")
    bitrate=$(echo "$line" | grep -oP 'bitrate=\K[0-9]+' || echo "")
    jitter=$(echo "$line" | grep -oP 'jitter=\K[0-9]+' || echo "")
    frames=$(echo "$line" | grep -oP 'frames=\K[0-9]+' || echo "")
    dropped=$(echo "$line" | grep -oP 'dropped=\K[0-9]+' || echo "")
    codec=$(echo "$line" | grep -oP 'codec=\K[^\s]+' || echo "")
    res=$(echo "$line" | grep -oP 'res=\K[^\s]+' || echo "")
    ts=$(echo "$line" | head -c 18 || echo "")

    if [[ -n "$fps" && -n "$jitter" ]]; then
        echo "$ts,$fps,$bitrate,$jitter,$frames,$dropped,$codec,$res" >> "$BENCH_FILE"
        TOTAL_FPS=$((TOTAL_FPS + fps))
        TOTAL_JITTER=$((TOTAL_JITTER + jitter))
        TOTAL_BITRATE=$((TOTAL_BITRATE + bitrate))
        if [[ "$jitter" -gt "$MAX_JITTER" ]]; then MAX_JITTER=$jitter; fi
        LAST_DROPPED="${dropped:-0}"
        LAST_CODEC="${codec:-unknown}"
        LAST_RES="${res:-unknown}"
        SAMPLE_COUNT=$((SAMPLE_COUNT + 1))
    fi
done < "$LOGCAT_FILE"

if [[ "$SAMPLE_COUNT" -eq 0 ]]; then
    warn "No BENCHMARK samples collected! Is the app streaming?"
    warn "Make sure AirPlay is actively mirroring to the device."

    # Create empty summary
    cat > "$SUMMARY_FILE" <<EOF
{
  "status": "no_data",
  "error": "No BENCHMARK logcat samples. Ensure AirPlay is streaming.",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "commit": "$COMMIT",
  "device": "$DEVICE_MODEL",
  "android": "$ANDROID_VERSION",
  "duration_s": $DURATION
}
EOF
    echo -e "\n  Results saved to: ${CYAN}$RESULT_DIR${NC}"
    exit 1
fi

# Compute averages
AVG_FPS=$((TOTAL_FPS / SAMPLE_COUNT))
AVG_JITTER=$((TOTAL_JITTER / SAMPLE_COUNT))
AVG_BITRATE=$((TOTAL_BITRATE / SAMPLE_COUNT))
AVG_BITRATE_MBPS=$(echo "scale=1; $AVG_BITRATE / 1000" | bc)

# Compute FPS stability (min/max from CSV)
FPS_MIN=$(tail -n +2 "$BENCH_FILE" | cut -d',' -f2 | sort -n | head -1)
FPS_MAX=$(tail -n +2 "$BENCH_FILE" | cut -d',' -f2 | sort -n | tail -1)

# ─── Generate Summary ────────────────────────────────────────────────

cat > "$SUMMARY_FILE" <<EOF
{
  "status": "ok",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "commit": "$COMMIT",
  "device": "$DEVICE_MODEL",
  "android_version": "$ANDROID_VERSION",
  "soc": "$DEVICE_SOC",
  "duration_s": $DURATION,
  "samples": $SAMPLE_COUNT,
  "codec": "$LAST_CODEC",
  "resolution": "$LAST_RES",
  "avg_fps": $AVG_FPS,
  "fps_min": ${FPS_MIN:-0},
  "fps_max": ${FPS_MAX:-0},
  "avg_jitter_us": $AVG_JITTER,
  "max_jitter_us": $MAX_JITTER,
  "dropped_frames": $LAST_DROPPED,
  "avg_bitrate_mbps": $AVG_BITRATE_MBPS
}
EOF

# ─── Print Report ────────────────────────────────────────────────────

header "Benchmark Results"
echo ""
echo -e "  ${BOLD}Device:${NC}     $DEVICE_MODEL (Android $ANDROID_VERSION)"
echo -e "  ${BOLD}Commit:${NC}     $COMMIT"
echo -e "  ${BOLD}Duration:${NC}   ${DURATION}s ($SAMPLE_COUNT samples)"
echo -e "  ${BOLD}Codec:${NC}      $LAST_CODEC"
echo -e "  ${BOLD}Resolution:${NC} $LAST_RES"
echo ""
echo -e "  ┌─────────────────────────────────────────┐"
echo -e "  │  ${BOLD}Metric${NC}              │  ${BOLD}Value${NC}            │"
echo -e "  ├─────────────────────────────────────────┤"

# FPS with color coding
if [[ "$AVG_FPS" -ge 28 ]]; then FPS_COLOR=$GREEN
elif [[ "$AVG_FPS" -ge 20 ]]; then FPS_COLOR=$YELLOW
else FPS_COLOR=$RED; fi
printf "  │  Avg FPS              │  ${FPS_COLOR}%-17s${NC} │\n" "$AVG_FPS (${FPS_MIN}-${FPS_MAX})"

# Jitter with color coding
if [[ "$AVG_JITTER" -le 2000 ]]; then JITTER_COLOR=$GREEN
elif [[ "$AVG_JITTER" -le 5000 ]]; then JITTER_COLOR=$YELLOW
else JITTER_COLOR=$RED; fi
JITTER_MS=$(echo "scale=1; $AVG_JITTER / 1000" | bc)
MAX_JITTER_MS=$(echo "scale=1; $MAX_JITTER / 1000" | bc)
printf "  │  Avg Jitter            │  ${JITTER_COLOR}%-17s${NC} │\n" "${JITTER_MS}ms (max ${MAX_JITTER_MS}ms)"

# Dropped frames
if [[ "$LAST_DROPPED" -eq 0 ]]; then DROP_COLOR=$GREEN
else DROP_COLOR=$RED; fi
printf "  │  Dropped Frames        │  ${DROP_COLOR}%-17s${NC} │\n" "$LAST_DROPPED"

# Bitrate
printf "  │  Avg Bitrate           │  %-17s │\n" "${AVG_BITRATE_MBPS} Mbps"

echo -e "  └─────────────────────────────────────────┘"
echo ""
echo -e "  Full data: ${CYAN}$BENCH_FILE${NC}"
echo -e "  Summary:   ${CYAN}$SUMMARY_FILE${NC}"
echo ""
