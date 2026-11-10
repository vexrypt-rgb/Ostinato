#!/usr/bin/env python3
"""OAP scheduling study: summary of live planload runs (LegatoLiveTest -Dostinato.legatotest.arms=planload).

usage: oap_sched_summary.py RAWDIR TAG [TAG ...] [--boot N] [--keep-warmup]

Reads RAWDIR/sched-TAG-{rows.txt,ticks.csv,frames.csv,server.csv} for each TAG and reports each TAG on its own.
Arms: A load 0, B load 1 (one production A* search kept running), C load 0 (A/A' control), D load 3.

The (rep, leg) block holding the run's first lap is a warm-up block (JIT, first world load) and is left out unless
--keep-warmup is given (pre-registered, docs/performance/OAP_SCHEDULING_PREREG.md).

Pooled statistics use every sample of every lap in the arm (client ticks: each lap's first tick dropped, it holds the
lap's setup). Confidence intervals: block bootstrap over (rep, leg) blocks, each block holding one lap of every arm,
so a resample keeps the pairing; nearest-rank quantiles (the harness's own rank()); numpy.random.default_rng(20261110).
"""
import csv
import re
import sys
from collections import defaultdict

import numpy as np

ARMS = ["A", "B", "C", "D"]
LOAD = {"A": 0, "B": 1, "C": 0, "D": 3}
LONG_FRAME_NS = 25_000_000  # 1.5 frames at the 60 FPS cap


def rows(path):
    laps, env = [], []
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("LEGATOTEST planload") or line.startswith("LEGATOTEST start"):
            env.append(line[len("LEGATOTEST "):])
        m = re.match(r"LEGATOTEST lap (\d+) (.*)", line)
        if m:
            d = dict(re.findall(r"(\w+)=(\S+)", m.group(2)))
            d["lap"] = int(m.group(1))
            laps.append(d)
    return laps, env


def samples(path, skip_first, clean=False):
    """{(rep, leg, mode): np.array of ns}. clean: client ticks with flags 0 only (no load search built in the tick, no GC
    pause ended since the tick before), for runs whose ticks file has the flags column."""
    out = defaultdict(list)
    try:
        with open(path, newline="") as f:
            for r in csv.DictReader(f):
                if skip_first and r["i"] == "0":
                    continue
                if clean and r.get("flags", "0") != "0":
                    continue
                out[(int(r["rep"]), r["leg"], r["mode"])].append(int(r["ns"]))
    except FileNotFoundError:
        return {}
    return {k: np.array(v, dtype=np.int64) for k, v in out.items()}


def q(a, p):
    return float(np.quantile(a, p, method="inverted_cdf")) if len(a) else float("nan")


def pooled(data, blocks, arm):
    parts = [data[(r, l, arm)] for r, l in blocks if (r, l, arm) in data]
    return np.concatenate(parts) if parts else np.array([], dtype=np.int64)


def boot_diff(data, blocks, a, b, stat, n, rng):
    """stat(b) - stat(a) with a block-bootstrap 95% CI."""
    est = stat(pooled(data, blocks, b)) - stat(pooled(data, blocks, a))
    vals = []
    idx = np.arange(len(blocks))
    for _ in range(n):
        pick = [blocks[i] for i in rng.choice(idx, size=len(idx), replace=True)]
        vals.append(stat(pooled(data, pick, b)) - stat(pooled(data, pick, a)))
    lo, hi = np.percentile(vals, [2.5, 97.5])
    return est, lo, hi


def fnum(d, k):
    try:
        return float(d[k])
    except (KeyError, ValueError):
        return float("nan")


