# Ostinato 2.0 Phase 06, Tier L: the unifiedExecutor live A/B, judged against the pre-registered criteria L1-L6 in
# docs/ostinato2/BENCHMARKS.md ("Phase 06 pre-registration"), and Phase 06A's L2' (ownership across lap boundaries,
# from the run totals) in "Phase 06A: executor safety and reliability recovery".
# Arms (LegatoLiveTest arms=unified): A off, B on, C off, D on. Replications: B/A and D/C. Noise: C/A.
# Usage: python scripts/unified-ab.py <seed-1 legatotest lines> <seed-2 legatotest lines>
import random
import re
import statistics
import sys

ROW = re.compile(r"LEGATOTEST lap (\d+) (.*)")
KV = re.compile(r"(\w+)=(\{[^}]*\}|\S+)")
RESAMPLES = 10000
BOOT_SEED = 20261108
OVERHEAD_MS = 0.5
FAST, SLOW = 0.97, 1.03
SAFETY = ("RELEASED_EARLY", "OVERWRITTEN", "STALE_REJECTS")
SHOWN = ("ACQUIRES", "COMPLETIONS", "HANDOVERS", "FAILURES", "CANCELS", "CANCEL_STOPS", "PREEMPTIONS", "UNKNOWNS",
         "CONFLICTS", "ERASED", "OVERWRITTEN", "RELEASED_EARLY", "STALE_REJECTS", "DIVERGENCE_CHECKS", "DIVERGENCES",
         "PREDICTION_UNAVAILABLE", "CORRECTIONS", "CORRECTIONS_ACCEPTED", "ESCALATIONS", "PROCESS_CANCELS",
         "CORRECTION_RETRIES", "CORRECTION_RETRY_PLANS")
# Phase 06A, L2': these run totals must be 0 (they include the ticks between laps, which the lap rows do not).
GAP_SAFETY = ("RELEASED_EARLY", "OVERWRITTEN", "STALE_REJECTS", "UNKNOWNS")


def boot(diffs, stat):
    r = random.Random(BOOT_SEED)
    s = sorted(stat([r.choice(diffs) for _ in diffs]) for _ in range(RESAMPLES))
    return s[int(0.025 * RESAMPLES)], s[int(0.975 * RESAMPLES) - 1]


def parse_map(s):
    s = s.strip("{}")
    out = {}
    for part in filter(None, (p.strip() for p in s.split(","))):
        k, _, v = part.partition("=")
        try:
            out[k] = int(v)
        except ValueError:
            out[k] = v
    return out


