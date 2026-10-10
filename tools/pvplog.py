#!/usr/bin/env python3
"""Summarise Ostinato PvP recordings (pvplogs/*.log.gz).
usage: pvplog.py FILE...            summary per fight
       pvplog.py -t FILE [a b]      dump ticks a..b (default all)
       pvplog.py -e FILE            only ticks with events (hits, damage)"""
import gzip, sys, math

def load(path):
    head, rows, end = [], [], ""
    with gzip.open(path, "rt", errors="replace") as f:
        for ln in f:
            ln = ln.rstrip("\n")
            if ln.startswith("#end"): end = ln
            elif ln.startswith("#"): head.append(ln)
            elif ln:
                p = ln.split("|")
                if len(p) >= 6: rows.append(p)
    return head, rows, end

def ent(s):
    v = s.split(",")
    return dict(x=float(v[0]), y=float(v[1]), z=float(v[2]), vx=float(v[3]), vy=float(v[4]), vz=float(v[5]),
                yaw=float(v[6]), pitch=float(v[7]), hp=float(v[8]), gnd=v[10], item=v[11], fl=v[12] if len(v) > 12 else "")

def cols(r):
    """Support v1 (t|me|tg|dist|state|ev) and v2 (t|me|tg|dist|cd=|dec=|state|ev)."""
    if len(r) >= 8 and r[4].startswith("cd=") and r[5].startswith("dec="):
        return {"t": int(r[0]), "me": r[1], "tg": r[2], "dist": float(r[3]), "cd": r[4], "dec": r[5], "state": r[6], "ev": r[7]}
    return {"t": int(r[0]), "me": r[1], "tg": r[2], "dist": float(r[3]), "cd": "", "dec": "", "state": r[4], "ev": r[5] if len(r) > 5 else ""}

def summary(path):
    head, rows, end = load(path)
    print("==", path)
    for h in head: print("  ", h)
    print("  ", end or "(no #end: crashed or still running)")
    if not rows: return
    parsed = [cols(r) for r in rows]
    dist = [p["dist"] for p in parsed]
    evs = [(p["t"], p["ev"]) for p in parsed if p["ev"]]
    items = {}
    for p in parsed:
        it = ent(p["me"])["item"]; items[it] = items.get(it, 0) + 1
    states = {}
    for p in parsed:
        k = p["state"].split()[1:3]; k = " ".join(k); states[k] = states.get(k, 0) + 1
    decs = {}
    for p in parsed:
        if p["dec"]:
            d = p["dec"].removeprefix("dec="); decs[d] = decs.get(d, 0) + 1
    close = sum(1 for d in dist if d < 3.5) / len(dist)
    print(f"   ticks={len(rows)} avgDist={sum(dist)/len(dist):.1f} timeWithin3.5={close:.0%}")
    print("   my held items:", dict(sorted(items.items(), key=lambda kv: -kv[1])[:5]))
    print("   special-state ticks (mace,pearl):", dict(sorted(states.items(), key=lambda kv: -kv[1])[:5]))
    if decs:
        print("   decisions:", dict(sorted(decs.items(), key=lambda kv: -kv[1])[:12]))
    swings = sum(1 for p in parsed if "S" in ent(p["tg"])["fl"])
    blocks = sum(1 for p in parsed if "B" in ent(p["tg"])["fl"])
    print(f"   opponent swinging {swings} ticks, blocking {blocks} ticks")
    print("   events:", " ".join(f"{t}:{e}" for t, e in evs[:60]) + (" ..." if len(evs) > 60 else ""))
    # damage taken per 100-tick window
    win = {}
    for t, e in evs:
        i = e.find("D")
        if i >= 0:
            try: win[t // 100] = win.get(t // 100, 0) + float(e[i + 1:].split("H")[0])
            except ValueError: pass
    print("   damage taken per 100 ticks:", {k * 100: round(v, 1) for k, v in sorted(win.items())})

def dump(path, a, b, only_events):
    _, rows, _ = load(path)
    for r in rows:
        c = cols(r)
        if a <= c["t"] <= b and (c["ev"] or not only_events):
            print("|".join(r))

if __name__ == "__main__":
    a = sys.argv[1:]
    if not a: sys.exit(__doc__)
    if a[0] in ("-t", "-e"):
        lo = int(a[2]) if len(a) > 2 else 0
        hi = int(a[3]) if len(a) > 3 else 10**9
        dump(a[1], lo, hi, a[0] == "-e")
    else:
        for p in a: summary(p)
