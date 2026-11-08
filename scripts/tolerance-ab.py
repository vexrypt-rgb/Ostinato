# Ostinato 2.0 Phase 04 validation: the predictionTolerance live A/B and tick-time overhead (B2, B3), judged against the
# pre-registered criteria in docs/ostinato2/BENCHMARKS.md ("Phase 04 live validation").
# Arms (LegatoLiveTest arms=tolerance): A off, B on, C off, D on. Replications: B/A and D/C. Noise: C/A.
# Usage: python scripts/tolerance-ab.py <seed-1 legatotest lines> <seed-2 legatotest lines>
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
    laps, stuck = [], []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = ROW.search(line)
        if m:
            kv = dict(KV.findall(m.group(2)))
            if "tickP95Us" not in kv:
                continue
            laps.append(dict(index=int(m.group(1)), rep=int(kv["rep"]), leg=kv["leg"], mode=kv["mode"], result=kv["result"],
                             ticks=int(kv["ticks"]), rewinds=int(kv["rewinds"]), cancels=int(kv["cancels"]),
                             faults=parse_map(kv["faults"]), drivers=parse_map(kv["drivers"]),
                             p50=int(kv["tickP50Us"]), p95=int(kv["tickP95Us"]), p99=int(kv["tickP99Us"]), tmax=int(kv["tickMaxUs"]),
                             physPlans=int(kv["physPlans"]), drift=int(kv["driftReplans"]), physUs=int(kv["physUs"]),
                             tolerance=kv["tolerance"] == "true", stack=kv["stack"]))
        elif "keys still held" in line or "keys held right after" in line:
            stuck.append(line.strip())
    return laps, stuck


def analyse(label, laps, stuck):
    print(f"\n# {label}: {len(laps)} laps, stacks {sorted({l['stack'] for l in laps})}")
    legs = list(dict.fromkeys(l["leg"] for l in laps))
    reps = sorted({l["rep"] for l in laps})
    by = {(l["leg"], l["rep"], l["mode"]): l for l in laps}
    flag_ok = all(l["tolerance"] == (l["mode"] in "BD") for l in laps)
    print(f"arm flag check (B, D on; A, C off): {'ok' if flag_ok else 'MISMATCH'}")

    print("\n## Reliability")
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
    rel = reached["B"] >= reached["A"] - 1 and reached["D"] >= reached["C"] - 1 and not stuck and not new_faults and not died
    print(f"stuck-key lines {len(stuck)}; deaths {len(died)}; fault codes only in B/D {sorted(new_faults)}")
    print(f"reliability: {'holds' if rel else 'FAILS'}")

    print("\n## Paired lap ticks (reached pairs), per leg: median ratio arm/base")
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
            # any leg at or over 1.03, noise or not, vetoes a completion-time improvement
        any_slow = [leg for leg in legs if ratios[(leg, pair)] is not None and ratios[(leg, pair)] >= SLOW]
        return fast, slow, any_slow

    calls = {}
    for pair in ("B/A", "D/C"):
        fast, slow, any_slow = leg_calls(pair)
        calls[pair] = dict(time=len(fast) >= 2 and not any_slow, slow=len(slow) >= 2)
        print(f"{pair}: legs <= 0.97 beyond noise {fast}; legs >= 1.03 beyond noise {slow}; legs >= 1.03 at all {any_slow}")

    print("\n## Recovery: per-lap cancels + rewinds, paired (on - off), all pairs")
    for base, arm in (("A", "B"), ("C", "D")):
        d = []
        for leg in legs:
            for rep in reps:
                a, b = by.get((leg, rep, base)), by.get((leg, rep, arm))
                if a and b:
                    d.append((b["cancels"] + b["rewinds"]) - (a["cancels"] + a["rewinds"]))
        if d:
            lo, hi = boot(d, statistics.mean)
            better = statistics.mean(d) < 0 and hi < 0
            print(f"{arm}/{base}: n={len(d)} mean {statistics.mean(d):+.3f} 95% CI {lo:+.3f}..{hi:+.3f} -> {'lower with flag on' if better else 'no improvement'}")
            calls[arm + "/" + base]["recovery"] = better
        else:
            calls[arm + "/" + base]["recovery"] = False
    calls["B/A"]["reached"] = reached["B"] > reached["A"]
    calls["D/C"]["reached"] = reached["D"] > reached["C"]
    print(f"strictly more reached: B>A {calls['B/A']['reached']}, D>C {calls['D/C']['reached']}")
    return dict(rel=rel, calls=calls, laps=laps)


def overhead(all_laps):
    print("\n# Overhead (B2): client tick time, paired on - off, same seed, leg and repetition, both runs pooled")
    by = {(l["seed"], l["leg"], l["rep"], l["mode"]): l for l in all_laps}
    verdicts = []
    for metric in ("p95", "p99", "p50", "tmax"):
        d = []
        for (seed, leg, rep, mode), l in by.items():
            if mode in "BD":
                base = by.get((seed, leg, rep, "A" if mode == "B" else "C"))
                if base:
                    d.append((l[metric] - base[metric]) / 1000.0)
        if not d:
            print(f"{metric}: no pairs")
            continue
        lo, hi = boot(d, statistics.median)
        gated = metric in ("p95", "p99")
        v = "acceptable" if hi <= OVERHEAD_MS else "FAIL" if lo > OVERHEAD_MS else "inconclusive"
        if gated:
            verdicts.append(v)
        off = statistics.median(l[metric] for l in all_laps if l["mode"] in "AC") / 1000.0
        on = statistics.median(l[metric] for l in all_laps if l["mode"] in "BD") / 1000.0
        print(f"tick {metric}: n={len(d)} median diff {statistics.median(d):+.3f} ms, 95% CI {lo:+.3f}..{hi:+.3f} ms; "
              f"median off {off:.2f} ms, on {on:.2f} ms" + (f" -> {v}" if gated else " (diagnostic)"))
    final = "FAIL" if "FAIL" in verdicts else "acceptable" if verdicts and all(v == "acceptable" for v in verdicts) else "inconclusive"
    print(f"overhead verdict: {final}")
    return final


def main(paths):
    results = []
    all_laps = []
    for i, p in enumerate(paths):
        laps, stuck = load(p)
        for l in laps:
            l["seed"] = i
        all_laps += laps
        results.append(analyse(p, laps, stuck))
    ov = overhead(all_laps)

    print("\n# A/B verdict (B3)")
    rel = all(r["rel"] for r in results)
    improve = {k: all(r["calls"][pair][k] for r in results for pair in ("B/A", "D/C")) for k in ("time", "recovery", "reached")}
    regress = not rel or all(r["calls"][pair]["slow"] for r in results for pair in ("B/A", "D/C"))
    print(f"reliability holds in every seed: {rel}")
    print(f"improvement in both replications and both seeds: completion time {improve['time']}, recovery {improve['recovery']}, reliability {improve['reached']}")
    verdict = "REGRESSION (FAIL)" if regress else "IMPROVEMENT" if any(improve.values()) else "no improvement"
    print(f"A/B verdict: {verdict}; overhead: {ov}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: python scripts/tolerance-ab.py <run-1 lines> [<run-2 lines> ...]")
    main(sys.argv[1:])
