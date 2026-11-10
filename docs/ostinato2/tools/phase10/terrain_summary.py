"""Ostinato 2.0 Phase 10: summarize INTERACTTERRAIN rows (InteractTerrainLiveTest) and evaluate the pre-registered gates.

Usage: python terrain_summary.py <lines.txt> [<lines.txt> ...]
Each file holds the INTERACTTERRAIN lines of one client log (one seed).
"""
import re
import statistics
import sys
from collections import Counter, defaultdict

POSITIVE = {"CHEST2", "FURNACE2", "BLAST", "ENDER", "TABLE", "MENU_OPEN", "VANISH_ONE", "LAG_SHORT"}
CANCELS = {"CANCEL_MOVE", "CANCEL_ACT"}


def fields(line):
    out = {}
    for m in re.finditer(r'(\w+)=("[^"]*"|\S+)', line):
        out.setdefault(m.group(1), m.group(2).strip('"'))
    return out


def load(path, begins):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "INTERACTTERRAIN begin " in line:
                d = fields(line[line.index("INTERACTTERRAIN begin ") + 22:])
                begins[(path, d["lap"])] = d.get("known", "")
                continue
            if "INTERACTTERRAIN row " not in line:
                continue
            d = fields(line[line.index("INTERACTTERRAIN row ") + 20:])
            d["file"] = path
            rows.append(d)
    return rows


