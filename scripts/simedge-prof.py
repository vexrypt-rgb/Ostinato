#!/usr/bin/env python3
"""Ostinato 2.0 Phase 05B: digest of a live run's planning profile (-Dostinato.simedge.profile=true).

Reads the client log and prints, per mode and leg, the first plan's wait and planner time, and per SimEdge search
where the time went (simulation by edge kind, legacy move evaluation, the rest) with expansions and allocation.
Usage: python scripts/simedge-prof.py <client log>
"""
import re
import statistics as st
import sys
from collections import defaultdict


def kv(line):
    return dict(re.findall(r"(\w+)=(\S+)", line))


def main(path):
    laps, prof, plan = [], [], []
    for line in open(path, encoding="utf-8", errors="replace"):
        if "LEGATOTEST lap " in line:
            d = kv(line[line.index("LEGATOTEST lap "):])
            d["lap"] = int(re.search(r"LEGATOTEST lap (\d+)", line).group(1))
            d["simprof"], d["planprof"] = prof, plan
            laps.append(d)
            prof, plan = [], []
        elif "SIMEDGEPROF" in line:
            prof.append(kv(line[line.index("SIMEDGEPROF"):]))
        elif "PLANPROF" in line:
            plan.append(kv(line[line.index("PLANPROF"):]))
    print(f"{len(laps)} laps")
    g = defaultdict(list)
    for d in laps:
        sim = d["mode"] in "BD"
        g[(d["leg"], sim)].append(d)
    print("\nleg      planner  n  planWait(med)  firstPlanMs(med,min-max)  planUs/lap(med)  firstTimedOut  firstToGoal")
    for (leg, sim), ds in sorted(g.items()):
        first = [d["planprof"][0] for d in ds if d["planprof"]]
        ms = [int(p["us"]) / 1000 for p in first]
        print(f"{leg:8s} {'simedge' if sim else 'astar  ':7s} {len(ds):2d}  {st.median(int(d['planWait']) for d in ds):6.1f}"
              f"         {st.median(ms):7.0f} ({min(ms):.0f}-{max(ms):.0f})        {st.median(int(d['planUs']) for d in ds) / 1000:7.0f} ms"
              f"        {sum(p['timedOut'] == 'true' for p in first)}/{len(first)}          {sum(p['toGoal'] == 'true' for p in first)}/{len(first)}")
    print("\nper SimEdge search (all searches, first plan marked *): share of wall time")
    rows = []
    for d in laps:
        for i, p in enumerate(d["simprof"]):
            ms = int(p["ms"])
            sims = [int(x) for x in p["simUs"].split("/")]
            rows.append((d["leg"], i == 0, ms, sum(sims) / 1000, int(p["applyUs"]) / 1000, int(p["nodes"]), int(p["cells"]),
                         sum(int(x) for x in p["sims"].split("/")), int(p["dupSims"]), int(p["apply"]), int(p["applyDistinct"]), p))
    tot = defaultdict(lambda: [0.0] * 8)
    for leg, first, ms, simms, applyms, nodes, cells, sims, dup, apply, distinct, p in rows:
        t = tot["first" if first else "later"]
        for i, v in enumerate((ms, simms, applyms, nodes, cells, sims, dup, apply)):
            t[i] += v
        t.append(distinct)
    for k, t in tot.items():
        ms, simms, applyms, nodes, cells, sims, dup, apply = t[:8]
        distinct = sum(t[8:])
        print(f"  {k:5s}: wall {ms:.0f} ms, sim {simms:.0f} ms ({simms / ms:.0%}), legacy apply {applyms:.0f} ms ({applyms / ms:.0%}),"
              f" other {ms - simms - applyms:.0f} ms ({(ms - simms - applyms) / ms:.0%}); nodes/cell {nodes / cells:.1f},"
              f" sims/node {sims / nodes:.1f}, exact-duplicate sims {dup / sims:.1%}, apply/distinct {apply / distinct:.1f}x")
    alloc = defaultdict(list)
    for d in laps:
        for p in d["planprof"]:
            alloc[p["planner"]].append((int(p["allocKB"]) / 1024, int(p["us"]) / 1000, int(p["map"])))
    for k, v in alloc.items():
        print(f"  {k}: searches {len(v)}, alloc MB/search median {st.median(a for a, _, _ in v):.0f}, ms median {st.median(m for _, m, _ in v):.0f},"
              f" map median {st.median(n for _, _, n in v):.0f}")
    print("\nfirst-plan primary budget (ms) by position in the leg block (laps on one leg run back to back):")
    pos = defaultdict(list)
    for d in laps:
        if d["planprof"]:
            pos[(d["mode"] in "BD", d["lap"] % 4)].append((int(d["planprof"][0]["primaryMs"]), int(d["planWait"])))
    for (sim, k), v in sorted(pos.items()):
        print(f"  {'simedge' if sim else 'astar  '} pos {k}: primaryMs {sorted(set(a for a, _ in v))} planWait median {st.median(b for _, b in v)}")


if __name__ == "__main__":
    main(sys.argv[1])
