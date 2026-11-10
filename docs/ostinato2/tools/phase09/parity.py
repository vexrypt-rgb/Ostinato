#!/usr/bin/env python3
"""Ostinato 2.0 Phase 09: parity of the container-adapter refactor against the Phase 08 build (BENCHMARKS.md, Phase 09).

  python docs/ostinato2/tools/phase09/parity.py <base-lines.txt>,<p09-lines.txt> [<base>,<p09> ...]
      each pair is one seed run on the Phase 08 build (baseline) and on the Phase 09 build; lines files come from
      phase08/interact_ab.py --extract

Reuses Phase 08's parser and metrics, so classes mean the same thing: the harness's server-side oracle (cls).
Prints, per seed and pooled: C1 (Phase 08's gates on the P09 build), C2 (per-row class and outcome parity),
the performance limits, and the pre-registered class.
"""
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'phase08'))
import interact_ab as ab  # noqa: E402

# Legacy arm A on a scenario where the open is impossible: FS and HANG are the same failure to notice (Phase 08 saw both).
A_LOOSE = {'VANISH', 'SEALED'}


def rows_by_key(run):
    # a lap can log several main rows (REPEAT's two steps); a HANG row has no step, so key by order within the lap
    out = {}
    seen = Counter()
    for r in run['rows']:
        base = (r['scen'], r['arm'], r['rep'], r['part'])
        out[base + (seen[base],)] = r
        seen[base] += 1
    return out


def outcome_sr(kv):
    o = kv.get('outcome', '')
    return o.split(':', 1)[1] if ':' in o else None  # STATE/REASON, without the request number


def same_class(scen, arm, a, b):
    if a == b:
        return True
    return arm == 'A' and scen in A_LOOSE and {a, b} <= {'FS', 'HANG'}


def parity(base_runs, p09_runs):
    c = Counter()
    diffs = []
    for base, p09 in zip(base_runs, p09_runs):
        seed = p09['start'].get('seed')
        kb, kp = rows_by_key(base), rows_by_key(p09)
        for key in sorted(set(kb) | set(kp)):
            scen, arm = key[0], key[1]
            if key not in kb or key not in kp:
                c['missing'] += 1
                diffs.append(f'{seed} {key}: row only in {"base" if key in kb else "p09"}')
                continue
            rb, rp = kb[key], kp[key]
            c['rows'] += 1
            if not same_class(scen, arm, rb['cls'], rp['cls']):
                c['class_diff'] += 1
                diffs.append(f"{seed} {key}: cls {rb['cls']} -> {rp['cls']}")
            if arm == 'B' and outcome_sr(rb) != outcome_sr(rp):
                c['outcome_diff'] += 1
                diffs.append(f'{seed} {key}: outcome {outcome_sr(rb)} -> {outcome_sr(rp)}')
        sb = {(s['scen'], s['arm'], s['rep']): s for s in base['stops']}
        sp = {(s['scen'], s['arm'], s['rep']): s for s in p09['stops']}
        for key in sorted(set(sb) & set(sp)):
            c['stops'] += 1
            if key[1] == 'B' and outcome_sr(sb[key]) != outcome_sr(sp[key]):
                # a stop's outcome can be CANCELLED or UNKNOWN by when the click lands (Phase 08 G7): report, not a diff
                c['stop_outcome_varies'] += 1
                diffs.append(f'{key}: stop outcome {outcome_sr(sb[key])} -> {outcome_sr(sp[key])} (timing, not counted)')
    return c, diffs


def perf(base_runs, p09_runs):
    _, _, ts_a0, _, _, _, _, _ = ab.metrics(base_runs, 'A')
    _, _, ts_a1, _, _, _, _, _ = ab.metrics(p09_runs, 'A')
    mb0, _, ts_b0, tf_b0, uses0, run0, _, _ = ab.metrics(base_runs, 'B')
    mb1, _, ts_b1, tf_b1, uses1, run1, _, _ = ab.metrics(p09_runs, 'B')
    lim = {}
    for arm, t0, t1 in (('A', ts_a0, ts_a1), ('B', ts_b0, ts_b1)):
        d = ab.med(t1.get('OPEN', [])) - ab.med(t0.get('OPEN', []))
        lim[f'L1 OPEN TS median |delta| <= 2 ticks (arm {arm}: {d:+.1f})'] = abs(d) <= 2
    for scen in ('CAT', 'PREEMPT', 'SEALED', 'VANISH'):
        d = ab.med(tf_b1.get(scen, [])) - ab.med(tf_b0.get(scen, []))
        lim[f'L2 B {scen} TF median delta <= +3 ticks ({d:+.1f})'] = d <= 3
    d = ab.med(ts_b1.get('CANCEL_ACT', [])) - ab.med(ts_b0.get('CANCEL_ACT', []))
    lim[f'L3 B CANCEL_ACT rerun TS median delta <= +2 ticks ({d:+.1f})'] = d <= 2
    d = ab.med(run1) - ab.med(run0)
    lim[f'L4 B runnerUs median <= base + 5 us ({ab.med(run0):.2f} -> {ab.med(run1):.2f}, {d:+.2f})'] = d <= 5
    lim[f"L5 B CAT use packets <= base ({uses0['CAT']} -> {uses1['CAT']})"] = uses1['CAT'] <= uses0['CAT']
    return lim


def main(argv):
    pairs = [a.split(',') for a in argv]
    base_runs = [ab.parse(b) for b, _ in pairs]
    p09_runs = [ab.parse(p) for _, p in pairs]
    baseline_exc = {what for r in base_runs for _, what in r['exc']}
    for (b, p), rb, rp in zip(pairs, base_runs, p09_runs):
        print(f'##### seed {rp["start"].get("seed")}: base {b} vs p09 {p}')
        c, diffs = parity([rb], [rp])
        print(f'  parity: {dict(c)}')
        for d in diffs:
            print('    ' + d)
    print('##### C1: Phase 08 gates on the P09 build (pooled)')
    res = ab.report('p09 pooled', p09_runs)
    print('=== gates (pooled) ===')
    c1 = ab.gates(res, baseline_exc)
    print('##### C1 reference: Phase 08 gates on the baseline build (pooled)')
    res0 = ab.report('base pooled', base_runs)
    print('=== gates (pooled) ===')
    ab.gates(res0, baseline_exc)
    print('##### C2: parity (pooled)')
    c, _ = parity(base_runs, p09_runs)
    print(f'  {dict(c)}')
    c2 = c['class_diff'] == 0 and c['outcome_diff'] == 0 and c['missing'] == 0
    print(f"  {'PASS' if c2 else 'FAIL'}  C2 per-row class parity, B outcome parity, no missing rows")
    ma, mb = res['A'][0], res['B'][0]
    safety = all((m['stop_leak1'] + m['stop_leakEnd'] + m['end_leak'] + m['held_after_lap'] + m['died']) == 0 for m in (ma, mb))
    print(f"  {'PASS' if safety else 'FAIL'}  S safety in both arms of the P09 build (no leak, no death)")
    print('##### performance limits (pooled)')
    lim = perf(base_runs, p09_runs)
    for k, v in lim.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}")
    if c1 == 'FAIL' or not c2 or not safety:
        cls = 'FAIL'
    elif c1 == 'PASS' and all(lim.values()):
        cls = 'PASS'
    else:
        cls = 'PARTIAL'
    print(f'##### Phase 09 class (correctness and performance; modularity evidence is judged separately): {cls}')


if __name__ == '__main__':
    main(sys.argv[1:])
