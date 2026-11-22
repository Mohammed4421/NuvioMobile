#!/usr/bin/env python3
"""Reads `dumpsys gfxinfo <package> framestats` on stdin and prints the median time, in ms, from the
intended vsync of each frame that handled a touch to that frame being completed: how long the app
took to draw its answer to the finger (Build 15, P5-02). Prints nothing when no frame handled a touch,
and a count line on stderr.

Column names differ between Android versions (INTENDED_VSYNC, IntendedVsync); they are matched
without case or underscores. A frame handled a touch when its input event id is set (Android 12+,
InputEventId), or on older versions when HANDLE_INPUT_START is set. Only standard Python."""
import statistics
import sys


def key(name):
    return name.strip().upper().replace("_", "")


def input_frame_ms(text):
    header, times, rows = None, [], 0
    for line in text.splitlines():
        cells = [c.strip() for c in line.split(",")]
        if cells and key(cells[0]) == "FLAGS":
            header = {key(name): index for index, name in enumerate(cells) if name.strip()}
            continue
        if not header or len(cells) < len(header):
            continue
        try:
            value = lambda name: int(cells[header[name]])
            flags, vsync, done = value("FLAGS"), value("INTENDEDVSYNC"), value("FRAMECOMPLETED")
            touched = value("INPUTEVENTID") != 0 if "INPUTEVENTID" in header else value("HANDLEINPUTSTART") > 0
        except (KeyError, ValueError, IndexError):
            continue
        rows += 1
        if flags == 0 and touched and done > vsync > 0:
            times.append((done - vsync) / 1_000_000)
    print(f"framestats: {rows} frames, {len(times)} handled a touch", file=sys.stderr)
    if not rows:
        around = [l[:160] for l in text.splitlines() if "PROFILEDATA" in l or key(l.split(",")[0]) == "FLAGS"][:3]
        print(f"framestats shape: {around}", file=sys.stderr)
    return round(statistics.median(times), 1) if times else None


if __name__ == "__main__":
    value = input_frame_ms(sys.stdin.read())
    if value is not None:
        print(value)
