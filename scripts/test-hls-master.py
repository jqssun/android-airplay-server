#!/usr/bin/env python3
"""Exercise the actual patched UxPlay HLS code without Android or network access.

Usage: python3 scripts/test-hls-master.py [path/to/third_party/UxPlay]
Requires a C compiler supporting AddressSanitizer and UndefinedBehaviorSanitizer.
The native functions/predicates are extracted from production sources, not copied.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile


def function(text, name):
    start = text.index("char *" + name + " (")
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("uxplay", nargs="?", type=Path,
                        default=Path("app/src/main/cpp/third_party/UxPlay"))
    args = parser.parse_args()
    video = (args.uxplay / "lib/airplay_video.c").read_text()
    handlers = (args.uxplay / "lib/http_handlers.h").read_text()
    adjust = function(video, "adjust_master_playlist")
    direct = re.search(r"bool direct_url =.*?;", handlers, re.S)
    master = re.search(r'size_t master_len = strlen\("/master.m3u8"\);\s*if \((.*?)\) \{',
                       handlers, re.S)
    # Old code has neither guard: retain its actual original conditions so this
    # same harness also demonstrates the regression on the unpatched sources.
    direct_code = direct[0] if direct else "bool direct_url = false;"
    master_code = ("size_t master_len = strlen(\"/master.m3u8\");\nreturn " + master[1] + ";"
                   if master else 'return !strcmp(url, "/master.m3u8");')
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
ADJUST_FUNCTION
static bool relayed(const char *playback_location) {
    const char *uri_suffix = strstr(playback_location, "/master.m3u8");
    DIRECT_PREDICATE
    return uri_suffix && !direct_url;
}
static bool master_path(const char *url) { MASTER_PREDICATE }
int main(void) {
    char relative[] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000\nvariant.m3u8?token=test\n";
    char absolute[] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000\nhttps://example.invalid/hls/variant.m3u8\n";
    char expected[] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000\nhttp://localhost:7000/hls/variant.m3u8\n";
    char empty[] = "";
    char *out = adjust_master_playlist(relative, (int)strlen(relative),
                                       "https://example.invalid/hls", "http://localhost:7000/hls");
    assert(!strcmp(out, relative)); free(out);
    out = adjust_master_playlist(absolute, (int)strlen(absolute),
                                 "https://example.invalid/hls", "http://localhost:7000/hls");
    assert(!strcmp(out, expected)); free(out);
    out = adjust_master_playlist(empty, 0, "https://example.invalid/hls", "http://localhost:7000/hls");
    assert(out[0] == '\0'); free(out);
    assert(master_path("/master.m3u8"));
    assert(master_path("/master.m3u8?"));
    assert(master_path("/master.m3u8?probe=neutral"));
    assert(!master_path("/master.m3u8.extra"));
    assert(!master_path("/master.m3u8/other"));
    assert(!master_path("/master"));
    assert(!relayed("http://example.invalid/direct.m3u8"));
    assert(!relayed("http://example.invalid/master.m3u8?probe=neutral"));
    assert(!relayed("https://example.invalid/master.m3u8?token=test"));
    assert(relayed("mlhls://example.invalid/master.m3u8?token=test"));
    puts("PASS: relative, absolute and empty playlists; master path query/boundaries; direct HTTP(S) and relay schemes.");
    return 0;
}
'''.replace("ADJUST_FUNCTION", adjust).replace("DIRECT_PREDICATE", direct_code).replace("MASTER_PREDICATE", master_code)
    with tempfile.TemporaryDirectory(prefix="airplay-hls-test-") as folder:
        source = Path(folder) / "hls.c"
        binary = Path(folder) / "hls-test"
        source.write_text(harness)
        subprocess.run([os.environ.get("CC", "cc"), "-std=c17", "-g", "-O1",
                        "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                        str(source), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
