"""Presto v4.1 A/B statistics over prestotest-result files (v4.1 row format: key=value, joltMode/joltResult).

Pairs are the harness's own (laps 2k, 2k+1), counterbalanced ABBA, so a pair is matched by position, not by order.
Reports, per run and pooled across runs of the same jolt mode only:
  completion (Wilson 95%), discordant pairs (exact McNemar), reached-lap ticks, end-to-end cost (all ticks spent,
  failed attempts included, per completed lap), paired tick differences (both reached; exact sign test), failures
  with their places, the movement/ownership/correction counters, and Presto's prediction cost.
Usage: python abstats41.py result1.txt [result2.txt ...]"""
import math, re, sys

def wilson(k, n, z=1.96):
    if n == 0: return float('nan'), float('nan')
    p = k / n; d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d; h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return c - h, c + h

def binom_two_sided(k, n):
    """Exact two-sided binomial p for k of n at 1/2 (sign test, McNemar)."""
    if n == 0: return 1.0
    pk = [math.comb(n, i) / 2 ** n for i in range(n + 1)]
    return min(1.0, sum(p for p in pk if p <= pk[k] + 1e-12))

T975 = {1: 12.71, 2: 4.30, 3: 3.18, 4: 2.78, 5: 2.57, 6: 2.45, 7: 2.36, 8: 2.31, 9: 2.26, 10: 2.23, 12: 2.18, 15: 2.13,
        20: 2.09, 25: 2.06, 30: 2.04, 40: 2.02, 60: 2.00}
def tcrit(df):
    for k in sorted(T975):
        if df <= k: return T975[k]
    return 1.96

def parse(f):
    mode = None; rows = []
    for line in open(f, encoding='utf-8', errors='replace'):
        m = re.search(r'PRESTOTEST (lap \d+ presto=.*)', line)
        if not m: continue
        kv = dict(re.findall(r'(\w+)=(\S+)', m[1]))
        kv['lap'] = int(re.search(r'lap (\d+)', m[1])[1]); kv['file'] = f
        for k, v in list(kv.items()):
            if isinstance(v, str) and re.fullmatch(r'-?\d+', v): kv[k] = int(v)
            elif isinstance(v, str) and re.fullmatch(r'-?\d+\.\d+', v): kv[k] = float(v)
        kv['on'] = kv['presto'] == 'true'; kv['isJolt'] = kv['jolt'] == 'true'; kv['ok'] = kv['result'] == 'REACHED'
        rows.append(kv)
        if kv['isJolt']: mode = kv.get('joltMode')
    return mode or 'safe', rows

COUNTERS = ['corrections', 'posPackets', 'pathEvents', 'driverChanges', 'rewinds', 'deferred', 'declinedTicks', 'hopsHeld',
            'overridden', 'fallbacks', 'distrusts', 'retrusts', 'checks', 'agree', 'unavailable', 'noLanding', 'landings',
            'landingMisses']

