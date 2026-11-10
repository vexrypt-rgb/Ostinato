"""OAP Stage 1 diagnostic: planner CPU per budget arm, split by search kind.

usage: python oap_cpu_decomposition.py <dir> <label>

failedTrips: every search of a trip that did not reach the goal. Of reached trips: escalated (a repeated start, budget
raised above the arm's base), aheadPrimaryBound (plan-ahead that stopped on its primary budget), aheadOther (plan-ahead
that reached the goal, the horizon or exhausted), first (first searches). Not a gate: the gates are in
oap_budget_summary.py.
"""
import csv, sys
from collections import defaultdict
d, label = sys.argv[1], sys.argv[2]
trips = list(csv.DictReader(open(f"{d}/{label}-trips.csv")))
ss = list(csv.DictReader(open(f"{d}/{label}-searches.csv")))
ok = {}
for t in trips:
    ok[(t['seed'], t['family'], t['mode'], t['length'], t['n'], t['arm'])] = t['ok'] == 'true'
base_pa = {'base': 4000, 'a2000': 2000, 'a1000': 1000, 'a500': 500, 'a8000': 8000}
agg = defaultdict(lambda: defaultdict(float))
for s in ss:
    if s['type'] == 'CANCEL_LATENCY': continue
    arm = s['arm'].split('@')[1]
    k = (s['seed'], s['family'], s['mode'], s['length'], s['n'], s['arm'])
    c = float(s['cpuMs']) / 1e3
    a = agg[arm]
    a['all'] += c
    if not ok[k]: a['failedTrips'] += c; continue
    ahead = s['ahead'] == 'true'
    esc = int(s['primary']) > (base_pa[arm] if ahead else 500)
    if esc: a['escalated'] += c
    elif ahead and s['timedOut'] == 'true': a['aheadPrimaryBound'] += c
    elif ahead: a['aheadOther'] += c
    else: a['first'] += c
print(f"{label}: planner CPU seconds by search kind")
cols = ['all', 'failedTrips', 'escalated', 'aheadPrimaryBound', 'aheadOther', 'first']
print(f"{'arm':>6} " + " ".join(f"{c:>17}" for c in cols))
for arm in base_pa:
    if arm in agg: print(f"{arm:>6} " + " ".join(f"{agg[arm][c]:>17.1f}" for c in cols))