def summarize(raw, tag, nboot, keep_warmup=False):
    base = f"{raw}/sched-{tag}"
    laps, env = rows(base + "-rows.txt")
    warm = set() if keep_warmup else {(int(d["rep"]), d["leg"]) for d in laps if d["lap"] == 0}
    laps = [d for d in laps if (int(d["rep"]), d["leg"]) not in warm]
    ticks = samples(base + "-ticks.csv", True)
    clean = samples(base + "-ticks.csv", True, clean=True)
    frames = samples(base + "-frames.csv", False)
    server = samples(base + "-server.csv", False)
    print(f"== {tag}: {len(laps)} laps" + (f" (warm-up block {sorted(warm)} left out)" if warm else ""))
    for e in env:
        print("   " + e[:400])
    try:
        print("   meta: " + " ".join(open(base + "-meta.txt", encoding="utf-8").read().split())[:400])
    except FileNotFoundError:
        pass
    blocks = sorted({(int(d["rep"]), d["leg"]) for d in laps})
    rng = np.random.default_rng(20261110)

    print("\n arm load laps reached medTicks | tick p50/p95/p99 ms | frame p50/p95/p99 ms long% | srv p50/p95 ms |"
          " workerCores renderBusy srvBusy procCores | gc/min gcMs/min | firstPath | loadSearches left searchMs buildMs")
    for arm in ARMS:
        L = [d for d in laps if d.get("mode") == arm]
        if not L:
            continue
        ok = [d for d in L if d.get("result") == "REACHED"]
        t = pooled(ticks, blocks, arm) / 1e6
        f = pooled(frames, blocks, arm) / 1e6
        s = pooled(server, blocks, arm) / 1e6
        wall = sum(fnum(d, "wallMs") for d in L)
        per = lambda k: sum(fnum(d, k) for d in L) / wall if wall else float("nan")
        mins = wall / 60000
        longp = 100 * np.mean(f > LONG_FRAME_NS / 1e6) if len(f) else float("nan")
        fp = sorted(fnum(d, "firstPath") for d in L)
        print(f"  {arm}  {LOAD[arm]}  {len(L):4d} {len(ok):4d} {np.median([int(d['ticks']) for d in ok]) if ok else float('nan'):7.0f} |"
              f" {q(t, .5):5.2f} {q(t, .95):6.2f} {q(t, .99):6.2f} | {q(f, .5):5.2f} {q(f, .95):6.2f} {q(f, .99):6.2f} {longp:5.2f} |"
              f" {q(s, .5):5.2f} {q(s, .95):6.2f} | {per('workerCpuMs'):5.2f} {per('renderCpuMs'):5.2f} {per('serverCpuMs'):5.2f}"
              f" {per('procCpuMs'):5.2f} | {sum(fnum(d, 'gcN') for d in L) / mins:5.2f} {sum(fnum(d, 'gcMs') for d in L) / mins:6.1f} |"
              f" {fp[len(fp) // 2]:4.0f} | {sum(fnum(d, 'loadStarted') for d in L):4.0f}"
              f" {sum(1 for d in L if fnum(d, 'loadLeft') > 0)}"
              f" {sum(fnum(d, 'loadWallMs') for d in L) / max(1, sum(fnum(d, 'loadStarted') for d in L)):6.0f}"
              f" {sum(fnum(d, 'loadBuildUs') for d in L) / 1000 / max(1, sum(fnum(d, 'loadStarted') for d in L)):5.2f}")

    try:
        fl = defaultdict(lambda: [0, 0, 0])
        with open(base + "-ticks.csv", newline="") as f:
            for r in csv.DictReader(f):
                if "flags" not in r or r["i"] == "0" or (int(r["rep"]), r["leg"]) in warm:
                    continue
                c = fl[r["mode"]]
                c[0] += 1
                c[1] += int(r["flags"]) & 1
                c[2] += (int(r["flags"]) >> 1) & 1
        if fl:
            print("\n flagged ticks: " + "  ".join(f"{a} build {100 * c[1] / c[0]:.1f}% gc {100 * c[2] / c[0]:.1f}%"
                                                    for a, c in sorted(fl.items())))
    except FileNotFoundError:
        pass
    print(f"\n differences vs A (block bootstrap over {len(blocks)} (rep,leg) blocks, {nboot} resamples)")
    longf = lambda a: 100 * float(np.mean(a > LONG_FRAME_NS)) if len(a) else float("nan")
    tests = [("tick p95 ms", ticks, lambda a: q(a, .95) / 1e6), ("tick p99 ms", ticks, lambda a: q(a, .99) / 1e6),
             ("tick p50 ms", ticks, lambda a: q(a, .5) / 1e6), ("tick mean ms", ticks, lambda a: float(np.mean(a)) / 1e6 if len(a) else float("nan")),
             ("frame p95 ms", frames, lambda a: q(a, .95) / 1e6), ("frame p99 ms", frames, lambda a: q(a, .99) / 1e6),
             ("long frames %", frames, longf), ("server p95 ms", server, lambda a: q(a, .95) / 1e6),
             ("clean tick p95", clean, lambda a: q(a, .95) / 1e6), ("clean tick p99", clean, lambda a: q(a, .99) / 1e6)]
    for arm in ["C", "B", "D"]:
        if not any(d.get("mode") == arm for d in laps):
            continue
        for name, data, stat in tests:
            if not data:
                continue
            est, lo, hi = boot_diff(data, blocks, "A", arm, stat, nboot, rng)
            base_v = stat(pooled(data, blocks, "A"))
            print(f"  {arm}-A {name:14s} {est:+7.3f} [{lo:+7.3f}, {hi:+7.3f}]   (A {base_v:7.3f})")
        # traversal: pooled lap-tick ratio over blocks where both arms reached
        pairs = []
        for r, l in blocks:
            a = [d for d in laps if d.get("mode") == "A" and int(d["rep"]) == r and d["leg"] == l and d["result"] == "REACHED"]
            b = [d for d in laps if d.get("mode") == arm and int(d["rep"]) == r and d["leg"] == l and d["result"] == "REACHED"]
            if a and b:
                pairs.append((int(a[0]["ticks"]), int(b[0]["ticks"])))
        if pairs:
            pa = np.array(pairs, dtype=float)
            ratio = pa[:, 1].sum() / pa[:, 0].sum()
            bs = []
            for _ in range(nboot):
                k = pa[rng.integers(0, len(pa), len(pa))]
                bs.append(k[:, 1].sum() / k[:, 0].sum())
            lo, hi = np.percentile(bs, [2.5, 97.5])
            print(f"  {arm}/A lap ticks ratio {ratio:.4f} [{lo:.4f}, {hi:.4f}] over {len(pa)} pairs both reached")
    print()


def main():
    args = sys.argv[1:]
    nboot = 2000
    if "--boot" in args:
        i = args.index("--boot")
        nboot = int(args[i + 1])
        del args[i:i + 2]
    keep = "--keep-warmup" in args
    if keep:
        args.remove("--keep-warmup")
    raw, tags = args[0], args[1:]
    for tag in tags:
        summarize(raw, tag, nboot, keep)


if __name__ == "__main__":
    main()
