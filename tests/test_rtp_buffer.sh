#!/bin/sh
# Requires the initialized UxPlay submodule, git and a host C compiler.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
vendor="$root/app/src/main/cpp/third_party/UxPlay"
patch="$root/app/src/main/cpp/patches/UxPlay/0010-preserve-empty-audio-rtp-sequences.patch"
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
trap 'exit 1' HUP INT TERM

for variant in original fixed; do
    mkdir -p "$temp/$variant/lib"
    for file in raop_buffer.c raop_buffer.h raop_rtp.c; do
        git -C "$vendor" show "HEAD:lib/$file" > "$temp/$variant/lib/$file"
    done
    if [ "$variant" = fixed ]; then
        (cd "$temp/$variant" && git apply --unidiff-zero "$patch")
    fi
    "${CC:-cc}" -std=c11 -D_DEFAULT_SOURCE -O1 -g -Wall -Wextra \
        -Wno-unused-parameter -I "$temp/$variant/lib" -I "$vendor/lib" \
        "$root/tests/rtp_buffer_test.c" "$temp/$variant/lib/raop_buffer.c" \
        "$vendor/lib/byteutils.c" -o "$temp/$variant/test"
    "$temp/$variant/test" "$variant"
done
