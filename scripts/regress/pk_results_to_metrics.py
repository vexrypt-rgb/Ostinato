#!/usr/bin/env python3
# Ostinato: regression suite live stage helper (docs/REGRESSION.md). New file.
"""Turn one PkTest run (run/pk-results/<world>-<mode>-<stamp>.txt and .csv, plus the client log for GSTAT lines)
into a regression-suite metrics file (the StageResult JSON shape), so a live-client course run compares against
the baseline like the headless stages.

    pk_results_to_metrics.py --txt <pk-results/...txt> [--log latest.log] --out <metrics dir> [--name pk_drill] [--commit sha]
"""
import argparse, csv, json, os, re

ap = argparse.ArgumentParser()
ap.add_argument('--txt', required=True)
ap.add_argument('--log')
ap.add_argument('--out', required=True)
ap.add_argument('--name')
ap.add_argument('--commit', default='')
a = ap.parse_args()

line = open(a.txt, encoding='utf-8').read().strip().splitlines()[-1]
kv = dict(re.findall(r'(\w+)=(\S+)', line.split(' byTag=')[0]))
mode = kv.get('mode', 'kinematic')
name = a.name or os.path.basename(kv.get('world', 'course'))
reached, goals = (int(x) for x in kv.get('goals', '0/0').split('/'))
jok, jall = (int(x) for x in kv.get('jumps', '0/0').split('/'))

def metric(n, v, better='info', sev='warn', abs_=0, rel=0, unit=''):
    return {'name': n, 'value': v, 'better': better, 'severity': sev, 'abs': abs_, 'rel': rel, 'unit': unit}

r = {'stage': f'live:pktest:{name}:{mode}', 'kind': 'live', 'status': 'ok', 'note': 'PkTest in a live client',
     'commit': a.commit, 'metrics': [
         metric('goals', goals), metric('goals_reached', reached, 'higher', 'fail'),
         metric('jumps_ok', jok, 'higher', 'warn', 1, 0.05), metric('jumps', jall),
         metric('falls', int(kv.get('falls', 0)), 'lower', 'warn', 1, 0.2),
         metric('skipped_goals', int(kv.get('skippedGoals', 0)), 'lower', 'fail'),
         metric('minutes', int(kv.get('min', 0)), 'lower', 'warn', 1, 0.5, 'min')],
     'cases': {}}
if a.log and os.path.exists(a.log):  # per-goal outcome from the GSTAT lines
    for m in re.finditer(r'PK GSTAT mode=\S+ goal=(\d+) ok=(true|false) astarMs=([\d.]+).*?travelS=([\d.]+)', open(a.log, encoding='utf-8', errors='replace').read()):
        r['cases'][f'goal{int(m.group(1)):03d}'] = {'outcome': 'ok' if m.group(2) == 'true' else 'fail',
                                                    'ms': float(m.group(3)), 'detail': f'travel {m.group(4)} s'}
csvp = a.txt[:-4] + '.csv'
if os.path.exists(csvp):  # jump success by class, as info metrics
    by = {}
    for row in csv.DictReader(open(csvp, encoding='utf-8')):
        t = by.setdefault(row['class'], [0, 0]); t[0] += 1; t[1] += int(row['ok'])
    for k, (n, ok) in sorted(by.items()):
        r['metrics'].append(metric(f'jumps_ok_{k}', ok, 'info'))
os.makedirs(a.out, exist_ok=True)
fn = re.sub(r'[^A-Za-z0-9._-]', '_', r['stage']) + '.json'
json.dump(r, open(os.path.join(a.out, fn), 'w'), indent=2)
print('wrote', os.path.join(a.out, fn))
