#!/usr/bin/env python3
"""Check a real SurfaceFlinger dump for downscaling before panel composition.

Capture while mirroring a known-size stream, then run:
  python3 tools/check_render_path.py dump.txt --decoded-size 994x2160

This is a device integration check, not a claim that changing the stream's
advertised resolution changed the physical output. It also checks composition:
a full-size buffer still loses detail if composited into a smaller client target.
"""
import argparse
from pathlib import Path
import re
import sys


def check(text, decoded_size, package):
    width, height = decoded_size
    buffers = []
    for block in re.split(r"(?m)^\+ ", text):
        title = block.splitlines()[0] if block else ""
        prefix = f"BufferLayer (SurfaceView - {package}/"
        if not title.startswith(prefix):
            continue
        found = re.search(r"activeBuffer=\[\s*(\d+)x\s*(\d+):", block)
        visible = re.search(
            r"Region VisibleRegion[^\n]*\n\s*\[\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]",
            block,
        )
        if found and visible:
            frame = tuple(map(int, visible.groups()))
            if frame[2] > frame[0] and frame[3] > frame[1]:
                buffers.append((title[len("BufferLayer ("):-1], tuple(map(int, found.groups())), frame))
    if len(buffers) != 1:
        raise ValueError("expected exactly one visible mirrored-video SurfaceView buffer")
    name, actual, frame = buffers[0]
    print(f"Decoded stream: {width}x{height}; output buffer: {actual[0]}x{actual[1]}")
    if actual[0] < width or actual[1] < height:
        raise ValueError("the receiver reduced the video before display composition")

    if "HWC layers:" not in text:
        raise ValueError("no HWC composition table in dump")
    hwc = text.split("HWC layers:", 1)[1].split("h/w composer state:", 1)[0]
    entries = []
    for entry in re.finditer(
        r"(?m)^ (SurfaceView[^\n]*)\n\s*(?:rel\s+)?-?\d+\s*\|\s*(\w+)\s*\|"
        r"\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*\|"
        r"\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)", hwc
    ):
        # Android 9 abbreviates the middle of long layer names with [...].
        pattern = ".*".join(re.escape(part) for part in entry[1].strip().split("[...]"))
        if re.fullmatch(pattern, name) and tuple(map(int, entry.group(3, 4, 5, 6))) == frame:
            entries.append(entry)
    if len(entries) != 1:
        raise ValueError("cannot uniquely associate the video layer with its HWC entry")
    entry = entries[0]
    composition = entry[2]
    print(f"Mirrored-video composition: {composition}")
    if composition not in {"Device", "Sideband"}:
        raise ValueError("full-resolution hardware output is not established by this dump")
    left, top, right, bottom = map(float, entry.group(7, 8, 9, 10))
    if right - left < width or bottom - top < height:
        raise ValueError("HWC source crop does not preserve decoded dimensions")
    print("PASS: decoded dimensions reach a hardware-composed video layer")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--decoded-size", required=True)
    parser.add_argument("--package", default="io.github.jqssun.airplay")
    args = parser.parse_args()
    match = re.fullmatch(r"([1-9]\d*)x([1-9]\d*)", args.decoded_size)
    if not match:
        parser.error("--decoded-size must be WIDTHxHEIGHT")
    try:
        check(args.dump.read_text(), tuple(map(int, match.groups())), args.package)
    except ValueError as error:
        sys.exit(f"FAIL: {error}")


if __name__ == "__main__":
    main()
