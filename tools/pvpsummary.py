#!/usr/bin/env python3
# Ostinato: compressed, tick-preserving summaries of PvpRecorder fight logs (pvplogs/*.log.gz), plus the
# Phase 0 variance study that sizes ACS trials. Pure standard library.
"""usage:
  pvpsummary.py summary FILE...          one compressed summary per fight (events with absolute tick and delta,
                                         run-length decision spans, header/footer)
  pvpsummary.py study DIR [--json OUT]   variance study over every fight in DIR
The summary keeps every event's absolute fight tick (t=) and the gap to the previous event (+N), so timing
(cooldown use, hit/hurt spacing, reaction to swings) survives compression."""
import gzip, json, math, os, random, sys
from collections import defaultdict

ACTIVE_DIST = 7.0   # CombatGeometry.DRIVE, as CombatOutcome counts active ticks
CLICK_TICKS = 2     # CombatOutcome.Rules.CLICK_TICKS
WINDOW = 200        # CombatOutcome.Rules.WINDOW


def ent(s):
    v = s.split(",")
    return dict(x=float(v[0]), y=float(v[1]), z=float(v[2]), vx=float(v[3]), vy=float(v[4]), vz=float(v[5]),
                yaw=float(v[6]), pitch=float(v[7]), hp=float(v[8]), ab=float(v[9]), gnd=v[10] == "1",
                item=v[11], fl=v[12] if len(v) > 12 else "")


def parse_ev(ev):
    """'AD1.50H4.52X' -> list of (code, value)."""
    out, i = [], 0
    while i < len(ev):
        c = ev[i]
        i += 1
        if c in "DH":
            j = i
            while j < len(ev) and (ev[j].isdigit() or ev[j] == "."):
                j += 1
            out.append((c, float(ev[i:j] or 0)))
            i = j
        elif c == "O" and ev[i:i + 1] == ":":
            j = i + 1
            while j < len(ev) and (ev[j].isalnum() or ev[j] == "_"):
                j += 1
            out.append(("O", ev[i + 1:j]))
            i = j
        elif ev.startswith("dur", i - 1):
            j = i + 2
            while j < len(ev) and ev[j].isdigit():
                j += 1
            out.append(("dur", int(ev[i + 2:j] or 0)))
            i = j
        else:
            out.append((c, None))
    return out


def load(path):
    """Yields fights (a file can hold several when a fight restarted): dict with head, rows, opp, notes, end."""
    fights, cur = [], None
    with gzip.open(path, "rt", errors="replace") as f:
        for ln in f:
            ln = ln.rstrip("\n")
            if not ln:
                continue
            if ln.startswith("#fight"):
                cur = dict(head={}, rows=[], notes=[], opp="", end={}, file=os.path.basename(path))
                fights.append(cur)
                for kv in ln.split()[2:]:
                    k, _, v = kv.partition("=")
                    cur["head"][k] = v
                continue
            if cur is None:
                continue
            if ln.startswith("#me ") or ln.startswith("#tg "):
                cur["head"][ln[1:3]] = dict(kv.split("=", 1) for kv in ln[4:].split() if "=" in kv)
            elif ln.startswith("#opp"):
                cur["opp"] = ln
            elif ln.startswith("#end"):
                cur["end"] = dict(kv.split("=", 1) for kv in ln.split()[1:] if "=" in kv)
            elif ln.startswith("@"):
                cur["notes"].append(ln)
            elif ln[0].isdigit():
                p = ln.split("|")
                if len(p) < 8:
                    continue
                try:
                    cur["rows"].append(dict(t=int(p[0]), me=ent(p[1]), tg=ent(p[2]), dist=float(p[3]),
                                            cd=float(p[4][3:]), dec=p[5][4:], state=p[6], ev=parse_ev(p[7])))
                except (ValueError, IndexError):
                    continue
    return fights


def kit(f):
    me = f["head"].get("me", {})
    items = {v.split("x", 1)[-1] for k, v in me.items() if k.startswith("h")}
    items.add(me.get("mainhand", "-"))
    off = me.get("offhand", "-")
    parts = []
    for name, keys in (("mace", ["mace"]), ("spear", ["spear"]), ("crystal", ["end_crystal"]), ("anchor", ["respawn_anchor"]),
                       ("pot", ["splash_potion"]), ("axe", ["_axe"]), ("sword", ["_sword"]), ("bow", ["bow", "crossbow"])):
        if any(any(k in it for k in keys) for it in items):
            parts.append(name)
    if off == "shield":
        parts.append("shield")
    if off == "totem_of_undying":
        parts.append("totem")
    return "+".join(parts) or "fist"


