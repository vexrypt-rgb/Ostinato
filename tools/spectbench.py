#!/usr/bin/env python3
"""Ostinato: scores a SpectateBench run (spectbench/truth.csv, decode.csv, health.csv).

Decoded intervals are matched to the server's truth by entity id and position (the packet carries the server's
position to 1/4096), so no clock is shared. For each decoded interval the true keys are read from the server ticks it
spans: "applied" from the movement impulse the server ran (xxa/zza, past 0.35 of a full press at the sneak/use
scale), "commanded" from the bot's own forward/strafing (carpet-pvp action pack or VexBot). Health: estimate against the synced value the estimator was
told to ignore (-Dostinato.health.blind=true).

usage: spectbench.py <dir>
"""
import csv, sys, math, collections, bisect

KEY = 0.35


def load(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def sign_key(v, full):
    return 1 if v > KEY * full else -1 if v < -KEY * full else 0


def main(d):
    truth = load(d + '/truth.csv')
    dec = load(d + '/decode.csv')
    by_id = collections.defaultdict(list)
    for r in truth:
        by_id[int(r['id'])].append(r)
    # Match each decoded row to the truth row of the same id, near in time (client and server tick clocks are a tick
    # or two apart on an integrated server), whose position is nearest; the packet position is the server position
    # rounded to the 1/4096 delta grid, so anything within a couple of grid steps is the same tick.
    ticks_of = {i: [int(r['stick']) for r in rows] for i, rows in by_id.items()}
    TOL = 4.0 / 4096
    stats = collections.Counter()
    conf_bins = collections.defaultdict(lambda: [0, 0])
    last = {}
    tick_err = collections.Counter()
    seen = set()
    for r in dec:
        i = int(r['id'])
        if i not in by_id:
            continue
        dk = (i, r['tick'], r['x'], r['y'], r['z'])
        if dk in seen:
            stats['dup'] += 1
            continue
        seen.add(dk)
        rows_i, st = by_id[i], ticks_of[i]
        t = int(r['tick'])
        lo, hi = bisect.bisect_left(st, t - 6), bisect.bisect_right(st, t + 2)
        x, y, z = float(r['x']), float(r['y']), float(r['z'])
        best, bd = None, TOL
        prev = last.get(i)
        for k in range(lo, hi):
            if prev is not None and k <= prev:
                continue
            q = rows_i[k]
            dd = max(abs(float(q['x']) - x), abs(float(q['y']) - y), abs(float(q['z']) - z))
            if dd < bd or (dd == bd and best is not None and k > best):
                best, bd = k, dd
        if best is None:
            stats['unmatched'] += 1
            last.pop(i, None)
            continue
        k_end = best
        last[i] = k_end
        if r['reset'] == '1' or prev is None:
            stats['reset'] += 1
            continue
        span = by_id[i][prev + 1:k_end + 1]
        if not span or len(span) > 6:
            stats['span_bad'] += 1
            continue
        true_ticks = len(span)
        tick_err[true_ticks - int(r['ticks'])] += 1
        rows = span
        def applied(t):
            full = 0.98 * (0.3 if t['shift'] == '1' else 1) * (0.2 if t['using'] == '1' else 1)
            return sign_key(float(t['zza']), full), sign_key(float(t['xxa']), full)
        def commanded(t):
            return sign_key(float(t['cmdF']), 1), sign_key(float(t['cmdS']), 1)
        ka = [applied(t) for t in rows]
        kc = [commanded(t) for t in rows]
        clean = len(set(ka)) == 1
        f_true, s_true = ka[-1]
        f_dec, s_dec = int(r['fwd']), int(r['str'])
        stats['n'] += 1
        stats['clean'] += clean
        ok = (f_dec, s_dec) == (f_true, s_true)
        stats['keys_ok'] += ok
        stats['fwd_ok'] += f_dec == f_true
        stats['str_ok'] += s_dec == s_true
        if clean:
            stats['clean_ok'] += ok
            stats['clean_fwd_ok'] += f_dec == f_true
            stats['clean_str_ok'] += s_dec == s_true
        stats['cmd_ok'] += (f_dec, s_dec) == kc[-1]
        # the same against the row before (a bot that sets its impulse after moving, for the next tick)
        sh = applied(by_id[i][k_end - 1])
        stats['shift_ok'] += (f_dec, s_dec) == sh
        # jump: the server ran a jump in the span (jumping held while it stood on the ground the tick before)
        jumped = False
        all_rows = by_id[i]
        for k in range(prev + 1, k_end + 1):
            if all_rows[k]['jumping'] == '1' and all_rows[k - 1]['onGround'] == '1':
                jumped = True
        if r['jumpObs'] == '1':
            stats['jump_n'] += 1
            stats['jump_ok'] += (r['jump'] == '1') == jumped
            stats['jump_true'] += jumped
            stats['jump_tp'] += jumped and r['jump'] == '1'
        stats['sprint_ok'] += (r['sprint'] == '1') == (rows[-1]['sprint'] == '1')
        stats['sneak_ok'] += (r['sneak'] == '1') == (rows[-1]['shift'] == '1')
        c = float(r['conf'])
        b = min(4, int(c * 5))
        conf_bins[b][0] += 1
        conf_bins[b][1] += ok
        stats['work'] += int(r['work'])
    n = max(1, stats['n'])
    print('decode: %d intervals matched (%d resets, %d unmatched, %d bad spans, %d duplicate rows)' % (stats['n'], stats['reset'], stats['unmatched'], stats['span_bad'], stats['dup']))
    print('  W/A/S/D exact (applied impulse): %.3f   forward %.3f  strafe %.3f' % (stats['keys_ok'] / n, stats['fwd_ok'] / n, stats['str_ok'] / n))
    cl = max(1, stats['clean'])
    print('  on %d intervals with no key change inside: %.3f   forward %.3f  strafe %.3f' % (stats['clean'], stats['clean_ok'] / cl, stats['clean_fwd_ok'] / cl, stats['clean_str_ok'] / cl))
    print('  against the bot\'s commanded keys: %.3f; against the impulse logged a tick earlier: %.3f' % (stats['cmd_ok'] / n, stats['shift_ok'] / n))
    jn = max(1, stats['jump_n'])
    print('  jump correct %.3f on %d observable intervals (%d true jumps, recall %.3f)' % (stats['jump_ok'] / jn, stats['jump_n'], stats['jump_true'], stats['jump_tp'] / max(1, stats['jump_true'])))
    print('  sprint %.3f  sneak %.3f' % (stats['sprint_ok'] / n, stats['sneak_ok'] / n))
    print('  interval length (true - assumed): %s' % dict(sorted(tick_err.items())))
    print('  sim ticks per interval: %.1f' % (stats['work'] / n))
    for b in sorted(conf_bins):
        t, o = conf_bins[b]
        print('  confidence %.1f-%.1f: %d intervals, %.3f exact' % (b / 5, (b + 1) / 5, t, o / max(1, t)))
    try:
        h = load(d + '/health.csv')
    except FileNotFoundError:
        return
    # Carpet bots get healed with no packet that shows it (synced health jumps by more than 2 in a tick with no death):
    # samples from such a heal until the entity's next death are scored apart.
    taint = collections.defaultdict(list)  # id -> [(from, to)]
    for i, rows in by_id.items():
        start = None
        for a_, b_ in zip(rows, rows[1:]):
            ha, hb = float(a_['hp']), float(b_['hp'])
            if start is None and ha > 0 and hb - ha > 2:
                start = int(b_['stick'])
            if start is not None and (hb <= 0 or b_['round'] != a_['round']):
                taint[i].append((start, int(b_['stick'])))
                start = None
        if start is not None:
            taint[i].append((start, 1 << 30))
    def tainted(i, t):
        return any(a_ - 3 <= t <= b_ + 3 for a_, b_ in taint.get(i, ()))
    errs_t = []
    errs, errs_h, by_src = [], [], collections.defaultdict(list)
    calib = [0, 0]
    for r in h:
        if not r['name'].startswith('vx'):
            continue
        syn = float(r['synced']) + float(r['syncedAbs'])
        if float(r['synced']) <= 0:
            continue
        est = float(r['est']) + float(r['abs'])
        e = abs(est - syn)
        if tainted(int(r['id']), int(r['tick'])):
            errs_t.append(e)
            continue
        errs.append(e)
        errs_h.append(abs(float(r['est']) - float(r['synced'])))
        by_src[r['source']].append(e)
        sd = float(r['sd'])
        calib[0] += 1
        calib[1] += e <= sd + 0.5
    if errs:
        errs.sort()
        print('health: %d samples, total (hp+abs) MAE %.2f, median %.2f, p90 %.2f, max %.2f; health-only MAE %.2f; within sd+0.5: %.2f' % (
            len(errs), sum(errs) / len(errs), errs[len(errs) // 2], errs[int(len(errs) * 0.9)], errs[-1], sum(errs_h) / len(errs_h), calib[1] / calib[0]))
        if errs_t:
            print('  excluded %d samples after invisible bot heals: MAE %.2f there' % (len(errs_t), sum(errs_t) / len(errs_t)))
        for s, v in by_src.items():
            print('  %s: %d samples MAE %.2f' % (s, len(v), sum(v) / len(v)))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'spectbench')