def load(path):
    laps, stuck, totals = [], [], {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = ROW.search(line)
        if m:
            kv = dict(KV.findall(m.group(2)))
            if "tickP95Us" not in kv or "unified" not in kv:
                continue
            laps.append(dict(index=int(m.group(1)), rep=int(kv["rep"]), leg=kv["leg"], mode=kv["mode"], result=kv["result"],
                             ticks=int(kv["ticks"]), rewinds=int(kv["rewinds"]), cancels=int(kv["cancels"]),
                             faults=parse_map(kv["faults"]), drivers=parse_map(kv["drivers"]),
                             p50=int(kv["tickP50Us"]), p95=int(kv["tickP95Us"]), p99=int(kv["tickP99Us"]), tmax=int(kv["tickMaxUs"]),
                             physPlans=int(kv["physPlans"]), drift=int(kv["driftReplans"]), physUs=int(kv["physUs"]),
                             tolerance=kv["tolerance"] == "true", unified=kv["unified"] == "true", ux=parse_map(kv["ux"]),
                             stack=kv["stack"], allocKB=int(kv["allocKB"])))
        elif "keys still held" in line or "keys held right after" in line:
            stuck.append(line.strip())
        elif "unified totals" in line:
            totals = {k: int(v) for k, v in re.findall(r"([A-Z_]+)=(\d+)", line.split("unified totals", 1)[1])}
    return laps, stuck, totals


def analyse(label, laps, stuck, totals):
    print(f"\n# {label}: {len(laps)} laps, stacks {sorted({l['stack'] for l in laps})}")
    legs = list(dict.fromkeys(l["leg"] for l in laps))
    reps = sorted({l["rep"] for l in laps})
    by = {(l["leg"], l["rep"], l["mode"]): l for l in laps}
    flag_ok = all(l["unified"] == (l["mode"] in "BD") for l in laps)
    tol_off = not any(l["tolerance"] for l in laps)
    print(f"arm flag check (B, D on; A, C off): {'ok' if flag_ok else 'MISMATCH'}; predictionTolerance off in every lap: {tol_off}")

    print("\n## L1 reliability")
    reached = {}
    faults = {}
    for mode in "ABCD":
        ls = [l for l in laps if l["mode"] == mode]
        reached[mode] = sum(l["result"] == "REACHED" for l in ls)
        faults[mode] = set().union(*[set(l["faults"]) for l in ls]) if ls else set()
        drv = {}
        for l in ls:
            for k, v in l["drivers"].items():
                if isinstance(v, int):
                    drv[k] = drv.get(k, 0) + v
        print(f"mode {mode}: {reached[mode]}/{len(ls)} reached; not reached {[(l['leg'], l['rep'], l['result']) for l in ls if l['result'] != 'REACHED']}; "
              f"faults {sorted(faults[mode])}; physPlans {sum(l['physPlans'] for l in ls)} driftReplans {sum(l['drift'] for l in ls)}; driver ticks {drv}")
    died = [l for l in laps if l["result"] == "DIED"]
    new_faults = (faults["B"] | faults["D"]) - (faults["A"] | faults["C"])
    rel = flag_ok and tol_off and reached["B"] >= reached["A"] - 1 and reached["D"] >= reached["C"] - 1 and not stuck and not new_faults and not died
    print(f"stuck-key lines {len(stuck)} {stuck[:5]}; deaths {len(died)}; fault codes only in B/D {sorted(new_faults)}")
    print(f"L1: {'holds' if rel else 'FAILS'}")

    print("\n## L2 ownership safety (unified counters per lap)")
    viol = [(l["leg"], l["rep"], l["mode"], {k: l["ux"].get(k, 0) for k in SAFETY}) for l in laps
            if l["mode"] in "BD" and any(l["ux"].get(k, 0) for k in SAFETY)]
    leaked = [(l["leg"], l["rep"], l["mode"], l["ux"]) for l in laps if l["mode"] in "AC" and any(k != "CANCEL_LATENCY_MAX" for k in l["ux"])]
    sums = {}
    for mode in "BD":
        ls = [l for l in laps if l["mode"] == mode]
        sums[mode] = {k: sum(l["ux"].get(k, 0) for l in ls) for k in SHOWN}
        print(f"mode {mode}: " + " ".join(f"{k}={v}" for k, v in sums[mode].items() if v))
    owned = sum(l["ux"].get("OWNED_TICKS", 0) for l in laps if l["mode"] in "BD")
    intact = sum(l["ux"].get("INTACT_TICKS", 0) for l in laps if l["mode"] in "BD")
    print(f"owned ticks {owned}, intact {intact}; executor counters moved with the flag off: {len(leaked)} {leaked[:5]}")
    if totals:
        print("run totals: " + " ".join(f"{k}={v}" for k, v in totals.items() if v))
    bounded = all(l["ux"].get("CORRECTIONS", 0) <= l["physPlans"] and l["ux"].get("ESCALATIONS", 0) == l["drift"]
                  for l in laps if l["mode"] in "BD")
    l2 = not viol and not leaked and owned > 0 and bounded
    print(f"safety violations {len(viol)} {viol[:10]}; corrections bounded and escalations == driftReplans in every lap: {bounded}")
    print(f"L2: {'holds' if l2 else 'FAILS'}" + ("" if owned > 0 else " (the executor never owned a tick: nothing was tested)"))

    print("\n## L2' (Phase 06A) ownership across lap boundaries, from the run totals")
    if not totals:
        l2p = "FAILS"
        print("no 'unified totals' line in the log: the run did not finish")
    else:
        bad = {k: totals.get(k, 0) for k in GAP_SAFETY if totals.get(k, 0)}
        pc = totals.get("PROCESS_CANCELS", 0)
        l2p = "FAILS" if bad or stuck else "holds" if pc > 0 else "not exercised"
        print(f"run totals {', '.join(f'{k}={totals.get(k, 0)}' for k in GAP_SAFETY)}; PROCESS_CANCELS={pc}; stuck-key lines {len(stuck)}")
        lapsum = {k: sum(l["ux"].get(k, 0) for l in laps) for k in totals if k != "CANCEL_LATENCY_MAX"}
        gap = {k: totals[k] - v for k, v in lapsum.items() if totals[k] - v}
        print(f"diagnostic, run total - lap sum (events between laps): {gap}")
    rt = sum(l["ux"].get("CORRECTION_RETRIES", 0) for l in laps if l["mode"] in "BD")
    rp = sum(l["ux"].get("CORRECTION_RETRY_PLANS", 0) for l in laps if l["mode"] in "BD")
    print(f"diagnostic, B/D laps: CORRECTION_RETRIES {rt}, CORRECTION_RETRY_PLANS {rp}")
    print(f"L2': {l2p}")

    print("\n## L3 paired lap ticks (reached pairs), per leg: median ratio arm/base")
    print("| leg | B/A n | B/A | D/C n | D/C | C/A (A/A' noise) |")
    print("|---|---|---|---|---|---|")
    ratios = {}
    for leg in legs:
        row = [leg]
        for base, arm in (("A", "B"), ("C", "D"), ("A", "C")):
            rs = []
            for rep in reps:
                a, b = by.get((leg, rep, base)), by.get((leg, rep, arm))
                if a and b and a["result"] == b["result"] == "REACHED":
                    rs.append(b["ticks"] / a["ticks"])
            ratios[(leg, arm + "/" + base)] = statistics.median(rs) if rs else None
            if arm + "/" + base != "C/A":
                row.append(str(len(rs)))
            row.append(f"{statistics.median(rs):.3f}" if rs else "-")
        print("| " + " | ".join(row) + " |")

    def leg_calls(pair):
        fast, slow = [], []
        for leg in legs:
            r, noise = ratios[(leg, pair)], ratios[(leg, "C/A")]
            spread = abs(noise - 1) if noise is not None else 0.0
            if r is None:
                continue
            if r <= FAST and 1 - r > spread:
                fast.append(leg)
            if r >= SLOW and r - 1 > spread:
                slow.append(leg)
        any_slow = [leg for leg in legs if ratios[(leg, pair)] is not None and ratios[(leg, pair)] >= SLOW]
        return fast, slow, any_slow

    calls = {}
    for pair in ("B/A", "D/C"):
        fast, slow, any_slow = leg_calls(pair)
        calls[pair] = dict(time=len(fast) >= 2 and not any_slow, slow=len(slow) >= 2)
        print(f"{pair}: legs <= 0.97 beyond noise {fast}; legs >= 1.03 beyond noise {slow}; legs >= 1.03 at all {any_slow}")

    print("\n## L4 replans and recovery, paired (on - off) per lap, all pairs")
    for base, arm in (("A", "B"), ("C", "D")):
        pair = arm + "/" + base
        for name, f in (("physPlans", lambda l: l["physPlans"]), ("driftReplans", lambda l: l["drift"]),
                        ("cancels+rewinds", lambda l: l["cancels"] + l["rewinds"])):
            d = []
            for leg in legs:
                for rep in reps:
                    a, b = by.get((leg, rep, base)), by.get((leg, rep, arm))
                    if a and b:
                        d.append(f(b) - f(a))
            if d:
                lo, hi = boot(d, statistics.mean)
                better = statistics.mean(d) < 0 and hi < 0
                worse = lo > 0
                print(f"{pair} {name}: n={len(d)} mean {statistics.mean(d):+.3f} 95% CI {lo:+.3f}..{hi:+.3f} -> "
                      f"{'lower with flag on' if better else 'HIGHER with flag on' if worse else 'no difference shown'}")
                calls[pair][name] = better
                calls[pair][name + "_worse"] = worse
            else:
                calls[pair][name] = False
                calls[pair][name + "_worse"] = False
    calls["B/A"]["reached"] = reached["B"] > reached["A"]
    calls["D/C"]["reached"] = reached["D"] > reached["C"]
    print(f"strictly more reached: B>A {calls['B/A']['reached']}, D>C {calls['D/C']['reached']}")
    return dict(rel=rel, safe=l2, gap=l2p, calls=calls, laps=laps)


def overhead(all_laps):
    print("\n# L5 overhead: client tick time, paired on - off, same seed, leg and repetition, both runs pooled")
    by = {(l["seed"], l["leg"], l["rep"], l["mode"]): l for l in all_laps}
    verdicts = []
    for metric in ("p95", "p99", "p50", "tmax", "allocKB"):
        d = []
        for (seed, leg, rep, mode), l in by.items():
            if mode in "BD":
                base = by.get((seed, leg, rep, "A" if mode == "B" else "C"))
                if base:
                    d.append((l[metric] - base[metric]) / (1.0 if metric == "allocKB" else 1000.0))
        if not d:
            print(f"{metric}: no pairs")
            continue
        lo, hi = boot(d, statistics.median)
        unit = "KB" if metric == "allocKB" else "ms"
        gated = metric in ("p95", "p99")
        v = "acceptable" if hi <= OVERHEAD_MS else "FAIL" if lo > OVERHEAD_MS else "inconclusive"
        if gated:
            verdicts.append(v)
        s = 1.0 if metric == "allocKB" else 1000.0
        off = statistics.median(l[metric] for l in all_laps if l["mode"] in "AC") / s
        on = statistics.median(l[metric] for l in all_laps if l["mode"] in "BD") / s
        print(f"{metric}: n={len(d)} median diff {statistics.median(d):+.3f} {unit}, 95% CI {lo:+.3f}..{hi:+.3f} {unit}; "
              f"median off {off:.2f} {unit}, on {on:.2f} {unit}" + (f" -> {v}" if gated else " (diagnostic)"))
    final = "FAIL" if "FAIL" in verdicts else "acceptable" if verdicts and all(v == "acceptable" for v in verdicts) else "inconclusive"
    print(f"L5: {final}")
    return final


def main(paths):
    results = []
    all_laps = []
    for i, p in enumerate(paths):
        laps, stuck, totals = load(p)
        for l in laps:
            l["seed"] = i
        all_laps += laps
        results.append(analyse(p, laps, stuck, totals))
    ov = overhead(all_laps)

    print("\n# Tier L verdict")
    rel = all(r["rel"] for r in results)
    safe = all(r["safe"] for r in results)
    pairs = ("B/A", "D/C")
    keys = ("time", "physPlans", "driftReplans", "cancels+rewinds", "reached")
    improve = {k: all(r["calls"][p][k] for r in results for p in pairs) for k in keys}
    slow = all(r["calls"][p]["slow"] for r in results for p in pairs)
    more_plans = all(r["calls"][p]["physPlans_worse"] for r in results for p in pairs)
    print(f"L1 reliability in every seed: {rel}; L2 ownership safety in every seed: {safe}")
    print("improvement in both replications and both seeds: " + ", ".join(f"{k} {v}" for k, v in improve.items()))
    print(f"L3 slower in both replications and both seeds: {slow}; more plans in both replications and both seeds: {more_plans}")
    regress = not rel or not safe or slow or more_plans
    verdict = "FAIL" if regress or ov == "FAIL" else "IMPROVEMENT" if any(improve.values()) else "no improvement, no regression"
    print(f"Tier L verdict: {verdict}; L5 overhead: {ov}; L6: live cancel latency not measured (cancels come only at lap end)")
    gaps = [r["gap"] for r in results]
    print(f"L2' per seed: {gaps}; holds and exercised in every seed: {all(g == 'holds' for g in gaps)}"
          f"; any FAILS: {'FAILS' in gaps}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: python scripts/unified-ab.py <run-1 lines> [<run-2 lines> ...]")
    main(sys.argv[1:])
