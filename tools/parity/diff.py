"""Parity diff: tick-by-tick serverbound packet comparison of two PacketLog runs.

usage: python diff.py RUN_A RUN_B [--max 25]
Each run's outbound stream is aligned at its first "action" packet (PlayerInput, UseItemOn, UseItem,
SetCarriedItem, Action, or a position change) after the scenario teleport (last AcceptTeleport).
ClientTickEnd marks a tick. Positions and angles are compared at the logged precision (1e-6, 1e-3).
Prints: verdict, first divergent tick, divergent tick count, packet-type counts, server-observed end state.
"""
import os, re, sys
from collections import Counter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runs')
ACTION = ('PlayerInput', 'UseItemOn', 'UseItem', 'SetCarriedItem', 'Action', 'ServerboundPlayerCommandPacket', 'Punch')


def load(run):
    rows = []
    for line in open(os.path.join(ROOT, run, 'packetlog.txt'), encoding='utf-8', errors='replace'):
        m = re.match(r't=(-?\d+) OUT (\S+)(.*)', line.rstrip())
        if m:
            rows.append((int(m[1]), m[2], m[3].strip()))
    tp = max((i for i, r in enumerate(rows) if r[1] == 'AcceptTeleport'), default=0)
    start_pos = None
    anchor = None
    for i in range(tp + 1, len(rows)):
        t, k, rest = rows[i]
        if k in ('Pos', 'PosRot'):
            xyz = re.findall(r'[xyz]=(-?[\d.]+)', rest)
            if start_pos is None:
                start_pos = xyz
            elif xyz != start_pos:
                anchor = i
                break
        if k in ACTION:
            anchor = i
            break
    if anchor is None:
        return rows, None, {}
    t0 = rows[anchor][0]
    ticks = {}
    for t, k, rest in rows[anchor:]:
        if k == 'ClientTickEnd':
            continue
        ticks.setdefault(t - t0, []).append(f'{k} {rest}'.strip())
    return rows, t0, ticks


def end_state(run):
    p = os.path.join(ROOT, run, 'server.out')
    out = []
    for line in open(p, encoding='utf-8', errors='replace'):
        if 'has the following entity data' in line or 'CHECK' in line or 'moved too quickly' in line or 'moved wrongly' in line:
            out.append(line.split(']: ', 1)[-1].strip())
    return out


def main():
    a, b = sys.argv[1], sys.argv[2]
    mx = int(sys.argv[sys.argv.index('--max') + 1]) if '--max' in sys.argv else 25
    ra, ta, A = load(a)
    rb, tb, B = load(b)
    last = max(list(A) + list(B) + [0])
    span = min(max(A, default=0), max(B, default=0))
    diffs = [d for d in range(0, span + 1) if A.get(d, []) != B.get(d, [])]
    ca = Counter(k for _, k, _ in ra if _ is not None)
    cb = Counter(k for _, k, _ in rb if _ is not None)
    print(f'A={a} anchor t={ta}  B={b} anchor t={tb}  compared ticks 0..{span}')
    print(f'divergent ticks: {len(diffs)} of {span + 1}; first: {diffs[0] if diffs else None}')
    print('VERDICT:', 'IDENTICAL' if not diffs and A.keys() == B.keys() else 'DIFFERENT')
    print('packet types A vs B (whole run, outbound):')
    for k in sorted(set(ca) | set(cb)):
        if ca[k] != cb[k]:
            print(f'  {k}: {ca[k]} vs {cb[k]}')
    print('end state A:', end_state(a))
    print('end state B:', end_state(b))
    for d in diffs[:mx]:
        print(f'--- tick +{d}')
        for x in A.get(d, []):
            print('  A', x[:150])
        for x in B.get(d, []):
            print('  B', x[:150])


if __name__ == '__main__':
    main()
