#!/usr/bin/env python3
"""Ostinato 2.0 Phase 08: read InteractLiveTest runs and apply the pre-registered gates (BENCHMARKS.md, Phase 08).

  python docs/ostinato2/tools/phase08/interact_ab.py --extract <client.log> <out-lines.txt>
      keeps only the INTERACTTEST lines and exception headlines (with their first frame) of a client log
  python docs/ostinato2/tools/phase08/interact_ab.py <lines.txt> [<lines.txt> ...]
      per seed and pooled: classes per scenario and arm, the metrics, and the gate verdicts

Arm A runs with verifiedGetToBlock off, arm B with it on (flag=present); with flag=absent both arms are legacy.
The oracle is the harness's server-side check (cls): TS true success, FS false success, TF true failure, FF false
failure, HANG no verdict within the timeout.
"""
import re
import statistics
import sys
from collections import Counter, defaultdict

POSSIBLE = {('OPEN', 'main'), ('REPLACE', 'main'), ('REPEAT', 'main'), ('CANCEL_MOVE', 'rerun'), ('CANCEL_ACT', 'rerun')}
IMPOSSIBLE = {('CAT', 'main'), ('VANISH', 'main'), ('SEALED', 'main'), ('PREEMPT', 'main')}
KV = re.compile(r'(\w+)=("[^"]*"|\S+)')
EXC = re.compile(r'^((?:[a-z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error))\b')


