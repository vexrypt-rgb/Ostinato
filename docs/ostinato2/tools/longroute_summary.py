"""Ostinato 2.0 Phase 07: summarise LongRouteSimTest.corpus output (<label>-trips.csv, <label>-searches.csv).

usage: python longroute_summary.py <dir> <label> [<label> ...]

Prints, per family x mode x arm: trips reached, travel / wait / total ticks (planner tier), wait share, plan ms,
first-search ms, per-search p50/p95/p99, nodes, allocation, escalations, plan-ahead retries, sealed; then the paired
HPA/PROD ratios per route (total ticks, plan ms) with a bootstrap 95% CI of the median ratio (10,000 resamples,
random.Random(20261108), percentile interval), and the cancel latencies.
"""
import csv
import random
import statistics
import sys
from collections import defaultdict


def pct(xs, q):
    xs = sorted(xs)
    if not xs:
        return float("nan")
    k = (len(xs) - 1) * q
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def boot_median(xs, n=10000):
    if len(xs) < 2:
        return (float("nan"), float("nan"))
    r = random.Random(20261108)
    ms = sorted(statistics.median(r.choices(xs, k=len(xs))) for _ in range(n))
    return (ms[int(0.025 * n)], ms[int(0.975 * n) - 1])


def main():
    d = sys.argv[1]
    trips, searches = [], []
    for label in sys.argv[2:]:
        with open(f"{d}/{label}-trips.csv", newline="") as f:
            trips += list(csv.DictReader(f))
        with open(f"{d}/{label}-searches.csv", newline="") as f:
            searches += list(csv.DictReader(f))
    cancel = [s for s in searches if s["type"] == "CANCEL_LATENCY"]
    searches = [s for s in searches if s["type"] != "CANCEL_LATENCY"]

    by = defaultdict(list)
    for t in trips:
        by[(t["family"], t["mode"], t["arm"])].append(t)
    sby = defaultdict(list)
    for s in searches:
        sby[(s["family"], s["mode"], s["arm"])].append(float(s["ms"]))

    print("family,mode,arm,trips,ok,travel,wait,total,waitShare%,planMs,firstMs,p50,p95,p99,searches,nodesK,allocMB,esc,aheadFails,sealed,reasons")
    for k in sorted(by):
        ts = by[k]
        f = lambda c: sum(float(t[c]) for t in ts)
        ok = sum(t["ok"] == "true" for t in ts)
        travel, wait = f("travel"), f("wait")
        ms = sby[k]
        reasons = ";".join(sorted({t["reason"] for t in ts if t["reason"]}))
        print(",".join(map(str, [*k, len(ts), ok, round(travel), round(wait, 1), round(travel + wait),
                                 round(100 * wait / max(1e-9, travel + wait), 2), round(f("planMs")),
                                 round(statistics.median(float(t["firstMs"]) for t in ts), 1),
                                 round(pct(ms, .5), 1), round(pct(ms, .95), 1), round(pct(ms, .99), 1), int(f("searches")),
                                 round(f("nodes") / 1000), round(f("allocMB")), int(f("escalations")), int(f("aheadFails")),
                                 int(f("sealed")), reasons])))

    # paired ratios HPA / PROD per route
    pair = defaultdict(dict)
    for t in trips:
        pair[(t["set"], t["seed"], t["family"], t["mode"], t["length"], t["n"])][t["arm"]] = t
    rt = defaultdict(list)
    rp = defaultdict(list)
    lost = defaultdict(int)
    for key, arms in pair.items():
        if "PROD" not in arms or "HPA" not in arms:
            continue
        a, b = arms["PROD"], arms["HPA"]
        g = (key[2], key[3])
        if a["ok"] == "true" and b["ok"] != "true":
            lost[g] += 1
        if a["ok"] == "true" and b["ok"] == "true":
            rt[g].append(float(b["total"]) / float(a["total"]))
            rp[g].append(float(b["planMs"]) / max(1e-3, float(a["planMs"])))
    print()
    print("family,mode,pairs,hpaLost,totalRatioMed,CI,planRatioMed,CI")
    for g in sorted(rt):
        lo, hi = boot_median(rt[g])
        plo, phi = boot_median(rp[g])
        print(f"{g[0]},{g[1]},{len(rt[g])},{lost[g]},{statistics.median(rt[g]):.4f},[{lo:.4f};{hi:.4f}],"
              f"{statistics.median(rp[g]):.3f},[{plo:.3f};{phi:.3f}]")
    allt = [x for v in rt.values() for x in v]
    allp = [x for v in rp.values() for x in v]
    if allt:
        lo, hi = boot_median(allt)
        plo, phi = boot_median(allp)
        print(f"ALL,,{len(allt)},{sum(lost.values())},{statistics.median(allt):.4f},[{lo:.4f};{hi:.4f}],"
              f"{statistics.median(allp):.3f},[{plo:.3f};{phi:.3f}]")

    if cancel:
        print()
        print("cancel latency ms (family,arm: values)")
        cl = defaultdict(list)
        for s in cancel:
            cl[(s["family"], s["arm"])].append(float(s["ms"]))
        for k in sorted(cl):
            print(f"{k[0]},{k[1]}: median {statistics.median(cl[k]):.2f} max {max(cl[k]):.2f} n={len(cl[k])}")


if __name__ == "__main__":
    main()
