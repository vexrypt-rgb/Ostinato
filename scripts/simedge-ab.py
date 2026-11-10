# Ostinato 2.0 Phase 05: analyses a legatotest-result.txt from an arms=simedge live run against the pre-registered gate
# (docs/ostinato2/BENCHMARKS.md, "Phase 05"). Usage: python scripts/simedge-ab.py <legatotest-result.txt> [seed label]
import random
import re
import statistics
import sys

ROW = re.compile(r"LEGATOTEST lap (\d+) rep=(\d+) leg=(\S+) mode=(\S) result=(\S+) ticks=(\d+) .*?turned=(\d+) .*?"
                 r"faults=(\{[^}]*\}).*?planWait=(-?\d+) predLegacy=(\S+) predSim=(\S+) complete=(\S+) links=(\d+) "
                 r"simLinks=(\d+) jumps=(\d+) simSearches=(\d+) simFallbacks=(\d+)(?: segTicks=(-?\d+))?")


def boot_ci(diffs, n=4000, seed=1):
    r = random.Random(seed)
    meds = sorted(statistics.median(r.choice(diffs) for _ in diffs) for _ in range(n))
    return meds[int(0.025 * n)], meds[int(0.975 * n)]


def main(path):
    laps = []
    stuck = []
    other = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = ROW.search(line)
        if m:
            g = m.groups()
            laps.append(dict(rep=int(g[1]), leg=g[2], mode=g[3], result=g[4], ticks=int(g[5]), turned=int(g[6]),
                             faults=g[7], wait=int(g[8]), pl=float(g[9]), ps=float(g[10]), complete=g[11] == "true",
                             links=int(g[12]), sim=int(g[13]), jumps=int(g[14]), searches=int(g[15]), fallbacks=int(g[16]),
                             seg=int(g[17]) if g[17] is not None else -1))
        elif "keys still held" in line or "keys held right after" in line:
            stuck.append(line.strip())
        elif "simedge searches" in line or "paired" in line or "summary" in line:
            other.append(line.strip())
    legs = list(dict.fromkeys(l["leg"] for l in laps))
    print(f"laps {len(laps)}, legs {legs}")
    print("\n## Reliability")
    for mode in "ABCD":
        ls = [l for l in laps if l["mode"] == mode]
        ok = sum(l["result"] == "REACHED" for l in ls)
        faults = sorted({l["faults"] for l in ls if l["faults"] != "{}"})
        print(f"mode {mode}: {ok}/{len(ls)} reached; non-reached {[ (l['leg'], l['rep'], l['result']) for l in ls if l['result'] != 'REACHED']}; "
              f"faults {faults}; searches {sum(l['searches'] for l in ls)} fallbacks {sum(l['fallbacks'] for l in ls)}")
    print(f"stuck-key lines: {len(stuck)}")
    for s in stuck[:5]:
        print("  " + s)

    def key(l):
        return (l["leg"], l["rep"], l["mode"])
    by = {key(l): l for l in laps}
    print("\n## Paired lap ticks (reached pairs)")
    print("| leg | pair | n | median ratio | 95% CI | medians base/arm | turned base/arm | jumps base/arm |")
    print("|---|---|---|---|---|---|---|---|")
    for leg in legs:
        for base, arm in (("A", "B"), ("C", "D"), ("A", "C")):
            rs, tb, ta, jb, ja, kb, ka = [], [], [], [], [], [], []
            for rep in sorted({l["rep"] for l in laps}):
                a, b = by.get((leg, rep, base)), by.get((leg, rep, arm))
                if a and b and a["result"] == b["result"] == "REACHED":
                    rs.append(b["ticks"] / a["ticks"])
                    tb.append(a["ticks"]); ta.append(b["ticks"])
                    kb.append(a["turned"]); ka.append(b["turned"])
                    jb.append(a["jumps"]); ja.append(b["jumps"])
            if not rs:
                print(f"| {leg} | {arm}/{base} | 0 | - | - | - | - | - |")
                continue
            lo, hi = boot_ci(rs)
            print(f"| {leg} | {arm}/{base} | {len(rs)} | {statistics.median(rs):.3f} | {lo:.3f}..{hi:.3f} | "
                  f"{statistics.median(tb)}/{statistics.median(ta)} | {statistics.median(kb):.0f}/{statistics.median(ka):.0f} | "
                  f"{statistics.median(jb)}/{statistics.median(ja)} |")

    # Diagnostic, not a gate measure: how much of a ticks difference is the first plan's wait.
    print("\n## Diagnostic: paired ratio of (lap ticks - first planWait), and median first planWait base/arm")
    print("| leg | pair | n | median ratio | 95% CI | planWait base/arm |")
    print("|---|---|---|---|---|---|")
    for leg in legs:
        for base, arm in (("A", "B"), ("C", "D")):
            rs, wb, wa = [], [], []
            for rep in sorted({l["rep"] for l in laps}):
                a, b = by.get((leg, rep, base)), by.get((leg, rep, arm))
                if a and b and a["result"] == b["result"] == "REACHED" and a["wait"] >= 0 and b["wait"] >= 0:
                    rs.append((b["ticks"] - b["wait"]) / (a["ticks"] - a["wait"]))
                    wb.append(a["wait"]); wa.append(b["wait"])
            if rs:
                lo, hi = boot_ci(rs)
                print(f"| {leg} | {arm}/{base} | {len(rs)} | {statistics.median(rs):.3f} | {lo:.3f}..{hi:.3f} | {statistics.median(wb)}/{statistics.median(wa)} |")

    print("\n## Planning accuracy (complete first plans of reached laps; actual = lap ticks - planWait)")
    print("| set | n | legacy model median rel. error | sim model median rel. error | median paired difference | 95% CI |")
    print("|---|---|---|---|---|---|")
    for label, modes in (("all arms", "ABCD"), ("legacy arms A,C", "AC"), ("sim arms B,D", "BD"), ("own model per arm", None)):
        el, es, d = [], [], []
        for l in laps:
            if l["result"] != "REACHED" or not l["complete"] or l["wait"] < 0 or l["pl"] != l["pl"]:
                continue
            if modes is not None and l["mode"] not in modes:
                continue
            act = l["ticks"] - l["wait"]
            a, b = abs(l["pl"] - act) / act, abs(l["ps"] - act) / act
            if modes is None:  # each arm judged by the model it planned with
                (el if l["mode"] in "AC" else es).append(a if l["mode"] in "AC" else b)
                continue
            el.append(a); es.append(b); d.append(a - b)
        if modes is None:
            print(f"| {label} | {len(el)}+{len(es)} | {statistics.median(el):.3f} | {statistics.median(es):.3f} | - | - |" if el and es else f"| {label} | 0 | - | - | - | - |")
            continue
        if not d:
            print(f"| {label} | 0 | - | - | - | - |")
            continue
        lo, hi = boot_ci(d)
        print(f"| {label} | {len(d)} | {statistics.median(el):.3f} | {statistics.median(es):.3f} | {statistics.median(d):.3f} | {lo:.3f}..{hi:.3f} |")
    # Added before any data was analysed: the first plan is usually partial, so the complete-plan table can be empty.
    # Here actual = ticks from the first plan to the feet first standing in that plan's last cell.
    print("\n## Planning accuracy, first segment (every lap whose feet reached the first plan's last cell; actual = segTicks - planWait)")
    print("| set | n | legacy model median rel. error | sim model median rel. error | median paired difference | 95% CI |")
    print("|---|---|---|---|---|---|")
    def seg_ok(l):
        return l["seg"] > l["wait"] >= 0 and l["pl"] == l["pl"]
    for label, modes in (("all arms", "ABCD"), ("legacy arms A,C", "AC"), ("sim arms B,D", "BD")):
        el, es, d = [], [], []
        for l in laps:
            if not seg_ok(l) or l["mode"] not in modes:
                continue
            act = l["seg"] - l["wait"]
            a, b = abs(l["pl"] - act) / act, abs(l["ps"] - act) / act
            el.append(a); es.append(b); d.append(a - b)
        if not d:
            print(f"| {label} | 0 | - | - | - | - |")
            continue
        lo, hi = boot_ci(d)
        print(f"| {label} | {len(d)} | {statistics.median(el):.3f} | {statistics.median(es):.3f} | {statistics.median(d):.3f} | {lo:.3f}..{hi:.3f} |")
    print("per leg (all arms): n, legacy / sim median rel. error (signed bias)")
    for leg in legs:
        ls = [l for l in laps if l["leg"] == leg and seg_ok(l)]
        if not ls:
            continue
        act = [l["seg"] - l["wait"] for l in ls]
        el = [abs(l["pl"] - a) / a for l, a in zip(ls, act)]
        es = [abs(l["ps"] - a) / a for l, a in zip(ls, act)]
        lb = [(l["pl"] - a) / a for l, a in zip(ls, act)]
        sb = [(l["ps"] - a) / a for l, a in zip(ls, act)]
        print(f"  {leg}: n={len(ls)} legacy {statistics.median(el):.3f} ({statistics.median(lb):+.3f}) sim {statistics.median(es):.3f} ({statistics.median(sb):+.3f})")

    print("\nper leg (all arms): legacy / sim median rel. error, sim share of links")
    for leg in legs:
        ls = [l for l in laps if l["leg"] == leg and l["result"] == "REACHED" and l["complete"] and l["wait"] >= 0]
        if not ls:
            continue
        el = [abs(l["pl"] - (l["ticks"] - l["wait"])) / (l["ticks"] - l["wait"]) for l in ls]
        es = [abs(l["ps"] - (l["ticks"] - l["wait"])) / (l["ticks"] - l["wait"]) for l in ls]
        sb = [(l["ps"] - (l["ticks"] - l["wait"])) / (l["ticks"] - l["wait"]) for l in ls]
        lb = [(l["pl"] - (l["ticks"] - l["wait"])) / (l["ticks"] - l["wait"]) for l in ls]
        share = sum(l["sim"] for l in ls) / max(1, sum(l["links"] for l in ls))
        print(f"  {leg}: n={len(ls)} legacy {statistics.median(el):.3f} (bias {statistics.median(lb):+.3f}) sim {statistics.median(es):.3f} "
              f"(bias {statistics.median(sb):+.3f}) simulated links {share:.2f}")
    print("\n## Harness lines")
    for o in other:
        print("  " + o)


if __name__ == "__main__":
    main(sys.argv[1])
