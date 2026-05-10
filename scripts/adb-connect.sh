#!/usr/bin/env bash
#
# adb-connect.sh — Connect to Android TV via WiFi ADB
#
# Usage:
#   ./scripts/adb-connect.sh <TV_IP_ADDRESS>
#   ./scripts/adb-connect.sh 192.168.1.100
#
# The TV must have developer mode and wireless debugging enabled.

set -euo pipefail

IP="${1:-}"
PORT="${2:-5555}"

if [[ -z "$IP" ]]; then
    echo "Usage: $0 <TV_IP_ADDRESS> [port]"
    echo ""
    echo "To find your TV's IP:"
    echo "  Settings → Network & Internet → (your network) → IP address"
    echo ""
    echo "To enable wireless debugging on the TV:"
    echo "  1. Settings → Device Preferences → About → Build number (tap 7 times)"
    echo "  2. Settings → Device Preferences → Developer options → USB debugging ON"
    echo "  3. If Android 11+: Developer options → Wireless debugging ON"
    exit 1
fi

echo "Connecting to $IP:$PORT..."
adb connect "$IP:$PORT"

echo ""
echo "Connected devices:"
adb devices -l
