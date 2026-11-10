"""OAP Stage 1: compare planning-budget arms of LongRouteSimTest.corpus (OSTINATO_LONGROUTE_BUDGETS).

usage: python oap_budget_summary.py <dir> <label> [--base=base] [--families=A,B]

Pairs every candidate arm's trips with the base arm's on the route key (set, seed, family, mode, length, n) and prints,
per candidate: reliability (G1), pooled planner CPU ratio (G2), pooled travel+wait ratio (G3) with bootstrap 95% CIs
over paired trips (10,000 resamples, random.Random(20261109), percentile), and the same per family. Ratios are ratios of
sums (pooled), not medians of per-trip ratios: a trip that plans for 16 s weighs what it costs.
"""
import csv
import random
import sys
from collections import defaultdict

SEED = 20261109
N_BOOT = 10000


def boot_ratio(pairs, num, den):
    if len(pairs) < 2:
        return float("nan"), float("nan")
    r = random.Random(SEED)
    out = []
    for _ in range(N_BOOT):
        s = r.choices(pairs, k=len(pairs))
        d = sum(den(p) for p in s)
        out.append(sum(num(p) for p in s) / d if d else float("nan"))
    out.sort()
    return out[int(0.025 * N_BOOT)], out[int(0.975 * N_BOOT) - 1]


def ratio(pairs, num, den):
    d = sum(den(p) for p in pairs)
    return sum(num(p) for p in pairs) / d if d else float("nan")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--"))
    base_name = opts.get("base", "base")
    fams = set(opts["families"].split(",")) if "families" in opts else None
    d, label = args[0], args[1]
    with open(f"{d}/{label}-trips.csv", newline="") as f:
        trips = list(csv.DictReader(f))
    with open(f"{d}/{label}-searches.csv", newline="") as f:
        searches = list(csv.DictReader(f))
    if fams:
        trips = [t for t in trips if t["family"] in fams]
        searches = [s for s in searches if s["family"] in fams]
    by = defaultdict(dict)  # route key -> budget -> row
    budgets = []
    for t in trips:
        b = t["arm"].split("@", 1)[1] if "@" in t["arm"] else ""
        if b not in budgets:
            budgets.append(b)
        by[(t["set"], t["seed"], t["family"], t["mode"], t["length"], t["n"])][b] = t

    # per-arm search stats
    print(f"{label}: {len(by)} routes, arms {budgets}")
    print(f"{'arm':>7} {'trips':>5} {'ok':>4} {'cpu s':>8} {'wall s':>8} {'allocGB':>8} {'searches':>8} {'timedOut':>8} {'ahead tOut':>10} {'escal':>5} {'total ticks':>12} {'wait':>7}")
    for b in budgets:
        rows = [v[b] for v in by.values() if b in v]
        ss = [s for s in searches if (s["arm"].split("@", 1)[1] if "@" in s["arm"] else "") == b and s["type"] != "CANCEL_LATENCY"]
        print(f"{b:>7} {len(rows):>5} {sum(r['ok'] == 'true' for r in rows):>4} {sum(float(r['cpuMs']) for r in rows) / 1e3:>8.1f} "
              f"{sum(float(r['planMs']) for r in rows) / 1e3:>8.1f} {sum(float(r['allocMB']) for r in rows) / 1e3:>8.2f} {len(ss):>8} "
              f"{sum(s['timedOut'] == 'true' for s in ss):>8} {sum(s['timedOut'] == 'true' and s['ahead'] == 'true' for s in ss):>10} "
              f"{sum(int(r['escalations']) for r in rows):>5} {sum(float(r['total']) for r in rows if r['ok'] == 'true'):>12.0f} "
              f"{sum(float(r['wait']) for r in rows):>7.1f}")

    cpu = lambda side: (lambda p: float(p[side]["cpuMs"]))
    tot = lambda side: (lambda p: float(p[side]["total"]))
    for b in budgets:
        if b == base_name:
            continue
        pairs = [(v[base_name], v[b]) for v in by.values() if base_name in v and b in v]
        P = [{"B": x, "C": y} for x, y in pairs]
        lost = [p for p in P if p["B"]["ok"] == "true" and p["C"]["ok"] != "true"]
        gained = [p for p in P if p["B"]["ok"] != "true" and p["C"]["ok"] == "true"]
        both = [p for p in P if p["B"]["ok"] == "true" and p["C"]["ok"] == "true"]
        fails = (sum(p["C"]["ok"] != "true" for p in P), sum(p["B"]["ok"] != "true" for p in P))
        rc, (rcl, rch) = ratio(P, cpu("C"), cpu("B")), boot_ratio(P, cpu("C"), cpu("B"))
        rt, (rtl, rth) = ratio(both, tot("C"), tot("B")), boot_ratio(both, tot("C"), tot("B"))
        print(f"\n== {b} vs {base_name}: {len(P)} pairs, failures {fails[0]} vs {fails[1]}, lost {len(lost)}, gained {len(gained)}")
        for p in lost:
            print(f"   LOST {p['B']['family']} {p['B']['mode']} {p['B']['length']} n={p['B']['n']}: {p['C']['reason']}")
        print(f"   cpu   ratio {rc:.3f}  CI [{rcl:.3f}, {rch:.3f}]   (base {sum(cpu('B')(p) for p in P) / 1e3:.1f} s -> {sum(cpu('C')(p) for p in P) / 1e3:.1f} s)")
        print(f"   total ratio {rt:.4f} CI [{rtl:.4f}, {rth:.4f}]  over {len(both)} pairs both reached")
        fam = defaultdict(list)
        for p in P:
            fam[p["B"]["family"]].append(p)
        for f_, ps in sorted(fam.items()):
            bo = [p for p in ps if p["B"]["ok"] == "true" and p["C"]["ok"] == "true"]
            print(f"     {f_:<12} n={len(ps):>2} cpu {ratio(ps, cpu('C'), cpu('B')):6.3f} ({sum(cpu('B')(p) for p in ps) / 1e3:6.1f} s base)"
                  f"  total {ratio(bo, tot('C'), tot('B')):.4f}  fails {sum(p['C']['ok'] != 'true' for p in ps)}/{sum(p['B']['ok'] != 'true' for p in ps)}")
        g1 = len(lost) == 0
        g2 = rc <= 0.75 and rch < 0.90
        g3 = rt <= 1.02 and rth < 1.04 and all(
            ratio([p for p in ps if p["B"]["ok"] == "true" and p["C"]["ok"] == "true"], tot("C"), tot("B")) <= 1.05
            for ps in fam.values() if any(p["B"]["ok"] == "true" and p["C"]["ok"] == "true" for p in ps))
        gmax = g1 and rt <= 0.98 and rth < 1.00
        print(f"   ECO gates: G1 {'PASS' if g1 else 'FAIL'}  G2 {'PASS' if g2 else 'FAIL'}  G3 {'PASS' if g3 else 'FAIL'}   MAX gate: {'PASS' if gmax else 'FAIL'}")


if __name__ == "__main__":
    main()
