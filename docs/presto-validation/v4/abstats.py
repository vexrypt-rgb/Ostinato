"""Presto v4 A/B statistics over prestotest-result files: completion (Wilson 95%), reached-lap ticks, Mann-Whitney U,
and within-run adjacent pairs (lap 2k baseline vs 2k+1 Presto, normal laps only)."""
import math, re, sys

def wilson(k, n, z=1.96):
    if n == 0: return (float('nan'),) * 2
    p = k / n; d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d; h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return c - h, c + h

def mwu(a, b):
    allv = sorted([(v, 0) for v in a] + [(v, 1) for v in b])
    ranks = {}; i = 0
    while i < len(allv):
        j = i
        while j < len(allv) and allv[j][0] == allv[i][0]: j += 1
        for k in range(i, j): ranks.setdefault(k, (i + j + 1) / 2)
        i = j
    ra = sum(ranks[k] for k, (v, g) in enumerate(allv) if g == 0)
    n1, n2 = len(a), len(b); u = ra - n1 * (n1 + 1) / 2
    mu = n1 * n2 / 2; sd = math.sqrt(n1 * n2 * (n1 + n2 + 1) / 12)
    z = (u - mu) / sd if sd else 0
    p = math.erfc(abs(z) / math.sqrt(2))
    return u, z, p

rows = []
for f in sys.argv[1:]:
    for line in open(f, encoding='utf-8', errors='replace'):
        m = re.search(r'PRESTOTEST lap (\d+) presto=(\w+) jolt=(\w+) result=(\w+) ticks=(\d+)', line)
        if m: rows.append((f, int(m[1]), m[2] == 'true', m[3] == 'true', m[4] == 'REACHED', int(m[5])))

for jolt in (False, True):
    print('jolt' if jolt else 'normal')
    for on in (False, True):
        r = [x for x in rows if x[2] == on and x[3] == jolt]
        k = sum(x[4] for x in r); t = [x[5] for x in r if x[4]]
        lo, hi = wilson(k, len(r))
        mean = sum(t) / len(t) if t else float('nan')
        sd = math.sqrt(sum((v - mean) ** 2 for v in t) / (len(t) - 1)) if len(t) > 1 else float('nan')
        print(f"  {'presto' if on else 'baseline'}: {k}/{len(r)} reached ({k / max(1, len(r)):.0%}, 95% {lo:.0%}-{hi:.0%}); reached ticks mean {mean:.1f} sd {sd:.1f} median {sorted(t)[len(t) // 2] if t else '-'}")
    a = [x[5] for x in rows if not x[2] and x[3] == jolt and x[4]]
    b = [x[5] for x in rows if x[2] and x[3] == jolt and x[4]]
    if a and b:
        u, z, p = mwu(a, b)
        print(f"  ticks Mann-Whitney U {u:.1f} z {z:.2f} p {p:.3f} (normal approx; laps interleaved, not independent)")
pairs = []
by = {}
for x in rows:
    if not x[3]: by.setdefault(x[0], {})[x[1]] = x
for f, laps in by.items():
    for i, x in laps.items():
        if not x[2] and i + 1 in laps and laps[i + 1][2] and x[4] and laps[i + 1][4]:
            pairs.append(laps[i + 1][5] - x[5])
if pairs:
    m = sum(pairs) / len(pairs); s = math.sqrt(sum((d - m) ** 2 for d in pairs) / (len(pairs) - 1))
    print(f"adjacent pairs (both reached) n={len(pairs)}: Presto - baseline mean {m:+.1f} ticks, sd {s:.1f}, 95% CI {m - 1.96 * s / math.sqrt(len(pairs)):+.1f}..{m + 1.96 * s / math.sqrt(len(pairs)):+.1f}")
