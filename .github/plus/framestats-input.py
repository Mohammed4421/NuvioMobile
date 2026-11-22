#!/usr/bin/env python3
"""Reads `dumpsys gfxinfo <package> framestats` on stdin and prints the median time, in ms, from the
oldest touch event a frame handled (OLDEST_INPUT_EVENT) to that frame being completed: how long the
app took to show its response to the finger (Build 15, P5-02). Frames without that column's value
fall back to HANDLE_INPUT_START (older Android versions; touches injected by `input` leave it empty on
newer ones). Prints nothing when no frame handled a touch, and a count line on stderr.
Only standard Python."""
import statistics
import sys


def input_frame_ms(text):
    header, times, rows = None, [], 0
    for line in text.splitlines():
        cells = [c.strip() for c in line.split(",")]
        if cells and cells[0] == "Flags":
            header = {name: index for index, name in enumerate(cells)}
            continue
        if line.startswith("---PROFILEDATA---"):
            header = header if header else None
            continue
        if not header or len(cells) < len(header) - 1:
            continue
        try:
            flags = int(cells[header["Flags"]])
            done = int(cells[header["FRAME_COMPLETED"]])
            oldest = int(cells[header["OLDEST_INPUT_EVENT"]]) if "OLDEST_INPUT_EVENT" in header else 0
            handle = int(cells[header["HANDLE_INPUT_START"]])
            vsync = int(cells[header["INTENDED_VSYNC"]])
        except (KeyError, ValueError, IndexError):
            continue
        rows += 1
        if flags != 0:
            continue
        if 0 < oldest < done:
            times.append((done - oldest) / 1_000_000)
        elif handle > 0 and done > vsync > 0:
            times.append((done - vsync) / 1_000_000)
    print(f"framestats: {rows} frames, {len(times)} handled a touch", file=sys.stderr)
    return round(statistics.median(times), 1) if times else None


if __name__ == "__main__":
    value = input_frame_ms(sys.stdin.read())
    if value is not None:
        print(value)