def result(f):
    return f["end"].get("result", "none")


def summary(f):
    """The compressed text: one line per event with t=absolute tick and +gap, decisions as spans."""
    h = f["head"]
    out = [f"#fight {f['file']} label={h.get('label','?')} kit={kit(f)} result={result(f)} ticks={f['end'].get('ticks','?')} "
           f"dealt={f['end'].get('dealt','?')} taken={f['end'].get('taken','?')} attacks={f['end'].get('attacks','?')} hits={f['end'].get('hits','?')}"]
    last_t, span_dec, span_from = 0, None, None
    rows = f["rows"]
    for i, r in enumerate(rows):
        if r["dec"] != span_dec:
            if span_dec is not None:
                out.append(f"t={span_from}..{rows[i-1]['t']} dec={span_dec}")
            span_dec, span_from = r["dec"], r["t"]
        evs = []
        for c, v in r["ev"]:
            if c == "A":
                evs.append(f"A cd={r['cd']:.2f} d={r['dist']:.2f}" + (" crit" if not r["me"]["gnd"] and r["me"]["vy"] < 0 else "")
                           + (" spr" if "P" in r["me"]["fl"] else ""))
            elif c == "H":
                evs.append(f"H{v:.2f} tg={r['tg']['hp']:.1f}")
            elif c == "D":
                evs.append(f"D{v:.2f} me={r['me']['hp']:.1f}" + (" blk" if "B" in r["me"]["fl"] else ""))
            elif c == "X":
                evs.append("X")
            elif c == "O":
                evs.append(f"off={v}")
        if evs:
            out.append(f"t={r['t']} +{r['t']-last_t} " + " ; ".join(evs))
            last_t = r["t"]
    if span_dec is not None and rows:
        out.append(f"t={span_from}..{rows[-1]['t']} dec={span_dec}")
    out.extend(f["notes"])
    if f["opp"]:
        out.append(f["opp"])
    return "\n".join(out)


def windows(f):
    """CombatOutcome-like windows of WINDOW active ticks: (active, dealt-by-click, taken)."""
    ws, act, dealt, taken = [], 0, 0.0, 0.0
    last_click, prev = -100, None
    for r in f["rows"]:
        if any(c == "A" for c, _ in r["ev"]):
            last_click = r["t"]
        alive = r["me"]["hp"] > 0 and r["tg"]["hp"] > 0
        is_active = prev is not None and alive and r["dist"] <= ACTIVE_DIST and r["t"] == prev + 1
        prev = r["t"]
        h = sum(v for c, v in r["ev"] if c == "H")
        d = sum(v for c, v in r["ev"] if c == "D")
        if is_active:
            act += 1
            if r["t"] - last_click <= CLICK_TICKS:
                dealt += h
        taken += d  # taken counts every fight tick (CombatOutcome adds 'out' to taken)
        if act >= WINDOW:
            ws.append((act, dealt, taken))
            act, dealt, taken = 0, 0.0, 0.0
    if act > 0:
        ws.append((act, dealt, taken))
    return ws


def ratio_se(xs, ns):
    """Rate = sum x / sum n and its delta-method SE treating each element as independent."""
    N, X = sum(ns), sum(xs)
    if N == 0 or len(xs) < 2:
        return 0.0, float("inf")
    r = X / N
    s = sum((x - r * n) ** 2 for x, n in zip(xs, ns)) * len(xs) / (len(xs) - 1)
    return r, math.sqrt(s) / N


