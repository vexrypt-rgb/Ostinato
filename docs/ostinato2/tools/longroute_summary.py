"""Ostinato 2.0 Phase 07: summarise LongRouteSimTest.corpus output (<label>-trips.csv, <label>-searches.csv).

usage: python longroute_summary.py [--skip-modes=goal,...] [--arms=BASE,TEST] <dir> <label> [<label> ...]

Prints, per family x mode x arm: trips reached, travel / wait / total ticks (planner tier), wait share, plan ms,
first-search ms, per-search p50/p95/p99, nodes, allocation, escalations, plan-ahead retries, sealed; then the paired
TEST/BASE ratios per route (total ticks, plan ms, plan ms per expanded node) with a bootstrap 95% CI of the median
ratio (10,000 resamples, random.Random(20261108), percentile interval), the cancel latencies, and the held-out checks:
BASE wait share overall and its largest cell (C1), and the outcomes of TEST searches whose goal was simplified (C3).
--arms defaults to PROD,HPA. With a TEST arm that must not change behaviour (IDS, the Phase 07 state-id cache) the
equivalence check compares travel, nodes, segments, searches and end distance in every pair where neither trip had a
timed-out search, and lists each mismatch.
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
    args = sys.argv[1:]
    skip = set()
    base, test = "PROD", "HPA"
    while args and args[0].startswith("--"):
        k, v = args.pop(0)[2:].split("=", 1)
        if k == "skip-modes":
            skip = set(v.split(","))
        elif k == "arms":
            base, test = v.split(",")
        else:
            sys.exit(f"unknown option --{k}")
    d = args[0]
    trips, searches = [], []
    for label in args[1:]:
        with open(f"{d}/{label}-trips.csv", newline="") as f:
            trips += list(csv.DictReader(f))
        with open(f"{d}/{label}-searches.csv", newline="") as f:
            searches += list(csv.DictReader(f))
    trips = [t for t in trips if t["mode"] not in skip]
    searches = [s for s in searches if s["mode"] not in skip]
    if skip:
        print(f"skipped modes: {','.join(sorted(skip))}")
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

    # paired ratios TEST / BASE per route
    pair = defaultdict(dict)
    for t in trips:
        pair[(t["set"], t["seed"], t["family"], t["mode"], t["length"], t["n"])][t["arm"]] = t
    timed = set()
    for s in searches:
        if s["timedOut"] == "true":
            timed.add((s["set"], s["seed"], s["family"], s["mode"], s["length"], s["n"], s["arm"]))
    rt = defaultdict(list)
    rp = defaultdict(list)
    rn = defaultdict(list)
    lost = defaultdict(int)
    gained = defaultdict(int)
    eq_checked, eq_bad = 0, []
    for key, arms in pair.items():
        if base not in arms or test not in arms:
            continue
        a, b = arms[base], arms[test]
        g = (key[2], key[3])
        if float(a["nodes"]) > 0 and float(b["nodes"]) > 0:
            rn[g].append((float(b["planMs"]) / float(b["nodes"])) / max(1e-9, float(a["planMs"]) / float(a["nodes"])))
        if key + (base,) not in timed and key + (test,) not in timed:
            eq_checked += 1
            diff = [c for c in ("ok", "travel", "nodes", "segments", "searches", "endDist") if a[c] != b[c]]
            if diff:
                eq_bad.append((key, {c: (a[c], b[c]) for c in diff}))
        if a["ok"] == "true" and b["ok"] != "true":
            lost[g] += 1
        if a["ok"] != "true" and b["ok"] == "true":
            gained[g] += 1
        if a["ok"] == "true" and b["ok"] == "true":
            rt[g].append(float(b["total"]) / float(a["total"]))
            rp[g].append(float(b["planMs"]) / max(1e-3, float(a["planMs"])))
    print()
    print(f"pairs {test}/{base}")
    print("family,mode,pairs,testLost,testGained,totalRatioMed,CI,planRatioMed,CI,msPerNodeRatioMed,CI,promotion")
    promoted = []
    for g in sorted(set(rt) | set(lost) | set(gained) | set(rn)):
        lo, hi = boot_median(rt[g])
        plo, phi = boot_median(rp[g])
        nlo, nhi = boot_median(rn[g])
        tm = statistics.median(rt[g]) if rt[g] else float("nan")
        pm = statistics.median(rp[g]) if rp[g] else float("nan")
        nm = statistics.median(rn[g]) if rn[g] else float("nan")
        # C2: the task's provisional promotion target, per family, judged in known mode
        meets = (pm <= 0.70 and phi < 1.0 and lost[g] - gained[g] <= 0 and tm <= 1.01 and hi <= 1.03)
        if meets and g[1] == "known":
            promoted.append(g[0])
        print(f"{g[0]},{g[1]},{len(rt[g])},{lost[g]},{gained[g]},{tm:.4f},[{lo:.4f};{hi:.4f}],"
              f"{pm:.3f},[{plo:.3f};{phi:.3f}],{nm:.4f},[{nlo:.4f};{nhi:.4f}],{'MEETS' if meets else 'no'}")
    allt = [x for v in rt.values() for x in v]
    allp = [x for v in rp.values() for x in v]
    alln = [x for v in rn.values() for x in v]
    if allt:
        lo, hi = boot_median(allt)
        plo, phi = boot_median(allp)
        nlo, nhi = boot_median(alln)
        print(f"ALL,,{len(allt)},{sum(lost.values())},{sum(gained.values())},{statistics.median(allt):.4f},"
              f"[{lo:.4f};{hi:.4f}],{statistics.median(allp):.3f},[{plo:.3f};{phi:.3f}],"
              f"{statistics.median(alln):.4f},[{nlo:.4f};{nhi:.4f}],")
    print(f"C2 known-mode families meeting the promotion target: {','.join(promoted) or 'none'}")
    print(f"equivalence: {eq_checked} pairs with no timed-out search, {len(eq_bad)} differ")
    for key, d in eq_bad[:20]:
        print(f"  differs {','.join(key)}: {d}")

    # C1: BASE wait share overall and its largest family x mode cell
    prod = [t for t in trips if t["arm"] == base]
    if prod:
        tw = sum(float(t["wait"]) for t in prod)
        tt = sum(float(t["travel"]) + float(t["wait"]) for t in prod)
        cells = defaultdict(lambda: [0.0, 0.0])
        for t in prod:
            c = cells[(t["family"], t["mode"])]
            c[0] += float(t["wait"])
            c[1] += float(t["travel"]) + float(t["wait"])
        worst = max(cells, key=lambda k: cells[k][0] / max(1e-9, cells[k][1]))
        print()
        print(f"C1 {base} wait share: overall {100 * tw / tt:.2f}% over {len(prod)} trips; largest cell "
              f"{worst[0]} {worst[1]} {100 * cells[worst][0] / cells[worst][1]:.2f}%")

    # C3: outcomes of TEST searches whose goal was simplified (unloaded GoalBlock -> GoalXZ)
    simp = defaultdict(int)
    for s in searches:
        if s["arm"] == test and s["simplified"] == "true":
            simp[(s["mode"], s["outcome"])] += 1
    if simp:
        print()
        print(f"C3 {test} searches with a simplified goal (mode,outcome: n)")
        for k in sorted(simp):
            print(f"{k[0]},{k[1]}: {simp[k]}")

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
