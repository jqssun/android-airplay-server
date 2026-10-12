#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
probe_dir=${AIRPLAY_PROBE_DIR:-/opt/codex-airplay-probes}
mkdir -p "$probe_dir"
cd "$project_dir"

case "${1:-host}" in
  host)
    g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I app/src/main/cpp \
      tests/codec_packet_input_test.cpp -o "$probe_dir/codec-input"
    "$probe_dir/codec-input"
    g++ -std=c++17 -pthread -O1 -g -fsanitize=address,undefined -I app/src/main/cpp \
      tests/timeline_clock_test.cpp -o "$probe_dir/timeline-mvp3"
    "$probe_dir/timeline-mvp3"
    gcc -std=c17 -D_DEFAULT_SOURCE -O1 -g -fsanitize=address,undefined \
      -ffunction-sections -fdata-sections -Wl,--gc-sections tests/raop_silence_test.c \
      app/src/main/cpp/third_party/UxPlay/lib/byteutils.c -o "$probe_dir/raop-silence-mvp3"
    "$probe_dir/raop-silence-mvp3"
    ;;
  arm)
    ndk_bin=${ANDROID_HOME:-/opt/codex-airplay-sdk}/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
    "$ndk_bin/armv7a-linux-androideabi24-clang++" -std=c++17 -O1 -static-libstdc++ \
      -I app/src/main/cpp tests/codec_packet_input_test.cpp -o "$probe_dir/codec-input-armv7"
    cp "$probe_dir/codec-input-armv7" tests/codec_packet_input_test-armv7
    "$ndk_bin/armv7a-linux-androideabi24-clang++" -std=c++17 -O1 -pthread -static-libstdc++ \
      -I app/src/main/cpp tests/timeline_clock_test.cpp -o "$probe_dir/timeline-mvp3-armv7"
    "$ndk_bin/armv7a-linux-androideabi24-clang" -std=c17 -O1 -D_DEFAULT_SOURCE \
      -ffunction-sections -fdata-sections -Wl,--gc-sections tests/raop_silence_test.c \
      app/src/main/cpp/third_party/UxPlay/lib/byteutils.c -o "$probe_dir/raop-silence-mvp3-armv7"
    cp "$probe_dir/timeline-mvp3-armv7" tests/timeline_clock_test-armv7
    cp "$probe_dir/raop-silence-mvp3-armv7" tests/raop_silence_test-armv7
    ;;
  *) printf 'Usage: bash tools/test-mvp.sh [host|arm]\n' >&2; exit 2 ;;
esac
