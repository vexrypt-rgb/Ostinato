# Ostinato 2.0 Phase 07, Tier L: the pathingStateIdCache live A/B, judged against the pre-registered criteria LR, LP, LT
# and LO in docs/ostinato2/BENCHMARKS.md ("Phase 07: long-distance routing", pre-registration).
# Arms (LegatoLiveTest arms=stateids): A off, B on, C off, D on. Replications: B/A and D/C. Noise: C/A.
# Usage: python scripts/stateids-ab.py <seed-1 legatotest lines> [<seed-2 legatotest lines> ...]
import random
import re
import statistics
import sys

ROW = re.compile(r"LEGATOTEST lap (\d+) (.*)")
KV = re.compile(r"(\w+)=(\{[^}]*\}|\S+)")
RESAMPLES = 10000
BOOT_SEED = 20261108
OVERHEAD_MS = 0.5
LAP_TICKS_MAX = 1.03


def boot(xs, stat):
    if len(xs) < 2:
        return float("nan"), float("nan")
    r = random.Random(BOOT_SEED)
    s = sorted(stat([r.choice(xs) for _ in xs]) for _ in range(RESAMPLES))
    return s[int(0.025 * RESAMPLES)], s[int(0.975 * RESAMPLES) - 1]


def parse_map(s):
    out = {}
    for part in filter(None, (p.strip() for p in s.strip("{}").split(","))):
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
            if "tickP95Us" not in kv or "stateIds" not in kv:
                continue
            laps.append(dict(rep=int(kv["rep"]), leg=kv["leg"], mode=kv["mode"], result=kv["result"], ticks=int(kv["ticks"]),
                             faults=parse_map(kv["faults"]), p50=int(kv["tickP50Us"]), p95=int(kv["tickP95Us"]),
                             p99=int(kv["tickP99Us"]), tmax=int(kv["tickMaxUs"]), planUs=int(kv["planUs"]),
                             calcs=int(kv["calcs"]), allocKB=int(kv["allocKB"]), stateIds=kv["stateIds"] == "true",
                             stack=kv["stack"]))
        elif "keys still held" in line or "keys held right after" in line:
            stuck.append(line.strip())
    return laps, stuck


def pairs(laps, metric, reached_only):
    by = {(l["seed"], l["leg"], l["rep"], l["mode"]): l for l in laps}
    out = {"B/A": [], "D/C": [], "C/A": []}
    for (seed, leg, rep, mode), l in by.items():
        for arm, base in (("B", "A"), ("D", "C"), ("C", "A")):
            if mode != arm:
                continue
            b = by.get((seed, leg, rep, base))
            if not b or (reached_only and not (l["result"] == b["result"] == "REACHED")):
                continue
            out[arm + "/" + base].append((b, l))
    return out


def reliability(label, laps, stuck):
    print(f"\n# {label}: {len(laps)} laps, stacks {sorted({l['stack'] for l in laps})}")
    flag_ok = all(l["stateIds"] == (l["mode"] in "BD") for l in laps)
    print(f"arm flag check (B, D on; A, C off): {'ok' if flag_ok else 'MISMATCH'}")
    reached, faults = {}, {}
    for mode in "ABCD":
        ls = [l for l in laps if l["mode"] == mode]
        reached[mode] = sum(l["result"] == "REACHED" for l in ls)
        faults[mode] = set().union(*[set(l["faults"]) for l in ls]) if ls else set()
        print(f"mode {mode}: {reached[mode]}/{len(ls)} reached; not reached "
              f"{[(l['leg'], l['rep'], l['result']) for l in ls if l['result'] != 'REACHED']}; faults {sorted(faults[mode])}")
    died = [l for l in laps if l["result"] == "DIED"]
    new_faults = (faults["B"] | faults["D"]) - (faults["A"] | faults["C"])
    ok = (flag_ok and reached["B"] >= reached["A"] - 1 and reached["D"] >= reached["C"] - 1 and not stuck
          and not new_faults and not died)
    print(f"stuck-key lines {len(stuck)}; deaths {len(died)}; fault codes only in B/D {sorted(new_faults)}")
    print(f"LR: {'holds' if ok else 'FAILS'}")
    return ok


def ratio_block(name, all_laps, metric, reached_only):
    ps = pairs(all_laps, metric, reached_only)
    res = {}
    for k in ("B/A", "D/C", "C/A"):
        rs = [l[metric] / b[metric] for b, l in ps[k] if b[metric] > 0]
        lo, hi = boot(rs, statistics.median)
        res[k] = rs
        print(f"{name} {k}: n={len(rs)} median {statistics.median(rs) if rs else float('nan'):.4f} 95% CI {lo:.4f}..{hi:.4f}")
    pooled = res["B/A"] + res["D/C"]
    lo, hi = boot(pooled, statistics.median)
    med = statistics.median(pooled) if pooled else float("nan")
    print(f"{name} pooled on/off: n={len(pooled)} median {med:.4f} 95% CI {lo:.4f}..{hi:.4f}")
    return med, lo, hi


def overhead(all_laps):
    print("\n# LO overhead: client tick time, paired on - off, same seed, leg and repetition, both seeds pooled")
    ps = pairs(all_laps, None, False)
    verdicts = []
    for metric in ("p95", "p99", "p50", "tmax"):
        d = [(l[metric] - b[metric]) / 1000.0 for k in ("B/A", "D/C") for b, l in ps[k]]
        lo, hi = boot(d, statistics.median)
        gated = metric in ("p95", "p99")
        v = "acceptable" if hi <= OVERHEAD_MS else "FAIL" if lo > OVERHEAD_MS else "inconclusive"
        if gated:
            verdicts.append(v)
        print(f"{metric}: n={len(d)} median diff {statistics.median(d):+.3f} ms, 95% CI {lo:+.3f}..{hi:+.3f} ms"
              + (f" -> {v}" if gated else " (diagnostic)"))
    final = "FAIL" if "FAIL" in verdicts else "acceptable" if all(v == "acceptable" for v in verdicts) else "inconclusive"
    print(f"LO: {final}")
    return final


def main(paths):
    all_laps, rel = [], []
    for i, p in enumerate(paths):
        laps, stuck = load(p)
        for l in laps:
            l["seed"] = i
        all_laps += laps
        rel.append(reliability(p, laps, stuck))
    print("\n# LP live A* planning time per lap (planUs), paired on/off")
    lp = ratio_block("planUs", all_laps, "planUs", False)
    print("\n# LT lap ticks, reached pairs, paired on/off")
    lt = ratio_block("ticks", all_laps, "ticks", True)
    lo = overhead(all_laps)
    print("\n# Tier L verdict")
    lr = all(rel)
    lp_ok = lp[2] < 1.0
    lt_ok = lt[2] <= LAP_TICKS_MAX
    print(f"LR reliability in every seed: {lr}")
    print(f"LP planning lower live (pooled CI upper < 1.0): {lp_ok} (median {lp[0]:.4f}, CI {lp[1]:.4f}..{lp[2]:.4f})")
    print(f"LT lap ticks not longer (pooled CI upper <= {LAP_TICKS_MAX}): {lt_ok} (median {lt[0]:.4f}, CI {lt[1]:.4f}..{lt[2]:.4f})")
    print(f"LO overhead: {lo}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: python scripts/stateids-ab.py <run-1 lines> [<run-2 lines> ...]")
    main(sys.argv[1:])
