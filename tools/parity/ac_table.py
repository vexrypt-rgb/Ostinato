"""Anticheat parity table: for each scenario x config, the real client's hits and the protocol bot's.
A hit counts against the bot only if the real client did not trip the same check (bot-only column).
usage: python ac_table.py SUFFIX"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ac_extract import hits, loaded, ROOT

CONFIGS = ["none", "grim", "grim+pl", "ncp", "ncp+pl", "spartan", "spartan+pl", "lac", "lac+pl"]
SCEN = ["pillar", "walk", "parkour", "ladder", "bridge", "voidbridge", "mine"]


def fmt(c):
    return ', '.join(f'{k} {v:g}' for k, v in sorted(c.items())) or 'none'


def main():
    suf = sys.argv[1]
    print('| Scenario | Config | plugins enabled | real client hits | protocol bot hits | bot-only checks | end real / bot |')
    print('|---|---|---|---|---|---|---|')
    for s in SCEN:
        for c in CONFIGS:
            r, b = f'{s}-real-{c}-{suf}', f'{s}-proto-{c}-{suf}'
            have = [os.path.exists(os.path.join(ROOT, x, 'server.out')) for x in (r, b)]
            if not all(have):
                print(f'| {s} | {c} | | {"not run" if not have[0] else ""} | {"not run" if not have[1] else ""} | | |')
                continue
            hr, hb = hits(r), hits(b)
            only = {k: v for k, v in hb.items() if k not in hr}
            en = '+'.join(loaded(b)[0]) or '-'
            from table import short_end
            print(f'| {s} | {c} | {en} | {fmt(hr)} | {fmt(hb)} | {fmt(only)} | {short_end(r)[0]} / {short_end(b)[0]} |')


if __name__ == '__main__':
    main()