def main(paths):
    begins = {}
    rows = [r for p in paths for r in load(p, begins)]
    for r in rows:
        k = begins.get((r["file"], r.get("lap")), "")
        # known=<n>,<positions in the box>...: the fixtures Baritone's own search returned at lap start
        r["knownInBox"] = max(0, len(k.split(",")) - 1) if k and k != "-" else 0
        r["eligible"] = r["knownInBox"] >= (2 if r.get("scen") == "VANISH_ONE" else 1)
    verdicts = [r for r in rows if r.get("part") in ("main", "rerun")]
    table = defaultdict(Counter)
    for r in verdicts:
        table[(r["scen"], r["part"], r["arm"])][r["cls"]] += 1
    print("classes by scenario / part / arm")
    for k in sorted(table):
        print("  %-11s %-5s %s  %s" % (k[0], k[1], k[2], dict(sorted(table[k].items()))))

    def count(arm, cls, scen=None, part=None):
        return sum(1 for r in verdicts if r["arm"] == arm and r["cls"] == cls
                   and (scen is None or r["scen"] in scen) and (part is None or r["part"] == part))

    print("\ntotals (verdict rows)")
    for arm in "AB":
        c = Counter(r["cls"] for r in verdicts if r["arm"] == arm)
        print("  arm %s %s" % (arm, dict(sorted(c.items()))))

    # positive outcomes: positive scenarios' main rows plus the cancel scenarios' reruns
    def pos(arm, cls):
        return count(arm, cls, POSITIVE, "main") + count(arm, cls, CANCELS, "rerun")

    pos_n = {a: sum(1 for r in verdicts if r["arm"] == a and (r["scen"] in POSITIVE and r["part"] == "main"
                                                              or r["scen"] in CANCELS and r["part"] == "rerun"))
             for a in "AB"}
    print("\npositive set (all laps): A TS %d/%d HANG %d, B TS %d/%d HANG %d" % (
        pos("A", "TS"), pos_n["A"], pos("A", "HANG"), pos("B", "TS"), pos_n["B"], pos("B", "HANG")))

    # T2 set: positive (file, rep, scenario, part) pairs whose target was known at lap start in both arms
    pair = defaultdict(dict)
    for r in verdicts:
        if r["scen"] in POSITIVE and r["part"] == "main" or r["scen"] in CANCELS and r["part"] == "rerun":
            pair[(r["file"], r["rep"], r["scen"], r["part"])][r["arm"]] = r
    elig = [v for v in pair.values() if "A" in v and "B" in v and v["A"]["eligible"] and v["B"]["eligible"]]
    t2a = sum(1 for v in elig if v["A"]["cls"] == "TS")
    t2b = sum(1 for v in elig if v["B"]["cls"] == "TS")
    print("positive set (target known at start in both arms): pairs %d of %d, A TS %d, B TS %d, B HANG %d" % (
        len(elig), len(pair), t2a, t2b, sum(1 for v in elig if v["B"]["cls"] == "HANG")))
    print("pairs excluded: %s" % dict(Counter(k[2] for k, v in pair.items()
                                             if not ("A" in v and "B" in v and v["A"]["eligible"] and v["B"]["eligible"]))))

    # B-only hangs: a B HANG where the A lap of the same (file, rep, scenario, part) did not hang
    key = lambda r: (r["file"], r["rep"], r["scen"], r["part"])
    a_hang = {key(r) for r in verdicts if r["arm"] == "A" and r["cls"] == "HANG"}
    b_only = [key(r) for r in verdicts if r["arm"] == "B" and r["cls"] == "HANG" and key(r) not in a_hang]

    stops = [r for r in rows if r.get("part") == "stop"]
    stop_bad = [r for r in stops if r.get("keys1") != "-" or r.get("activeEnd") != "false" or r.get("keysEnd") != "-"]
    b_stop_nonterminal = [r for r in stops if r["arm"] == "B" and not re.search(r":(CANCELLED|UNKNOWN)/", r.get("outcome", ""))]
    print("stop rows %d, keys/active left after stop %d, B stop outcome not CANCELLED/UNKNOWN %d, lateOpen %s" % (
        len(stops), len(stop_bad), len(b_stop_nonterminal), dict(Counter((r["arm"], r.get("lateOpen")) for r in stops))))

    preempt_b = [r for r in verdicts if r["scen"] == "PREEMPT" and r["arm"] == "B"]
    preempt_b_ok = [r for r in preempt_b if re.search(r":CANCELLED/OWNERSHIP_LOST", r.get("outcome", ""))]
    print("PREEMPT B CANCELLED/OWNERSHIP_LOST %d/%d" % (len(preempt_b_ok), len(preempt_b)))

    magma = [r for r in rows if r.get("scen") == "MAGMA"]
    print("MAGMA %s" % dict(Counter(r["cls"] for r in magma)))

    ends = [r for r in rows if r.get("part") == "end"]
    late = Counter((r["scen"], r["arm"]) for r in ends if r.get("openAfterVerdict") == "true")
    print("open after verdict (end rows) %s" % dict(late))
    for arm in "AB":
        us = [float(r["runnerUs"]) for r in ends if r["arm"] == arm and r.get("lap") not in ("0", "1")]
        if us:
            print("runner us/tick arm %s: median %.2f (n=%d, laps 0-1 excluded)" % (arm, statistics.median(us), len(us)))
    keys_left = [r for r in verdicts if r.get("keys") not in (None, "-") and r["cls"] != "HANG"]
    print("verdict rows with keys still forced (non-HANG) %d" % len(keys_left))
    died = [r for r in verdicts if r["cls"] == "DIED"]

    fs_b = count("B", "FS") + count("B", "FF")
    print("\nGATES")
    print("  T1 B false successes (FS) = 0: %s (FS %d, FF %d)" % ("PASS" if count("B", "FS") == 0 else "FAIL", count("B", "FS"), count("B", "FF")))
    # T2 needs at least 12 eligible pairs per seed (of 30)
    t2 = t2b >= t2a - 2 * len(paths) and len(elig) >= 12 * len(paths)
    print("  T2 B TS >= A TS - 2 per seed, >= 12 eligible pairs per seed: %s (%d vs %d, %d pairs)" % ("PASS" if t2 else "FAIL", t2b, t2a, len(elig)))
    t3 = not stop_bad and not b_stop_nonterminal and len(preempt_b_ok) == len(preempt_b)
    print("  T3 bounded release on stop/preempt: %s" % ("PASS" if t3 else "FAIL"))
    print("  T4 no B-only HANG: %s (%s)" % ("PASS" if not b_only else "FAIL", b_only))
    m1 = magma and all(r["cls"] == "CLEAR" for r in magma)
    print("  M1 MAGMA all CLEAR: %s" % ("PASS" if m1 else "FAIL"))
    print("  deaths %d" % len(died))


if __name__ == "__main__":
    main(sys.argv[1:])