def extract(src, dst):
    out = []
    lines = open(src, encoding='utf-8', errors='replace').read().splitlines()
    for i, line in enumerate(lines):
        if 'INTERACTTEST ' in line:
            out.append(line[line.index('INTERACTTEST '):])
            continue
        m = EXC.match(line.strip())
        if m:
            frame = next((l.strip() for l in lines[i + 1:i + 3] if l.strip().startswith('at ')), 'at ?')
            out.append('EXCEPTION ' + m.group(1) + ' ' + frame)
    open(dst, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
    print(f'{len(out)} lines -> {dst}')


def parse(path):
    run = {'rows': [], 'stops': [], 'ends': [], 'held': [], 'exc': [], 'start': {}, 'arm_of': {}}
    lap = None
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if line.startswith('EXCEPTION '):
            run['exc'].append((lap, line[len('EXCEPTION '):]))
            continue
        if not line.startswith('INTERACTTEST '):
            continue
        body = line[len('INTERACTTEST '):]
        kv = {k: v.strip('"') for k, v in KV.findall(body)}
        if body.startswith('start '):
            run['start'] = kv
        elif body.startswith('begin '):
            lap = int(kv['lap'])
            run['arm_of'][lap] = kv['arm']
        elif body.startswith('keys still held after lap'):
            n = int(re.search(r'after lap (\d+)', body).group(1))
            run['held'].append((n, body.split(':', 1)[1].strip()))
        elif body.startswith('row '):
            run['arm_of'].setdefault(int(kv['lap']), kv['arm'])
            part = kv.get('part')
            if part == 'stop':
                run['stops'].append(kv)
            elif part == 'end':
                run['ends'].append(kv)
            else:
                run['rows'].append(kv)
    return run


def med(xs):
    return statistics.median(xs) if xs else float('nan')


def outcome_state(kv):
    o = kv.get('outcome', '')
    return o.split(':', 1)[1].split('/')[0] if ':' in o else None


def metrics(runs, arm):
    m = Counter()
    cls_by = defaultdict(Counter)
    ticks_ts = defaultdict(list)
    ticks_tf = defaultdict(list)
    uses = Counter()
    runner = []
    agree = Counter()
    exc = Counter()
    for run in runs:
        for r in run['rows']:
            if r['arm'] != arm:
                continue
            key = (r['scen'], r['part'])
            c = r['cls']
            cls_by[r['scen'] + ('/' + r['part'] if r['part'] != 'main' else '')][c] += 1
            m['rows'] += 1
            if 'DIED' in r.get('verdict', ''):
                m['died'] += 1
            if c == 'FS':
                m['FS'] += 1
            if c == 'HANG':
                m['HANG'] += 1
            if c == 'FF':
                m['FF'] += 1
            if key in POSSIBLE:
                m['possible'] += 1
                if c == 'TS':
                    m['possible_TS'] += 1
                    ticks_ts[r['scen']].append(int(r['ticks']))
                if c in ('TF', 'FF'):
                    m['false_fail'] += 1
                if r['scen'] in ('OPEN', 'REPLACE', 'CANCEL_MOVE'):
                    m['legacy_ok_rows'] += 1
                    m['legacy_ok_TS'] += c == 'TS'
            if key in IMPOSSIBLE:
                m['impossible'] += 1
                if c == 'TF':
                    m['impossible_TF'] += 1
                    ticks_tf[r['scen']].append(int(r['ticks']))
            uses[r['scen']] += int(r.get('uses', 0))
            st = outcome_state(r)
            if st in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'):
                agree['terminal'] += 1
                if (st == 'SUCCEEDED') != (c == 'TS'):
                    agree['mismatch'] += 1
            else:
                agree['skipped'] += 1
        for s in run['stops']:
            if s['arm'] != arm:
                continue
            m['stops'] += 1
            if s.get('keys1', '-') != '-' or s.get('active1') == 'true':
                m['stop_leak1'] += 1
            if s.get('keysEnd', '-') != '-' or s.get('activeEnd') == 'true':
                m['stop_leakEnd'] += 1
            st = outcome_state(s)
            if 'outcome' in s and st not in ('CANCELLED', 'UNKNOWN'):
                m['stop_bad_outcome'] += 1
            if s['scen'] == 'CANCEL_ACT' and s.get('lateOpen') == 'true':
                m['late_open'] += 1
                if 'outcome' in s and st != 'UNKNOWN':
                    m['late_open_not_unknown'] += 1
        for e in run['ends']:
            if e['arm'] != arm:
                continue
            m['ends'] += 1
            if e.get('keysEnd', '-') != '-' or e.get('activeEnd') == 'true':
                m['end_leak'] += 1
            runner.append(float(e['runnerUs']))
        for lap, keys in run['held']:
            if run['arm_of'].get(lap) == arm and keys not in ('', '-'):
                m['held_after_lap'] += 1
        for lap, what in run['exc']:
            if lap is not None and run['arm_of'].get(lap) == arm:
                exc[what] += 1
    return m, cls_by, ticks_ts, ticks_tf, uses, runner, agree, exc


def report(label, runs):
    print(f'=== {label} ===')
    for run in runs:
        st = run['start']
        print(f"  seed={st.get('seed')} flag={st.get('flag')} laps={st.get('laps')} reps={st.get('reps')}")
    res = {}
    for arm in ('A', 'B'):
        m, cls_by, ticks_ts, ticks_tf, uses, runner, agree, exc = metrics(runs, arm)
        res[arm] = (m, ticks_ts, uses, runner, agree, exc)
        print(f'  arm {arm}:')
        for scen in sorted(cls_by):
            print(f'    {scen:18s} ' + ' '.join(f'{k}={v}' for k, v in sorted(cls_by[scen].items())))
        comp = m['possible_TS'] / m['possible'] if m['possible'] else float('nan')
        print(f"    completion {m['possible_TS']}/{m['possible']} = {comp:.3f}; FS={m['FS']} false-fail={m['false_fail']} "
              f"FF={m['FF']} HANG={m['HANG']} died={m['died']}; impossible TF {m['impossible_TF']}/{m['impossible']}")
        print(f"    leaks: stop1={m['stop_leak1']} stopEnd={m['stop_leakEnd']} end={m['end_leak']} heldAfterLap={m['held_after_lap']}; "
              f"stops={m['stops']} badStopOutcome={m['stop_bad_outcome']} lateOpen={m['late_open']} lateOpenNotUnknown={m['late_open_not_unknown']}")
        print('    TS ticks median: ' + ' '.join(f'{s}={med(v)}' for s, v in sorted(ticks_ts.items())))
        print('    TF ticks median: ' + ' '.join(f'{s}={med(v)}' for s, v in sorted(ticks_tf.items())))
        print('    uses: ' + ' '.join(f'{s}={v}' for s, v in sorted(uses.items())))
        print(f'    runnerUs median {med(runner):.2f} (n={len(runner)}); outcome vs oracle: {dict(agree)}')
        print('    exceptions: ' + (', '.join(f'{k} x{v}' for k, v in exc.items()) or 'none'))
    return res


def gates(res, baseline_exc):
    a, b = res['A'], res['B']
    ma, mb = a[0], b[0]
    g = {}
    g['G1 no false success (B FS=0)'] = mb['FS'] == 0
    comp = mb['possible_TS'] / mb['possible'] if mb['possible'] else 0
    g['G2 completion (B >= 0.95, no loss where legacy is right)'] = comp >= 0.95 and mb['legacy_ok_TS'] >= ma['legacy_ok_TS']
    g['G3 false failures (B <= 1)'] = mb['false_fail'] <= 1  # TF or FF where the open was possible
    g['G4 liveness (B HANG=0)'] = mb['HANG'] == 0
    g['G5 safety (B no leak, no death)'] = (mb['stop_leak1'] + mb['stop_leakEnd'] + mb['end_leak'] + mb['held_after_lap'] + mb['died']) == 0
    known = set(a[5]) | set(baseline_exc)
    g['G6 no new exception in B'] = all(k in known for k in b[5])
    g['G7 cancel outcome honest'] = mb['stop_bad_outcome'] == 0 and mb['late_open_not_unknown'] == 0
    g['G8 outcome agrees with oracle'] = b[4]['mismatch'] == 0
    open_a, open_b = a[1].get('OPEN', []), b[1].get('OPEN', [])
    g['P1 OPEN ticks (B-A median <= 5) and runner (B <= A+25us)'] = (
        bool(open_a) and bool(open_b) and med(open_b) - med(open_a) <= 5 and med(b[3]) <= med(a[3]) + 25)
    g['P2 no extra clicks on a refused open (CAT uses B <= A)'] = b[2]['CAT'] <= a[2]['CAT']
    for k, v in g.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}")
    hard = all(g[k] for k in g if k.startswith(('G1', 'G4', 'G5', 'G6')))
    if not hard:
        cls = 'FAIL'
    elif all(g.values()):
        cls = 'PASS'
    else:
        cls = 'PARTIAL'
    print(f'  class: {cls}')
    return cls


def main(argv):
    if argv and argv[0] == '--extract':
        extract(argv[1], argv[2])
        return
    baseline = []
    if argv and argv[0].startswith('--baseline='):
        baseline = [parse(p) for p in argv[0].split('=', 1)[1].split(',')]
        argv = argv[1:]
    baseline_exc = {what for r in baseline for _, what in r['exc']}
    runs = [parse(p) for p in argv]
    for p, run in zip(argv, runs):
        report(p, [run])
    if len(runs) > 1:
        res = report('pooled', runs)
    else:
        res = report(argv[0] + ' (gates)', runs)
    print('=== gates (pooled) ===')
    gates(res, baseline_exc)


if __name__ == '__main__':
    main(sys.argv[1:])
