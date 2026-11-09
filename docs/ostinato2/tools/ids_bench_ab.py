"""Ostinato 2.0 Phase 07: paired PathBench A/B of pathingStateIdCache from the -raw.csv files.

usage: python ids_bench_ab.py <dir> <off-label,off-label...> <on-label,on-label...>

Per query: the median ms over every repetition of every OFF run, and the same for ON. A query whose node count is the
same in every row of both arms (no budget bound: the same search) is "same-search"; its ON/OFF ms ratio is a pure speed
measure. A query that differs (a timed-out search expands more nodes in the same time) is compared in ms per node.
Prints per length class and per terrain (same-search queries): queries, the median ratio, its bootstrap 95% CI
(10,000 resamples, random.Random(20261108), percentile interval), and the summed ms; then the differing queries, the
failure counts per arm, and the mean travel cost over queries both arms solved.
"""
import csv
import random
import statistics
import sys
from collections import defaultdict


def boot_median(xs, n=10000):
    if len(xs) < 2:
        return (float("nan"), float("nan"))
    r = random.Random(20261108)
    ms = sorted(statistics.median(r.choices(xs, k=len(xs))) for _ in range(n))
    return (ms[int(0.025 * n)], ms[int(0.975 * n) - 1])


def load(d, labels):
    rows = defaultdict(list)
    for label in labels:
        with open(f"{d}/{label}-raw.csv", newline="") as f:
            for r in csv.DictReader(f):
                rows[r["query"]].append(r)
    return rows


def main():
    d, off_l, on_l = sys.argv[1], sys.argv[2].split(","), sys.argv[3].split(",")
    off, on = load(d, off_l), load(d, on_l)
    same, diff = {}, {}
    fails = {"OFF": 0, "ON": 0}
    cost_pairs = []
    for q in off:
        a, b = off[q], on[q]
        fails["OFF"] += sum(r["ok"] != "true" for r in a)
        fails["ON"] += sum(r["ok"] != "true" for r in b)
        ma = statistics.median(float(r["ms"]) for r in a)
        mb = statistics.median(float(r["ms"]) for r in b)
        nodes = {r["nodes"] for r in a} | {r["nodes"] for r in b}
        if all(r["ok"] == "true" for r in a + b):
            cost_pairs.append((statistics.median(float(r["cost"]) for r in a), statistics.median(float(r["cost"]) for r in b)))
        if len(nodes) == 1:
            same[q] = (ma, mb)
        else:
            na = statistics.median(int(r["nodes"]) for r in a)
            nb = statistics.median(int(r["nodes"]) for r in b)
            diff[q] = (ma, mb, na, nb)

    print(f"OFF runs: {','.join(off_l)}  ON runs: {','.join(on_l)}")
    print(f"same-search queries: {len(same)}, differing: {len(diff)}")
    print()
    print("group,queries,ratioMed,CI,offMsSum,onMsSum,sumRatio")
    groups = defaultdict(list)
    for q, v in same.items():
        t, length, _ = q.split("/")
        groups["length " + length].append(v)
        groups["terrain " + t].append(v)
        groups["ALL"].append(v)
    for g in sorted(groups, key=lambda g: (g != "ALL", g)):
        vs = groups[g]
        rs = [b / a for a, b in vs]
        lo, hi = boot_median(rs)
        sa, sb = sum(a for a, _ in vs), sum(b for _, b in vs)
        print(f"{g},{len(vs)},{statistics.median(rs):.4f},[{lo:.4f};{hi:.4f}],{sa:.1f},{sb:.1f},{sb / sa:.4f}")
    if diff:
        print()
        print("differing queries (budget bound): query,offMs,onMs,offNodes,onNodes,msPerNodeRatio")
        for q, (ma, mb, na, nb) in sorted(diff.items()):
            print(f"{q},{ma:.1f},{mb:.1f},{na:.0f},{nb:.0f},{(mb / max(1, nb)) / (ma / max(1, na)):.4f}")
    print()
    print(f"failed rows: OFF {fails['OFF']}, ON {fails['ON']}")
    if cost_pairs:
        print(f"mean travel cost over {len(cost_pairs)} queries both solved: OFF {statistics.mean(a for a, _ in cost_pairs):.2f}, "
              f"ON {statistics.mean(b for _, b in cost_pairs):.2f}")


if __name__ == "__main__":
    main()
