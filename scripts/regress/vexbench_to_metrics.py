#!/usr/bin/env python3
# Ostinato: regression suite live stage helper (docs/REGRESSION.md). New file.
"""Turn one VexBench run (pvpbench/vexbench.csv, one row per round) into the combat:vexbench metrics file (the
StageResult JSON shape), so a live combat bench run compares against the baseline like the headless stages.
Tolerances are wide on purpose: at 48 rounds a win rate's standard error is about 0.07, so only a drop past
0.15 fails; damage moves warn. A real A/B still wants paired runs and a significance test (acs-farm fisher.py).

    vexbench_to_metrics.py --csv <run>/pvpbench/vexbench.csv --out <metrics dir> [--opponent smpbot] [--commit sha]
"""
import argparse, csv, json, os

ap = argparse.ArgumentParser()
ap.add_argument('--csv', required=True)
ap.add_argument('--out', required=True)
ap.add_argument('--opponent', default='')
ap.add_argument('--commit', default='')
a = ap.parse_args()

def metric(n, v, better='info', sev='warn', abs_=0, rel=0, unit=''):
    return {'name': n, 'value': round(v, 4), 'better': better, 'severity': sev, 'abs': abs_, 'rel': rel, 'unit': unit}

rows = list(csv.DictReader(open(a.csv, encoding='utf-8')))
stage = 'combat:vexbench'
out = {'stage': stage, 'kind': 'combat', 'commit': a.commit, 'metrics': [], 'cases': {}}
if not rows:
    out.update(status='skipped', note='vexbench.csv has no rounds')
else:
    n = len(rows)
    f = lambda k: sum(float(r[k]) for r in rows) / n
    wins = sum(r['result'] == 'win' for r in rows)
    attacks = sum(int(r['attacks']) for r in rows)
    hurts = sum(int(r.get('srvBotHurts') or 0) for r in rows)
    out.update(status='ok', note=f'VexBench vs {a.opponent or "?"}, {n} rounds', metrics=[
        metric('rounds', n),
        metric('win_rate', wins / n, 'higher', 'fail', 0.15, 0),
        metric('dmg_taken', f('dmgTaken'), 'lower', 'warn', 4, 0, 'hp'),
        metric('dmg_dealt_server', f('sDealt'), 'higher', 'warn', 4, 0, 'hp'),
        metric('crit_share', sum(int(r['crits']) for r in rows) / max(1, attacks), 'higher', 'warn', 0.05, 0),
        metric('hits_per_attack_server', hurts / max(1, attacks), 'higher', 'warn', 0.05, 0),
        metric('gapples', f('gapples'))])
    for k in sorted({(r['kit'], r['diff']) for r in rows}):
        g = [r for r in rows if (r['kit'], r['diff']) == k]
        w = sum(r['result'] == 'win' for r in g)
        out['cases'][f'{a.opponent or "bench"}/{k[0]}/{k[1]}'] = {'outcome': 'info', 'detail': f'{w}/{len(g)} wins'}
os.makedirs(a.out, exist_ok=True)
json.dump(out, open(os.path.join(a.out, stage.replace(':', '_') + '.json'), 'w'), indent=2)
print(f'{stage}: {out.get("note", "")}')
