#!/usr/bin/env python3
"""Compares the MEASURE lines of measure-app.sh for two builds measured on the same emulator
(spec §8: repeated values, their spread, before/after, and no improvement smaller than the noise).
For each metric: count, median, spread (min-max, standard deviation) per build, and the change of
the median. A change is reported as a difference only when it exceeds the noise margin: twice the
larger standard deviation of the two builds, and at least 5 % of the before median; otherwise
'within noise'. Prints a Markdown table and a JSON line. Exit 1 if a metric has fewer than three
values for either build (too few to tell noise from change).
Usage: measure-compare.py <before label> <after label> < measure lines"""
import json
import statistics
import sys


def parse(lines):
    values = {}
    for line in lines:
        parts = line.split()
        if len(parts) == 4 and parts[0] == "MEASURE":
            try:
                values.setdefault(parts[1], {}).setdefault(parts[2], []).append(float(parts[3]))
            except ValueError:
                pass
    return values


def compare(values, before, after):
    rows, problems = [], []
    for metric in sorted(set(values.get(before, {})) | set(values.get(after, {}))):
        a, b = values.get(before, {}).get(metric, []), values.get(after, {}).get(metric, [])
        if metric != "apk_bytes" and (len(a) < 3 or len(b) < 3):
            problems.append(f"{metric}: {len(a)} and {len(b)} values; at least 3 each are needed")
            continue
        if not a or not b:
            problems.append(f"{metric}: missing for one build")
            continue
        ma, mb = statistics.median(a), statistics.median(b)
        sa = statistics.stdev(a) if len(a) > 1 else 0.0
        sb = statistics.stdev(b) if len(b) > 1 else 0.0
        margin = max(2 * max(sa, sb), 0.05 * ma) if metric != "apk_bytes" else 0.0
        change = mb - ma
        verdict = "within noise" if abs(change) <= margin else ("higher" if change > 0 else "lower")
        rows.append({"metric": metric, "before_median": ma, "after_median": mb, "change": change,
                     "change_pct": round(100 * change / ma, 1) if ma else None, "noise_margin": round(margin, 1),
                     "before": {"n": len(a), "min": min(a), "max": max(a), "stdev": round(sa, 1)},
                     "after": {"n": len(b), "min": min(b), "max": max(b), "stdev": round(sb, 1)},
                     "verdict": verdict})
    return rows, problems


def main():
    before, after = sys.argv[1], sys.argv[2]
    rows, problems = compare(parse(sys.stdin.read().splitlines()), before, after)
    print(f"| Metric | {before} median (n, min–max, sd) | {after} median (n, min–max, sd) | Change | Noise margin | Verdict |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        fb, fa = r["before"], r["after"]
        print(f"| {r['metric']} | {r['before_median']:g} ({fb['n']}, {fb['min']:g}–{fb['max']:g}, {fb['stdev']}) | "
              f"{r['after_median']:g} ({fa['n']}, {fa['min']:g}–{fa['max']:g}, {fa['stdev']}) | "
              f"{r['change']:+g} ({r['change_pct']} %) | {r['noise_margin']} | {r['verdict']} |")
    print("COMPARE " + json.dumps(rows))
    for p in problems:
        print(f"::error::{p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
