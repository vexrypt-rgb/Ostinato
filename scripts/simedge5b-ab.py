# Ostinato 2.0 Phase 05B: analyses arms=simedge5b live runs against the pre-registered horizon experiment
# (docs/ostinato2/BENCHMARKS.md, "Phase 05B", "Pre-registered experiment"). A legacy A*, B SimEdge horizon 32,
# C legacy A* (A/A'), D SimEdge horizon 8. Median and bootstrap as in scripts/simedge-ab.py.
# Usage: python scripts/simedge5b-ab.py <client log or LEGATOTEST lines, seed 1> [<seed 2>]
import random
import re
import statistics
import sys

ROW = re.compile(r"LEGATOTEST lap (\d+) rep=(\d+) leg=(\S+) mode=(\S) result=(\S+) ticks=(\d+) .*?"
                 r"faults=(\{[^}]*\}).*?planWait=(-?\d+) .*?simEdge=(\S+) horizon=(\d+).*? planUs=(\d+)")
PAIRS = (("D", "A"), ("D", "C"), ("D", "B"), ("B", "A"), ("C", "A"))


def boot_ci(xs, n=4000, seed=1):
    r = random.Random(seed)
    meds = sorted(statistics.median(r.choice(xs) for _ in xs) for _ in range(n))
    return meds[int(0.025 * n)], meds[int(0.975 * n)]


def load(path):
    laps, seen, lines = [], set(), []
    for line in open(path, encoding="utf-8", errors="replace"):
        if "LEGATOTEST" not in line:
            continue
        text = line[line.index("LEGATOTEST"):].strip()
        if text in seen:  # a line logged twice is one lap
            continue
        seen.add(text)
        lines.append(text)
        m = ROW.search(text)
        if m:
            g = m.groups()
            laps.append(dict(lap=int(g[0]), rep=int(g[1]), leg=g[2], mode=g[3], result=g[4], ticks=int(g[5]), faults=g[6],
                             wait=int(g[7]), sim=g[8] == "true", horizon=int(g[9]), planUs=int(g[10])))
    return laps, lines


