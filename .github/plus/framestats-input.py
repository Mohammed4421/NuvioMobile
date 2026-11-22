#!/usr/bin/env python3
"""Reads `dumpsys gfxinfo <package> framestats` on stdin and prints the median time, in ms, from the
intended vsync of each frame that handled a touch (HANDLE_INPUT_START set) to that frame being
completed: how long the app took to show its response to the finger (Build 15, P5-02). Prints
nothing when no such frame was drawn. Only standard Python."""
import statistics
import sys


def input_frame_ms(text):
    header, times = None, []
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
            start = int(cells[header["HANDLE_INPUT_START"]])
            vsync = int(cells[header["INTENDED_VSYNC"]])
            done = int(cells[header["FRAME_COMPLETED"]])
        except (KeyError, ValueError, IndexError):
            continue
        if flags == 0 and start > 0 and done > vsync > 0:
            times.append((done - vsync) / 1_000_000)
    return round(statistics.median(times), 1) if times else None


if __name__ == "__main__":
    value = input_frame_ms(sys.stdin.read())
    if value is not None:
        print(value)
