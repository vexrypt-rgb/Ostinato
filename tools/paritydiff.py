#!/usr/bin/env python3
# Ostinato: compare two -Dostinato.trace files (a headless and a real client running the same drill) and print the
# first tick where they part, with the fields that differ and what led up to it.
#   paritydiff.py A.trace B.trace [--align fight|0|N:M] [--tol 0] [--context 3] [--ignore fr,st]
# Lines are aligned at the first tick of the fight (dec not '-') by default. 'st' is compared as a delta from the
# aligned line (the server's tick relative to ours); 'fr' (frames since the last tick) is never compared.
import argparse, sys

def parse(path):
    rows = []
    for line in open(path, encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        d = {}
        for tok in line.split():
            k, _, v = tok.partition('=')
            d[k] = v
        rows.append(d)
    return rows

def start(rows, how):
    if how == 'fight':
        for i, r in enumerate(rows):
            if r.get('dec', '-') != '-':
                return i
        return 0
    return int(how)

def close(a, b, tol):
    if a == b:
        return True
    if tol <= 0:
        return False
    try:
        xs, ys = [float(x) for x in a.split(',')], [float(y) for y in b.split(',')]
    except ValueError:
        return False
    return len(xs) == len(ys) and all(abs(x - y) <= tol for x, y in zip(xs, ys))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('a'); ap.add_argument('b')
    ap.add_argument('--align', default='fight', help="'fight', or N:M line indices")
    ap.add_argument('--tol', type=float, default=0.0)
    ap.add_argument('--context', type=int, default=3)
    ap.add_argument('--ignore', default='fr,t')
    o = ap.parse_args()
    A, B = parse(o.a), parse(o.b)
    if ':' in o.align:
        ia, ib = (int(x) for x in o.align.split(':'))
    else:
        ia, ib = start(A, o.align), start(B, o.align)
    ign = set(o.ignore.split(','))
    sa0, sb0 = int(A[ia].get('st', 0)), int(B[ib].get('st', 0))
    n = min(len(A) - ia, len(B) - ib)
    print(f"aligned A[{ia}] (t={A[ia].get('t')}) with B[{ib}] (t={B[ib].get('t')}), {n} ticks to compare")
    fa = [int(r.get('fr', 0)) for r in A[ia:ia + n]]; fb = [int(r.get('fr', 0)) for r in B[ib:ib + n]]
    if fa and fb:
        print(f"frames/tick A mean {sum(fa)/len(fa):.2f} (0-frame ticks {fa.count(0)}), B mean {sum(fb)/len(fb):.2f} (0-frame ticks {fb.count(0)})")
    sdA = [int(r.get('st', 0)) - int(r.get('t', 0)) for r in A[ia:ia + n]]; sdB = [int(r.get('st', 0)) - int(r.get('t', 0)) for r in B[ib:ib + n]]
    if sdA and sdB:
        print(f"server-minus-client tick drift over the run: A {sdA[-1]-sdA[0]:+d}, B {sdB[-1]-sdB[0]:+d}")
    for k in range(n):
        ra, rb = A[ia + k], B[ib + k]
        diff = []
        for key in sorted(set(ra) | set(rb)):
            if key in ign:
                continue
            va, vb = ra.get(key, '<none>'), rb.get(key, '<none>')
            if key == 'st':
                va, vb = str(int(va) - sa0), str(int(vb) - sb0)
            if not close(va, vb, o.tol):
                diff.append((key, va, vb))
        if diff:
            print(f"\nFIRST DIVERGENCE at aligned tick {k} (A t={ra.get('t')}, B t={rb.get('t')}):")
            for key, va, vb in diff:
                print(f"  {key}:\n    A {va}\n    B {vb}")
            print("\ncontext (A | B):")
            for j in range(max(0, k - o.context), min(n, k + 2)):
                print(f"  A[{j}] " + ' '.join(f"{x}={A[ia+j].get(x)}" for x in ('t', 'st', 'fr', 'r0', 'r', 'in', 'dec', 'hd')))
                print(f"  B[{j}] " + ' '.join(f"{x}={B[ib+j].get(x)}" for x in ('t', 'st', 'fr', 'r0', 'r', 'in', 'dec', 'hd')))
            return 1
    print("no divergence in the compared ticks")
    return 0

if __name__ == '__main__':
    sys.exit(main())