def analyse(path):
    laps, lines = load(path)
    seed = next((re.search(r"seed=(\d+)", l).group(1) for l in lines if " start " in l and "seed=" in l), path)
    legs = list(dict.fromkeys(l["leg"] for l in laps))
    reps = sorted({l["rep"] for l in laps})
    by = {(l["leg"], l["rep"], l["mode"]): l for l in laps}
    out = {"seed": seed}
    print(f"\n# seed {seed}: {len(laps)} laps, legs {legs}, reps {len(reps)}")

    print("\n## Arms as run (simEdgeSearch, simEdgeHorizon per mode)")
    for mode in "ABCD":
        print(f"  {mode}: {sorted({(l['sim'], l['horizon']) for l in laps if l['mode'] == mode})}")
    arms_ok = all({(l["sim"], l["horizon"]) for l in laps if l["mode"] == m} == {want}
                  for m, want in (("A", (False, 32)), ("B", (True, 32)), ("C", (False, 32)), ("D", (True, 8))))
    print(f"  arms as pre-registered: {arms_ok}")

    print("\n## Reliability")
    reached, faults = {}, {}
    for mode in "ABCD":
        ls = [l for l in laps if l["mode"] == mode]
        reached[mode] = sum(l["result"] == "REACHED" for l in ls)
        faults[mode] = set()
        for l in ls:
            faults[mode] |= {f.split("=")[0].strip() for f in l["faults"].strip("{}").split(",") if f.strip()}
        print(f"  {mode}: {reached[mode]}/{len(ls)} reached; non-reached {[(l['leg'], l['rep'], l['result']) for l in ls if l['result'] != 'REACHED']};"
              f" fault codes {sorted(faults[mode])}")
    stuck_line = next((l for l in lines if "keys stuck after" in l), None)
    stuck = int(re.search(r"keys stuck after (\d+)", stuck_line).group(1)) if stuck_line else None
    held = [l for l in lines if "keys still held" in l or "keys held right after" in l]
    simline = next((l for l in lines if "simedge searches=" in l), None)
    exceptions = int(re.search(r"exceptions=(\d+)", simline).group(1)) if simline else None
    print(f"  stuck-key laps {stuck}, held-key lines {len(held)}; {simline}")
    rel = (arms_ok and reached["D"] >= reached["A"] and reached["D"] >= reached["C"] and stuck == 0 and not held
           and faults["D"] <= (faults["A"] | faults["C"]) and exceptions == 0)
    print(f"  reliability holds: {rel}")
    out["rel"] = rel

    print("\n## Paired lap ticks (reached pairs, by leg and rep): median ratio, bootstrap 95% CI")
    print("| leg | " + " | ".join(f"{a}/{b}" for a, b in PAIRS) + " |")
    print("|---|" + "---|" * len(PAIRS))
    med = {}
    for leg in legs:
        cells = []
        for arm, base in PAIRS:
            rs = [by[(leg, r, arm)]["ticks"] / by[(leg, r, base)]["ticks"] for r in reps
                  if (leg, r, arm) in by and (leg, r, base) in by and by[(leg, r, arm)]["result"] == by[(leg, r, base)]["result"] == "REACHED"]
            if not rs:
                cells.append("-")
                continue
            med[(leg, arm, base)] = statistics.median(rs)
            lo, hi = boot_ci(rs)
            cells.append(f"{statistics.median(rs):.3f} ({lo:.3f}–{hi:.3f}), n={len(rs)}")
        print(f"| {leg} | " + " | ".join(cells) + " |")
    out["med"] = med

    print("\n## Lap ticks, median per arm")
    for leg in legs:
        print(f"  {leg}: " + ", ".join(f"{m} {statistics.median(l['ticks'] for l in laps if l['leg'] == leg and l['mode'] == m and l['result'] == 'REACHED')}"
                                       for m in "ABCD"))

    print("\n## Mechanism: first-plan wait (ticks before the first path), median per arm; D − A must be ≤ 3 on every leg")
    mech = True
    waits = {}
    for leg in legs:
        w = {m: statistics.median(l["wait"] for l in laps if l["leg"] == leg and l["mode"] == m and l["wait"] >= 0) for m in "ABCD"}
        waits[leg] = w
        ok = w["D"] - w["A"] <= 3
        mech &= ok
        pu = {m: statistics.median(l["planUs"] for l in laps if l["leg"] == leg and l["mode"] == m) / 1000 for m in "ABCD"}
        print(f"  {leg}: planWait A {w['A']} B {w['B']} C {w['C']} D {w['D']} (D−A {w['D'] - w['A']:+}, {'ok' if ok else 'FAILS'});"
              f" planner ms per lap A {pu['A']:.0f} B {pu['B']:.0f} C {pu['C']:.0f} D {pu['D']:.0f}")
    print(f"  mechanism holds: {mech}")
    out["mech"] = mech

    print("\n## planWait by position in the leg block (same-start timeout escalation)")
    for mode in "ABCD":
        print(f"  {mode}: " + ", ".join(f"pos {k} {statistics.median([l['wait'] for l in laps if l['mode'] == mode and l['lap'] % 4 == k] or [-1])}"
                                         for k in range(4)))

    print("\n## Diagnostic: driving (lap ticks − first-plan wait), paired median ratio")
    for leg in legs:
        cells = []
        for arm, base in (("D", "A"), ("D", "B"), ("B", "A"), ("C", "A")):
            rs = [(by[(leg, r, arm)]["ticks"] - by[(leg, r, arm)]["wait"]) / (by[(leg, r, base)]["ticks"] - by[(leg, r, base)]["wait"]) for r in reps
                  if (leg, r, arm) in by and (leg, r, base) in by and by[(leg, r, arm)]["result"] == by[(leg, r, base)]["result"] == "REACHED"
                  and by[(leg, r, arm)]["wait"] >= 0 and by[(leg, r, base)]["wait"] >= 0]
            if rs:
                lo, hi = boot_ci(rs)
                cells.append(f"{arm}/{base} {statistics.median(rs):.3f} ({lo:.3f}–{hi:.3f})")
        print(f"  {leg}: " + "; ".join(cells))

    def measure(arm, base):
        """≤ 0.97 on ≥ 2 legs and no leg ≥ 1.03; a leg whose C/A noise exceeds 3% needs an effect beyond that noise."""
        good, bad, notes = 0, 0, []
        for leg in legs:
            r = med.get((leg, arm, base))
            if r is None:
                bad += 1
                notes.append(f"{leg} missing")
                continue
            noise = abs(med.get((leg, "C", "A"), 1.0) - 1)
            if r <= 0.97 and (noise <= 0.03 or 1 - r > noise):
                good += 1
            if r >= 1.03:
                bad += 1
                notes.append(f"{leg} {r:.3f} ≥ 1.03")
        return good >= 2 and bad == 0, good, notes

    for arm, base in (("D", "A"), ("D", "C"), ("D", "B")):
        ok, good, notes = measure(arm, base)
        out[f"m_{arm}{base}"] = ok
        print(f"  {arm}/{base}: legs ≤ 0.97 {good}, met {ok} {notes}")
    out["DB_bad"] = sum(med.get((leg, "D", "B"), 0) >= 1.03 for leg in legs)
    out["DA"] = {leg: med.get((leg, "D", "A")) for leg in legs}
    out["noise"] = {leg: med.get((leg, "C", "A")) for leg in legs}
    print("\n## Harness lines")
    for l in lines:
        if " paired " in l or "simedge searches" in l or "keys stuck" in l or " start " in l:
            print("  " + l)
    return out


def main(paths):
    sys.stdout.reconfigure(encoding="utf-8")
    res = [analyse(p) for p in paths]
    if len(res) < 2:
        print("\n(classification needs both seeds)")
        return
    rel = all(r["rel"] for r in res)
    m1 = all(r["m_DA"] and r["m_DC"] for r in res)
    m2 = all(r["m_DB"] for r in res)
    mech = all(r["mech"] for r in res)
    regress = not rel or all(r["DB_bad"] >= 2 for r in res)
    cls = "D" if regress else "A" if m1 else "B" if (m2 and mech) else "C"
    print("\n# Pre-registered classification (both seeds)")
    print(f"  reliability {rel}; M1 (D/A and D/C) {m1}; M2 (D/B) {m2}; mechanism {mech}; regression {regress} -> class {cls}")
    legs = list(res[0]["DA"])
    gain = [leg for leg in legs if all(r["DA"].get(leg) is not None and r["DA"][leg] <= 0.99 for r in res)]
    print(f"  archive rule: legs with D/A ≤ 0.99 in both seeds {gain} -> "
          f"{'keep SimEdge as disabled research code' if gain else 'recommend archiving SimEdge as a planner'}")


if __name__ == "__main__":
    main(sys.argv[1:])
