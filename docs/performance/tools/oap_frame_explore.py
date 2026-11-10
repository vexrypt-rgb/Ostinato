#!/usr/bin/env python3
"""OAP scheduling study, EXPLORATORY (not pre-registered): frame-interval distribution and frame rate by arm.

usage: oap_frame_explore.py RAWDIR TAG [TAG ...]

Why: in e1-main the pre-registered long-frame share (> 25 ms) rose ~8 points with the planner load while frame p95
barely moved. A fixed threshold inside a dense part of the distribution amplifies a small shift; this shows the whole
distribution, the frame rate, and per-lap Render-thread CPU per frame, so the size of the shift can be read directly.
Same warm-up block rule and bootstrap as oap_sched_summary.py.
"""
import sys

import numpy as np

sys.path.insert(0, __file__.rsplit("/", 1)[0].rsplit("\\", 1)[0])
from oap_sched_summary import rows, samples, pooled, q, fnum  # noqa: E402

QS = [.5, .7, .75, .8, .9, .95, .99]


def main():
    raw, tags = sys.argv[1], sys.argv[2:]
    for tag in tags:
        base = f"{raw}/sched-{tag}"
        laps, _ = rows(base + "-rows.txt")
        warm = {(int(d["rep"]), d["leg"]) for d in laps if d["lap"] == 0}
        laps = [d for d in laps if (int(d["rep"]), d["leg"]) not in warm]
        blocks = sorted({(int(d["rep"]), d["leg"]) for d in laps})
        frames = samples(base + "-frames.csv", False)
        rng = np.random.default_rng(20261110)
        print(f"== {tag} (exploratory) {len(blocks)} blocks")
        print("  arm  fps   mean |" + "".join(f"  p{int(p * 100):<4}" for p in QS) + "| render CPU ms/frame")
        for arm in "ABCD":
            L = [d for d in laps if d.get("mode") == arm]
            f = pooled(frames, blocks, arm) / 1e6
            if not len(f):
                continue
            rcpu = sum(fnum(d, "renderCpuMs") for d in L) / sum(fnum(d, "frames") for d in L)
            print(f"  {arm}  {1000 / f.mean():5.1f} {f.mean():6.2f} |" + "".join(f" {q(f, p):6.2f}" for p in QS) + f"| {rcpu:6.3f}")
        for arm in "BCD":
            vals = []
            idx = np.arange(len(blocks))
            stat = lambda bl, a: float(np.mean(pooled(frames, bl, a))) / 1e6
            est = stat(blocks, arm) - stat(blocks, "A")
            for _ in range(2000):
                pick = [blocks[i] for i in rng.choice(idx, size=len(idx), replace=True)]
                vals.append(stat(pick, arm) - stat(pick, "A"))
            lo, hi = np.percentile(vals, [2.5, 97.5])
            print(f"  {arm}-A mean frame interval {est:+.3f} ms [{lo:+.3f}, {hi:+.3f}]")
        print()


if __name__ == "__main__":
    main()
