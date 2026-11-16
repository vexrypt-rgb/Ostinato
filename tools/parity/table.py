"""Parity table: scenario x metric, real 26.3 client (run 1 and 2) vs protocol bot.

usage: python table.py SUFFIX [scenario ...]   -> markdown table on stdout
Cells: identical / different (with the first divergence). Noise floor = real run 1 vs real run 2.
"""
import math, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from diff import load, end_state, ROOT

SCEN = ["walk", "pillar", "parkour", "ladder", "bridge", "voidbridge", "mine"]


def positions(run):
    pts = []
    for line in open(os.path.join(ROOT, run, 'packetlog.txt'), encoding='utf-8', errors='replace'):
        m = re.match(r't=(\d+) OUT (?:Pos|PosRot) x=(\S+) y=(\S+) z=(\S+)', line)
        if m:
            pts.append((float(m[2]), float(m[3]), float(m[4])))
    return pts


def path_dev(a, b):
    pa, pb = positions(a), positions(b)
    if not pa or not pb:
        return None
    d = [min(math.dist(p, q) for q in pa) for p in pb]
    d.sort()
    return d[len(d) // 2], d[-1]


def corrections(run):
    rows, _, _ = load(run)
    tp = sum(1 for _, k, _ in rows if k == 'AcceptTeleport')
    srv = open(os.path.join(ROOT, run, 'server.out'), encoding='utf-8', errors='replace').read()
    return max(0, tp - 2), len(re.findall(r'moved too quickly|moved wrongly', srv))


def compare(a, b):
    _, ta, A = load(a)
    _, tb, B = load(b)
    if ta is None or tb is None:
        return 'no action', None, 0
    span = min(max(A, default=0), max(B, default=0))
    diffs = [d for d in range(0, span + 1) if A.get(d, []) != B.get(d, [])]
    return ('identical' if not diffs else 'different'), (diffs[0] if diffs else None), len(diffs)


def short_end(run):
    e = end_state(run)
    pos = next((x.split('data: ')[1] for x in e if 'data: [' in x), '?')
    checks = ' '.join(x.split('CHECK ')[1] for x in e if 'CHECK' in x)
    return pos.replace('d', ''), checks


def main():
    suf = sys.argv[1]
    scen = sys.argv[2:] or SCEN
    print('| Scenario | real vs real | real vs bot | first divergent tick | divergent ticks | path dev bot vs real p50/max (blocks) | end real | end bot | outcome checks real / bot | corrections real / bot |')
    print('|---|---|---|---|---|---|---|---|---|---|')
    for s in scen:
        r1, r2, p1 = f'{s}-real-1-{suf}', f'{s}-real-2-{suf}', f'{s}-proto-1-{suf}'
        if not all(os.path.isdir(os.path.join(ROOT, x)) for x in (r1, p1)):
            print(f'| {s} | not run | not run | | | | | | | |')
            continue
        nf = compare(r1, r2) if os.path.isdir(os.path.join(ROOT, r2)) else ('not run', None, 0)
        v, first, n = compare(r1, p1)
        dev = path_dev(r1, p1)
        er, cr = short_end(r1)
        eb, cb = short_end(p1)
        print(f'| {s} | {nf[0]}' + (f' (first +{nf[1]}, {nf[2]} ticks)' if nf[0] == 'different' else '') +
              f' | {v} | {"" if first is None else "+" + str(first)} | {n} | ' +
              (f'{dev[0]:.2f} / {dev[1]:.2f}' if dev else '') +
              f' | {er} | {eb} | {cr or "-"} / {cb or "-"} | {corrections(r1)} / {corrections(p1)} |')


if __name__ == '__main__':
    main()