def study(d, jout=None):
    fights = []
    files = sorted(os.listdir(d))
    for fn in files:
        if fn.endswith(".log.gz"):
            try:
                fights.extend(load(os.path.join(d, fn)))
            except (OSError, EOFError):
                pass
    pvp = [f for f in fights if f["head"].get("target", "").lower().startswith("vex") and f["end"] and len(f["rows"]) > 20]
    rep = dict(files=len(files), fights=len(fights), vex_fights=len(pvp), per_kit={})
    by_kit = defaultdict(list)
    for f in pvp:
        by_kit[kit(f)].append(f)
    raw_bytes = sum(os.path.getsize(os.path.join(d, fn)) for fn in files)
    rep["corpus_gz_bytes"] = raw_bytes
    for k, fs in sorted(by_kit.items(), key=lambda kv: -len(kv[1])):
        if len(fs) < 30:
            continue
        res = defaultdict(int)
        for f in fs:
            res[result(f)] += 1
        W = [(fi, w) for fi, f in enumerate(fs) for w in windows(f)]
        full = [(fi, w) for fi, w in W if w[0] >= WINDOW]
        rates_d = [w[1] / w[0] for _, w in full]
        rates_t = [w[2] / w[0] for _, w in full]
        def cv(xs):
            m = sum(xs) / len(xs)
            return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1)) / m if m > 0 and len(xs) > 1 else float("nan")
        # lag-1 autocorrelation of consecutive full windows inside one fight
        pairs = []
        byf = defaultdict(list)
        for fi, w in full:
            byf[fi].append(w[1] / w[0])
        for seq in byf.values():
            pairs += list(zip(seq, seq[1:]))
        def corr(ps):
            if len(ps) < 10:
                return float("nan")
            a, b = zip(*ps)
            ma, mb = sum(a) / len(a), sum(b) / len(b)
            sa = math.sqrt(sum((x - ma) ** 2 for x in a)); sb = math.sqrt(sum((y - mb) ** 2 for y in b))
            return sum((x - ma) * (y - mb) for x, y in ps) / (sa * sb) if sa > 0 and sb > 0 else float("nan")
        # design effect: SE of dealt rate with windows independent vs fights as clusters
        _, se_w = ratio_se([w[1] for _, w in W], [w[0] for _, w in W])
        fx = defaultdict(lambda: [0.0, 0])
        for fi, w in W:
            fx[fi][0] += w[1]; fx[fi][1] += w[0]
        _, se_f = ratio_se([v[0] for v in fx.values()], [v[1] for v in fx.values()])
        # fight-level score in health bars (dealt - taken)/20 and win indicator
        scores = [(float(f["end"].get("dealt", 0)) - float(f["end"].get("taken", 0))) / 20 for f in fs]
        wins = [1.0 if result(f) == "win" else 0.0 for f in fs]
        sd = lambda xs: math.sqrt(sum((x - sum(xs) / len(xs)) ** 2 for x in xs) / (len(xs) - 1))
        ticks = [int(f["end"].get("ticks", 0)) for f in fs]
        p = sum(wins) / len(wins)
        rep["per_kit"][k] = dict(
            fights=len(fs), results=dict(res), win_rate=round(p, 3), mean_fight_ticks=round(sum(ticks) / len(ticks), 1),
            windows=len(W), full_windows=len(full), windows_per_fight=round(len(W) / len(fs), 2),
            cv_dealt_window=round(cv(rates_d), 3) if len(rates_d) > 2 else None,
            cv_taken_window=round(cv(rates_t), 3) if len(rates_t) > 2 else None,
            lag1_autocorr_dealt=round(corr(pairs), 3) if pairs else None,
            design_effect_dealt=round((se_f / se_w) ** 2, 2) if se_w > 0 and math.isfinite(se_f) else None,
            score_sd_bars=round(sd(scores), 3), win_sd=round(math.sqrt(p * (1 - p)), 3),
        )
    # compression ratio of the summary vs raw gz on a sample
    rnd = random.Random(1)
    sample = rnd.sample(pvp, min(200, len(pvp)))
    raw = sum(len("\n".join(str(r) for r in f["rows"])) for f in sample)
    comp = sum(len(summary(f)) for f in sample)
    rep["summary_chars_per_fight"] = round(comp / len(sample), 1)
    print(json.dumps(rep, indent=1))
    if jout:
        with open(jout, "w") as fo:
            json.dump(rep, fo, indent=1)
    return rep


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    if sys.argv[1] == "summary":
        for p in sys.argv[2:]:
            for f in load(p):
                print(summary(f)); print()
    elif sys.argv[1] == "study":
        j = sys.argv[sys.argv.index("--json") + 1] if "--json" in sys.argv else None
        study(sys.argv[2], j)
