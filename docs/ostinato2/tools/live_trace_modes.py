"""Per-mode PlayerSim prediction error from historical live 26.3 SIMTRACE files (Tier-L, historical).

Usage: python live_trace_modes.py <simtrace dir> <out csv>
simtrace.csv rows: label,tick,dx,dy,dz,dvx,dvy,dvz,openDx,openDy,openDz,yawLag,groundPredicted,groundActual
Labels contain commas, so the last 13 fields are fixed and the rest is the label.
Mode per tick:
  jump/chain label, actual on ground  -> sprint-ground (run-up and landing roll; movers sprint)
  jump/chain label, actual airborne   -> sprint-jump-air
  climb label                         -> climb-jump (jump that grabs a ladder/vine; air and climb ticks mixed)
"""
import math
import sys
from collections import defaultdict


def pct(v, p):
    v = sorted(v)
    return v[max(0, min(len(v) - 1, math.ceil(p * len(v)) - 1))]


def main():
    d, out = sys.argv[1], sys.argv[2]
    lines = open(d + '/simtrace.csv').read().splitlines()[1:]
    groups = defaultdict(list)
    for line in lines:
        parts = line.split(',')
        label = ','.join(parts[:-13])
        f = parts[-13:]
        kind = label.split()[0]
        dx, dy, dz = float(f[1]), float(f[2]), float(f[3])
        ga = f[12] == 'true'
        gp = f[11] == 'true'
        if kind == 'climb':
            mode = 'climb-jump'
        else:
            mode = 'sprint-ground' if ga else 'sprint-jump-air'
        groups[mode].append((math.sqrt(dx * dx + dy * dy + dz * dz), math.hypot(dx, dz), abs(dy), ga != gp))
    rows = ['mode,n,p50,p95,p99,max,over0.05,groundMismatch']
    for mode, v in sorted(groups.items()):
        e = [x[0] for x in v]
        rows.append('%s,%d,%.4f,%.4f,%.4f,%.4f,%d,%d' % (mode, len(v), pct(e, .5), pct(e, .95), pct(e, .99), max(e),
                                                       sum(x > 0.05 for x in e), sum(x[3] for x in v)))
    summary = open(d + '/summary.csv').read().splitlines()[1:]
    kinds = defaultdict(list)
    for line in summary:
        parts = line.split(',')
        label = ','.join(parts[:-8])
        f = parts[-8:]
        kinds[label.split()[0]].append((f[0] == 'true', int(f[4]), float(f[5])))
    rows.append('kind,runs,ok,held,openLoopEnd_p50,openLoopEnd_p95,openLoopEnd_p99,openLoopEnd_max')
    for kind, v in sorted(kinds.items()):
        ol = [x[2] for x in v]
        rows.append('%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f' % (kind, len(v), sum(x[0] for x in v), sum(x[1] == -1 for x in v),
                                                       pct(ol, .5), pct(ol, .95), pct(ol, .99), max(ol)))
    open(out, 'w', newline='\n').write('\n'.join(rows) + '\n')
    print('\n'.join(rows))


main()
