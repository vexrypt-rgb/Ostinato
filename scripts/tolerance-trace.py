# Ostinato 2.0 Phase 04 validation: per-mode bounds from two live tolerance-trace.csv sessions (dev, held-out), judged
# against the pre-registered criteria in docs/ostinato2/BENCHMARKS.md ("Phase 04 live validation").
# Usage: python scripts/tolerance-trace.py <dev.csv[.gz]> <heldout.csv[.gz]>
import csv
import gzip
import math
import sys
from collections import Counter, defaultdict

MODES = ["WALK", "SPRINT", "SPRINT_JUMP", "FALL", "FLUID", "CLIMB"]
MIN_N = 300          # fewer usable rows than this in either session: insufficient data
MAX_EXCEED = 0.02    # held-out share over the dev bound that still validates
FAIL_P99 = 0.30      # held-out p99 above this (n >= MIN_N): unacceptable prediction error
RESOLUTION = 1e-6    # the trace CSV's error resolution (6 decimals)


def q(sorted_v, p):
    """Nearest rank, as the harness logs it."""
    if not sorted_v:
        return float("nan")
    return sorted_v[max(0, min(len(sorted_v) - 1, math.ceil(p * len(sorted_v)) - 1))]


def load(path):
    err = defaultdict(list)       # usable rows: primary error
    land = defaultdict(list)      # usable rows: land tick() error (what SimTrace computes in water)
    yaw = defaultdict(list)       # usable rows: error with the yaw the client used
    excl = defaultdict(Counter)
    seg = Counter()
    seeds = set()
    rows = 0
    opener = gzip.open if path.endswith(".gz") else open
    for r in csv.DictReader(opener(path, "rt", newline="", encoding="utf-8")):
        rows += 1
        seeds.add(r["seed"])
        m = r["mode"]
        if r["excl"]:
            excl[m][r["excl"]] += 1
            continue
        err[m].append(float(r["err"]))
        land[m].append(float(r["errLand"]))
        yaw[m].append(float(r["errYaw"]))
        seg[(m, r["segType"])] += 1
    for d in (err, land, yaw):
        for v in d.values():
            v.sort()
    return dict(err=err, land=land, yaw=yaw, excl=excl, seg=seg, seeds=seeds, rows=rows)


def main(dev_path, ho_path):
    dev, ho = load(dev_path), load(ho_path)
    print(f"dev {dev_path}: seeds {sorted(dev['seeds'])}, {dev['rows']} rows")
    print(f"held-out {ho_path}: seeds {sorted(ho['seeds'])}, {ho['rows']} rows")
    if dev["seeds"] & ho["seeds"]:
        print("WARNING: the sessions share a seed")

    print("\n## Bounds (B1): dev p99 as the bound, held-out share over it")
    print("| mode | dev n | dev p50 | dev p95 | dev p99 (bound) | dev max | held-out n | held-out p99 | held-out over bound | status |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    status = {}
    for m in MODES:
        d, h = dev["err"][m], ho["err"][m]
        # addendum: the CSV has 6 decimals; a p99 that rounds to 0 is "< 1e-6" and its bound is the resolution
        bound = max(q(d, 0.99), RESOLUTION) if d else float("nan")
        over = sum(e > bound for e in h)
        share = over / len(h) if h else float("nan")
        hp99 = q(h, 0.99)
        if len(d) < MIN_N or len(h) < MIN_N:
            s = "insufficient data"
        elif hp99 > FAIL_P99:
            s = "FAIL (held-out p99 > 0.30)"
        elif share <= MAX_EXCEED:
            s = "validated"
        else:
            s = "not validated"
        status[m] = s
        print(f"| {m} | {len(d)} | {q(d, .5):.6f} | {q(d, .95):.6f} | {bound:.6f} | {q(d, 1):.6f} | {len(h)} | {hp99:.6f} | "
              f"{over}/{len(h)} = {100 * share:.2f}% | {s} |")

    print("\n## Excluded rows (per session, per mode)")
    print("| mode | dev packet | dev settle | dev keys | dev rest | held-out packet | held-out settle | held-out keys | held-out rest |")
    print("|---|---|---|---|---|---|---|---|---|")
    for m in MODES:
        a, b = dev["excl"][m], ho["excl"][m]
        print(f"| {m} | {a['packet']} | {a['settle']} | {a['keys']} | {a['rest']} | {b['packet']} | {b['settle']} | {b['keys']} | {b['rest']} |")

    print("\n## Diagnostics (not gated): p99 of usable rows")
    print("| mode | dev primary | dev land tick() | dev with yaw used | held-out primary | held-out land tick() | held-out with yaw used |")
    print("|---|---|---|---|---|---|---|")
    for m in MODES:
        print(f"| {m} | {q(dev['err'][m], .99):.6f} | {q(dev['land'][m], .99):.6f} | {q(dev['yaw'][m], .99):.6f} | "
              f"{q(ho['err'][m], .99):.6f} | {q(ho['land'][m], .99):.6f} | {q(ho['yaw'][m], .99):.6f} |")

    print("\n## Usable rows by mode and script segment (dev / held-out)")
    segs = sorted({s for (_, s) in list(dev["seg"]) + list(ho["seg"])})
    print("| mode | " + " | ".join(segs) + " |")
    print("|---|" + "---|" * len(segs))
    for m in MODES:
        print(f"| {m} | " + " | ".join(f"{dev['seg'][(m, s)]}/{ho['seg'][(m, s)]}" for s in segs) + " |")

    print("\n## B1 verdict")
    fails = [m for m in MODES if status[m].startswith("FAIL")]
    ok = [m for m in MODES if status[m] == "validated"]
    print(f"validated {len(ok)}/6: {ok}")
    print(f"not validated: {[m for m in MODES if status[m] == 'not validated']}")
    print(f"insufficient data: {[m for m in MODES if status[m] == 'insufficient data']}")
    print(f"unacceptable error (FAIL): {fails}")
    print("new MEASURED values for validated modes (dev p99, resolution floor 1e-6): "
          + ", ".join(f"{m}={max(q(dev['err'][m], .99), RESOLUTION):.6f}" for m in ok))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: python scripts/tolerance-trace.py <dev.csv> <heldout.csv>")
    main(sys.argv[1], sys.argv[2])