def report(name, rows):
    print(f'=== {name}: {len(rows)} laps')
    for jolt in (False, True):
        sel = [r for r in rows if r['isJolt'] == jolt]
        if not sel: continue
        print(' jolt laps' if jolt else ' normal laps')
        for on in (False, True):
            r = [x for x in sel if x['on'] == on]
            k = sum(x['ok'] for x in r); t = sorted(x['ticks'] for x in r if x['ok'])
            lo, hi = wilson(k, len(r)); spent = sum(x['ticks'] for x in r)
            mean = sum(t) / len(t) if t else float('nan')
            sd = math.sqrt(sum((v - mean) ** 2 for v in t) / (len(t) - 1)) if len(t) > 1 else float('nan')
            print(f"  {'presto  ' if on else 'baseline'}: {k}/{len(r)} reached ({k / max(1, len(r)):.0%}, 95% {lo:.0%}-{hi:.0%})"
                  f"; reached ticks mean {mean:.1f} sd {sd:.1f} median {t[len(t) // 2] if t else '-'}"
                  f"; end-to-end {spent} ticks spent / {k} reached = {spent / k if k else float('inf'):.1f} per completion")
            for x in r:
                if not x['ok']:
                    print(f"     {x['result']} lap {x['lap']} at x={x['x']} z={x['z']} y={x['y']} ticks={x['ticks']}"
                          + (f" jolt={x.get('joltResult')}" if jolt else '') + f" [{x['file'].split('/')[-1].split(chr(92))[-1]}]")
            if jolt:
                print('     jolts: ' + ', '.join(f"lap {x['lap']} {x.get('joltResult')}" for x in r))
            sums = {c: sum(x.get(c, 0) for x in r) for c in COUNTERS}
            print('     counters ' + ' '.join(f'{c}={v}' for c, v in sums.items() if v))
            ticks = sum(x['ticks'] for x in r) or 1
            kin = sum(x.get('kinNs', 0) for x in r)
            line = f'     main thread: kinematic {kin / ticks / 1000:.1f} us/tick'
            if on:
                ppe = sum(x.get('ppeNs', 0) for x in r); ch = sum(x.get('checks', 0) for x in r)
                hook = sum(x.get('hookNs', 0) for x in r); ht = sum(x.get('hookTicks', 0) for x in r)
                line += (f'; ppe {ppe / 1e6:.1f} ms over {ch} checks = {ppe / ch / 1e6 if ch else float("nan"):.2f} ms/check'
                         f'; hook {hook / ht / 1000 if ht else float("nan"):.1f} us/hooked tick ({ht} ticks)')
            print(line)
        # pairs: harness laps 2k, 2k+1 hold one of each arm
        by = {(x['file'], x['lap']): x for x in sel}
        diffs = []; both = onlyB = onlyP = neither = 0
        for (f, i), x in by.items():
            if i % 2 or (f, i + 1) not in by: continue
            y = by[(f, i + 1)]
            if x['on'] == y['on']: continue
            b, p = (y, x) if x['on'] else (x, y)
            if b['ok'] and p['ok']: both += 1; diffs.append(p['ticks'] - b['ticks'])
            elif b['ok']: onlyB += 1
            elif p['ok']: onlyP += 1
            else: neither += 1
        n = both + onlyB + onlyP + neither
        print(f'  pairs n={n}: both reached {both}, baseline only {onlyB}, Presto only {onlyP}, neither {neither}; '
              f'McNemar exact p {binom_two_sided(min(onlyB, onlyP), onlyB + onlyP):.3f}')
        if len(diffs) > 1:
            m = sum(diffs) / len(diffs); s = math.sqrt(sum((d - m) ** 2 for d in diffs) / (len(diffs) - 1))
            h = tcrit(len(diffs) - 1) * s / math.sqrt(len(diffs))
            pos = sum(d > 0 for d in diffs); neg = sum(d < 0 for d in diffs)
            print(f'  paired ticks (both reached) n={len(diffs)}: Presto - baseline mean {m:+.1f} sd {s:.1f} 95% CI {m - h:+.1f}..{m + h:+.1f}; '
                  f'median {sorted(diffs)[len(diffs) // 2]:+d}; Presto slower {pos} faster {neg} tied {len(diffs) - pos - neg}, '
                  f'sign test p {binom_two_sided(min(pos, neg), pos + neg):.3f}')

runs = [(f,) + parse(f) for f in sys.argv[1:]]
for f, mode, rows in runs:
    report(f'{f} (jolt {mode})', rows)
for mode in sorted({m for _, m, _ in runs}):
    group = [r for _, m, rs in runs if m == mode for r in rs]
    if sum(1 for _, m, _ in runs if m == mode) > 1:
        report(f'POOLED jolt={mode} ({sum(1 for _, m, _ in runs if m == mode)} runs, same conditions)', group)
