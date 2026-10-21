#!/usr/bin/env python3
"""Generate public synthetic HEVC access units for the real-decoder startup test.

Requires FFmpeg with libx265. No phone footage or network access is used.
Run from any directory, then build assembleDebugAndroidTest.
"""
from pathlib import Path
import re
import struct
import subprocess
import tempfile


def main():
    with tempfile.TemporaryDirectory(prefix="airplay-warmup-") as directory:
        video = Path(directory) / "fixture.hevc"
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
            "-i", "testsrc2=size=3840x1768:rate=30", "-frames:v", "30",
            "-c:v", "libx265", "-preset", "ultrafast", "-x265-params",
            "keyint=60:min-keyint=60:scenecut=0:bframes=0:repeat-headers=1:aud=1:pools=4:frame-threads=2:log-level=error",
            "-pix_fmt", "yuv420p", "-y", str(video),
        ], check=True)
        data = video.read_bytes()
    starts = list(re.finditer(b"\x00\x00(?:\x00)?\x01", data))
    frames = []
    current = bytearray()
    for index, start in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(data)
        nal = data[start.end():end]
        if (nal[0] >> 1) & 63 == 35 and current:
            frames.append(bytes(current))
            current.clear()
        current.extend(b"\x00\x00\x00\x01" + nal)
    if current:
        frames.append(bytes(current))
    if len(frames) != 30:
        raise ValueError(f"expected 30 access units, found {len(frames)}")
    target = Path(__file__).resolve().parent.parent / "app/src/androidTest/assets/warmup-fixture.bin"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"".join(struct.pack(">I", len(frame)) + frame for frame in frames))
    print(f"Generated {len(frames)} synthetic HEVC access units ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
