"""Anticheat hits for one parity run dir: Grim console fails, NCP info/log, Spartan log, LightAntiCheat log, kicks.
usage: python ac_extract.py RUN   -> one line summary"""
import glob, os, re, sys
from collections import Counter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runs')
ANSI = re.compile(r'\x1b\[[0-9;]*m')


def hits(run):
    d = os.path.join(ROOT, run)
    out = Counter()
    srv = ANSI.sub('', open(os.path.join(d, 'server.out'), encoding='utf-8', errors='replace').read())
    for m in re.finditer(r'Grim \S+ PBot failed (\S+)', srv):
        out['grim:' + m[1]] += 1
    for m in re.finditer(r'(?m)^.*PBot lost connection: (.*)$', srv):
        if 'Disconnected' not in m[1]:
            out['kick:' + m[1].strip()[:60]] += 1
    for m in re.finditer(r'Kicked PBot\s*:?\s*(.*)', srv):
        out['kick:' + re.sub(r'[^\x20-\x7e]', '', m[1]).strip()[:40]] += 1
    out['vanilla:moved'] += len(re.findall(r'moved too quickly|moved wrongly', srv))
    # NCP `ncp info PBot`: "[hh:mm:ss] check.name" then "Sum: N VLs." on the next line.
    for m in re.finditer(r'\] ([a-z]+(?:\.[a-z]+)+)\s*\n\W*Sum: ([\d.]+) VL', srv, re.I):
        out['ncp:' + m[1]] += float(m[2])
    # NCP's own log repeats what `ncp info` sums, so it is not counted twice.
    for f in glob.glob(os.path.join(d, 'plugin-Spartan', 'logs', '*')):
        for line in open(f, encoding='utf-8', errors='replace'):
            m = re.search(r'\(Player: PBot\), \(Check: ([^)]+)\)', line)
            if m:
                out['spartan:' + m[1]] += 1
    for f in glob.glob(os.path.join(d, 'plugin-LightAntiCheat', 'logs', '*')):
        for line in open(f, encoding='utf-8', errors='replace'):
            m = re.search(r'PBot Failed \[Check: ([^\]]+)\]', line)
            if m:
                out['lac:' + m[1]] += 1
    out = Counter({k: v for k, v in out.items() if v})
    return out


def loaded(run):
    srv = ANSI.sub('', open(os.path.join(ROOT, run, 'server.out'), encoding='utf-8', errors='replace').read())
    names = re.findall(r'Enabling (\S+) v', srv)
    errs = re.findall(r'(?:Error occurred while enabling|Could not load) .*?(\S+)', srv)
    return names, errs


if __name__ == '__main__':
    r = sys.argv[1]
    print(r, dict(hits(r)), 'enabled:', loaded(r)[0], 'errors:', loaded(r)[1])
