# Ostinato 2.0 Phase 06, Tier S: the headless batch (UnifiedBatchBench), legacy against unified, judged against the
# pre-registered criteria H1-H7 in docs/ostinato2/BENCHMARKS.md ("Phase 06 pre-registration"), and Phase 06A's R1, R2
# and slip mechanism check ("Phase 06A: executor safety and reliability recovery").
# Usage: python scripts/unified-batch.py docs/ostinato2/raw/phase06a/headless-batch.csv
# A Phase 06 CSV (no retry columns) still loads; the Phase 06A checks then find nothing exercised.
import csv
import math
import random
import statistics
import sys

RESAMPLES = 10000
BOOT_SEED = 20261108
OVERHEAD_MS = 0.5
FAST, SLOW = 0.97, 1.03
DISTURBED = ("kick", "slip", "conflict", "edge")
NON_CANCEL = ("clean", "kick", "slip", "conflict", "edge")
STATE_FOR = {"ARRIVED": "COMPLETED_VERIFIED", "CANCEL_STOP": "CANCELLED_CONFIRMED", "EMPTY_PLAN": "FAILED"}
INTS = ("trial", "order", "ticks", "plans", "driftReplans", "collided", "kickedAt", "cancelFrom", "cancelRefusals",
        "cancelLatency", "preLanded", "conflictLanded", "jumps", "sprints", "tickN", "tickP50Ns", "tickP95Ns", "tickP99Ns",
        "tickMaxNs", "planNs", "cpuNs", "allocBytes", "retryNs", "retryNsMax")
COUNTERS = ("ACQUIRES", "ACQUIRE_REFUSED", "COMPLETIONS", "HANDOVERS", "FAILURES", "CANCELS", "CANCEL_STOPS",
            "CANCEL_REFUSALS", "STALE_REJECTS", "PREEMPTIONS", "UNKNOWNS", "CONFLICTS", "ERASED", "OVERWRITTEN",
            "RELEASED_EARLY", "INTACT_TICKS", "OWNED_TICKS", "DIVERGENCE_CHECKS", "DIVERGENCES",
            "PREDICTION_UNAVAILABLE", "CORRECTIONS", "CORRECTIONS_ACCEPTED", "ESCALATIONS", "CANCEL_LATENCY_SUM",
            "CANCEL_LATENCY_MAX", "PROCESS_CANCELS", "CORRECTION_RETRIES", "CORRECTION_RETRY_PLANS")
# Phase 06A, R2 (milliseconds): bounds on the per-run largest retry and largest tick, over unified runs that retried.
RETRY_P95_MS, RETRY_MAX_MS = 150, 250
TICK_P95_MS, TICK_MAX_MS = 250, 400


def boot(xs, stat):
    r = random.Random(BOOT_SEED)
    s = sorted(stat([r.choice(xs) for _ in xs]) for _ in range(RESAMPLES))
    return s[int(0.025 * RESAMPLES)], s[int(0.975 * RESAMPLES) - 1]


def load(path):
    pairs = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            for k in INTS + COUNTERS:
                if k in row and row[k] != "":
                    row[k] = int(row[k])
            row["onGroundAtEnd"] = row["onGroundAtEnd"] == "true"
            row["leased"] = row["leased"] == "true"
            row["endX"], row["endY"], row["endZ"] = float(row["endX"]), float(row["endY"]), float(row["endZ"])
            pairs.setdefault((row["family"], row["trial"]), {})[row["arm"]] = row
    missing = [k for k, v in pairs.items() if set(v) != {"legacy", "unified"}]
    if missing:
        sys.exit(f"unpaired trials: {missing[:10]}")
    return pairs


def families(pairs):
    return list(dict.fromkeys(f for f, _ in pairs))


def h1(pairs):
    print("\n## H1 clean: the flag changes nothing where nothing pushes the player")
    bad = []
    n = 0
    for (fam, t), p in pairs.items():
        if fam != "clean":
            continue
        n += 1
        l, u = p["legacy"], p["unified"]
        same = all(l[k] == u[k] for k in ("end", "ticks", "plans", "driftReplans", "endX", "endY", "endZ", "jumps", "sprints"))
        if not same or u["DIVERGENCES"] != 0 or l["driftReplans"] != 0:
            bad.append((t, l["end"], u["end"], l["ticks"], u["ticks"], l["plans"], u["plans"], u["DIVERGENCES"], l["driftReplans"]))
    ends = {}
    for (fam, t), p in pairs.items():
        if fam == "clean":
            ends[p["legacy"]["end"]] = ends.get(p["legacy"]["end"], 0) + 1
    print(f"clean trials {n}; legacy ends {ends}; mismatches {len(bad)} {bad[:10]}")
    ok = n > 0 and not bad
    print(f"H1: {'holds' if ok else 'FAILS'}")
    return ok


def h2(pairs):
    print("\n## H2 reliability: arrivals per family, unified against legacy")
    ok = True
    refused = 0
    print("| family | n | legacy arrived | unified arrived | legacy ends | unified ends | allowed loss | holds |")
    print("|---|---|---|---|---|---|---|---|")
    for fam in families(pairs):
        ps = [p for (f, _), p in pairs.items() if f == fam]
        n = len(ps)
        le, ue = {}, {}
        for p in ps:
            le[p["legacy"]["end"]] = le.get(p["legacy"]["end"], 0) + 1
            ue[p["unified"]["end"]] = ue.get(p["unified"]["end"], 0) + 1
            refused += (p["legacy"]["end"] == "REFUSED") + (p["unified"]["end"] == "REFUSED")
        allowed = math.ceil(0.01 * n)
        if fam == "cancel":
            # a cancel stop is the point; what counts is ending anywhere but arrived or stopped
            lbad = sum(v for k, v in le.items() if k != "ARRIVED")
            ubad = sum(v for k, v in ue.items() if k not in ("ARRIVED", "CANCEL_STOP"))
            holds = ubad <= lbad + allowed
            la, ua = le.get("ARRIVED", 0), ue.get("ARRIVED", 0) + ue.get("CANCEL_STOP", 0)
        else:
            la, ua = le.get("ARRIVED", 0), ue.get("ARRIVED", 0)
            holds = ua >= la - allowed
        ok &= holds
        print(f"| {fam} | {n} | {la} | {ua}{' (incl. stops)' if fam == 'cancel' else ''} | {le} | {ue} | {allowed} | {'yes' if holds else 'NO'} |")
    if refused:
        print(f"REFUSED ends (the executor refused its only driver): {refused}")
    ok &= refused == 0
    print(f"H2: {'holds' if ok else 'FAILS'}")
    return ok


def h3(pairs):
    print("\n## H3 safety, every unified trial")
    v = {k: [] for k in ("RELEASED_EARLY", "OVERWRITTEN", "STALE_REJECTS", "conflictLanded", "keysDown", "leased", "state", "cancelAir")}
    timeouts = 0
    for (fam, t), p in pairs.items():
        u = p["unified"]
        tag = f"{fam}-{t}"
        for k in ("RELEASED_EARLY", "OVERWRITTEN", "STALE_REJECTS", "conflictLanded"):
            if u[k] != 0:
                v[k].append((tag, u[k]))
        if u["end"] == "TIMEOUT":
            timeouts += 1  # the harness does not hand the lease back on a timeout; keys and lease are not judged
            continue
        if u["keysDown"]:
            v["keysDown"].append((tag, u["end"], u["keysDown"]))
        if u["leased"]:
            v["leased"].append((tag, u["end"]))
        if STATE_FOR.get(u["end"]) != u["state"]:
            v["state"].append((tag, u["end"], u["state"], u["cause"]))
        if u["end"] == "CANCEL_STOP" and not u["onGroundAtEnd"]:
            v["cancelAir"].append((tag, u["endY"]))
    for k, xs in v.items():
        print(f"{k}: {len(xs)} {xs[:10]}")
    print(f"unified TIMEOUT ends (keys and lease not judged): {timeouts}")
    conflicts = sum(p["unified"]["CONFLICTS"] for p in pairs.values())
    erased = sum(p["unified"]["ERASED"] for p in pairs.values())
    print(f"diagnostic: CONFLICTS refused {conflicts}, ERASED {erased}; legacy conflictLanded "
          f"{sum(p['legacy']['conflictLanded'] for p in pairs.values())}")
    ok = not any(v.values())
    print(f"H3: {'holds' if ok else 'FAILS'}")
    return ok


def h4(pairs):
    print("\n## H4 the correction is bounded and counted")
    bad = []
    for (fam, t), p in pairs.items():
        u = p["unified"]
        if not (u["CORRECTIONS"] <= u["plans"] and u["ESCALATIONS"] == u["driftReplans"]
                and u["CORRECTIONS_ACCEPTED"] <= u["CORRECTIONS"]):
            bad.append((f"{fam}-{t}", u["CORRECTIONS"], u["CORRECTIONS_ACCEPTED"], u["plans"], u["ESCALATIONS"], u["driftReplans"]))
        if any(p["legacy"].get(c, 0) != 0 for c in COUNTERS):
            bad.append((f"{fam}-{t}", "legacy counters moved"))
    print("| family | divergences | corrections | accepted | escalations | unified driftReplans | legacy driftReplans |")
    print("|---|---|---|---|---|---|---|")
    for fam in families(pairs):
        ps = [p for (f, _), p in pairs.items() if f == fam]
        s = lambda arm, k: sum(p[arm][k] for p in ps)
        print(f"| {fam} | {s('unified', 'DIVERGENCES')} | {s('unified', 'CORRECTIONS')} | {s('unified', 'CORRECTIONS_ACCEPTED')} | "
              f"{s('unified', 'ESCALATIONS')} | {s('unified', 'driftReplans')} | {s('legacy', 'driftReplans')} |")
    print(f"violations {len(bad)} {bad[:10]}")
    ok = not bad
    print(f"H4: {'holds' if ok else 'FAILS'}")
    return ok


def h5(pairs):
    print("\n## H5 disturbed families: plans and ticks, paired unified - legacy")
    print("| family | n | plans mean diff | 95% CI | fewer replans | arrived pairs | ticks mean ratio | 95% CI | median | faster/equal/slower | regression |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    regress = False
    fewer = {}
    faster = {}
    for fam in DISTURBED:
        ps = [p for (f, _), p in pairs.items() if f == fam]
        if not ps:
            continue
        d = [p["unified"]["plans"] - p["legacy"]["plans"] for p in ps]
        lo, hi = boot(d, statistics.mean)
        fewer[fam] = hi < 0
        rs = [p["unified"]["ticks"] / p["legacy"]["ticks"] for p in ps
              if p["unified"]["end"] == p["legacy"]["end"] == "ARRIVED"]
        if rs:
            rlo, rhi = boot(rs, statistics.mean)
            reg = rlo > SLOW
            faster[fam] = rhi < FAST
            cmp = f"{sum(r < 1 for r in rs)}/{sum(r == 1 for r in rs)}/{sum(r > 1 for r in rs)}"
            rtxt = f"{statistics.mean(rs):.4f} | {rlo:.4f}..{rhi:.4f} | {statistics.median(rs):.4f} | {cmp}"
        else:
            reg = False
            faster[fam] = False
            rtxt = "- | - | - | -"
        regress |= reg
        print(f"| {fam} | {len(ps)} | {statistics.mean(d):+.3f} | {lo:+.3f}..{hi:+.3f} | {'yes' if hi < 0 else 'no'} | {len(rs)} | {rtxt} | {'YES' if reg else 'no'} |")
    print(f"fewer replans (CI upper < 0): {[f for f, v in fewer.items() if v]}; faster (Tier S, CI upper < {FAST}): {[f for f, v in faster.items() if v]}")
    print(f"H5: {'REGRESSION' if regress else 'no regression'}")
    return not regress, fewer, faster


def h6(pairs):
    print("\n## H6 cancel latency (ticks from the first request to the stop; legacy waits for the run to end)")
    ps = [p for (f, _), p in pairs.items() if f == "cancel" and p["unified"]["cancelFrom"] > 0]
    if not ps:
        print("no trial asked to cancel before it arrived")
        print("H6: FAILS (nothing measured)")
        return False
    ul = [p["unified"]["cancelLatency"] for p in ps]
    no_stop = [(p["unified"]["trial"], p["unified"]["end"]) for p in ps if p["unified"]["cancelLatency"] < 0]
    defined = [x for x in ul if x >= 0]
    both = [(p["unified"]["cancelLatency"], p["legacy"]["cancelLatency"]) for p in ps
            if p["unified"]["cancelLatency"] >= 0 and p["legacy"]["cancelLatency"] >= 0]
    worse = [(u, l) for u, l in both if u > l]
    long_legacy = [(u, l) for u, l in both if l >= 5]
    lower = sum(u < l for u, l in long_legacy)
    legacy_undefined = sum(p["legacy"]["cancelLatency"] < 0 for p in ps)
    med = statistics.median(defined) if defined else float("inf")
    mx = max(defined) if defined else float("inf")
    lmed = statistics.median([l for _, l in both]) if both else float("nan")
    frac = lower / len(long_legacy) if long_legacy else 1.0
    ends = {}
    for p in ps:
        ends[p["unified"]["end"]] = ends.get(p["unified"]["end"], 0) + 1
    print(f"trials with a request {len(ps)}; unified ends {ends}; unified latency median {med} max {mx}; legacy median {lmed} "
          f"(legacy never stopped while driving in {legacy_undefined})")
    print(f"unified without a stop or arrival after the request {len(no_stop)} {no_stop[:10]}; unified slower than legacy {len(worse)} {worse[:10]}")
    print(f"legacy latency >= 5: {len(long_legacy)} trials, unified strictly lower in {lower} ({frac:.1%})")
    # unified slower than legacy is reported, not gated: a mid-air request waits for the landing, and legacy can arrive first
    ok = not no_stop and med <= 10 and mx <= 20 and frac >= 0.9
    print(f"H6: {'holds' if ok else 'FAILS'}")
    return ok


def h7(pairs):
    print("\n## H7 overhead: per-run driver tick time, paired unified - legacy, all families pooled (plan ticks included)")
    verdicts = []
    for metric in ("tickP95Ns", "tickP99Ns", "tickP50Ns", "tickMaxNs"):
        d = [(p["unified"][metric] - p["legacy"][metric]) / 1e6 for p in pairs.values()]
        lo, hi = boot(d, statistics.median)
        gated = metric in ("tickP95Ns", "tickP99Ns")
        v = "acceptable" if hi <= OVERHEAD_MS else "FAIL" if lo > OVERHEAD_MS else "inconclusive"
        if gated:
            verdicts.append(v)
        off = statistics.median(p["legacy"][metric] for p in pairs.values()) / 1e3
        on = statistics.median(p["unified"][metric] for p in pairs.values()) / 1e3
        print(f"{metric}: n={len(d)} median diff {statistics.median(d) * 1e3:+.2f} us, 95% CI {lo * 1e3:+.2f}..{hi * 1e3:+.2f} us; "
              f"median legacy {off:.2f} us, unified {on:.2f} us" + (f" -> {v}" if gated else " (diagnostic)"))
    print("\ndiagnostic, per family (medians): tick p50 us legacy/unified, plan ms per run, cpu ms per run, alloc KB per run")
    print("| family | p50 legacy | p50 unified | plan ms legacy | plan ms unified | cpu ms legacy | cpu ms unified | alloc KB legacy | alloc KB unified |")
    print("|---|---|---|---|---|---|---|---|---|")
    for fam in families(pairs):
        ps = [p for (f, _), p in pairs.items() if f == fam]
        m = lambda arm, k, s: statistics.median(p[arm][k] for p in ps) / s
        print(f"| {fam} | {m('legacy', 'tickP50Ns', 1e3):.2f} | {m('unified', 'tickP50Ns', 1e3):.2f} | {m('legacy', 'planNs', 1e6):.3f} | "
              f"{m('unified', 'planNs', 1e6):.3f} | {m('legacy', 'cpuNs', 1e6):.3f} | {m('unified', 'cpuNs', 1e6):.3f} | "
              f"{m('legacy', 'allocBytes', 1024):.1f} | {m('unified', 'allocBytes', 1024):.1f} |")
    d = [(p["unified"]["tickP50Ns"] - p["legacy"]["tickP50Ns"]) / 1e3 for (f, _), p in pairs.items() if f == "clean"]
    if d:
        lo, hi = boot(d, statistics.median)
        print(f"clean only (same work both arms), tick p50 diff median {statistics.median(d):+.3f} us, 95% CI {lo:+.3f}..{hi:+.3f} us")
    final = "FAIL" if "FAIL" in verdicts else "acceptable" if all(v == "acceptable" for v in verdicts) else "inconclusive"
    print(f"H7: {final}")
    return final


def nearest_rank(xs, q):
    s = sorted(xs)
    return s[max(0, math.ceil(q * len(s)) - 1)]


def retry_bounded(pairs):
    print("\n## R1 (Phase 06A) the retry is bounded and counted, every unified run")
    bad = []
    for (fam, t), p in pairs.items():
        u = p["unified"]
        n, found, esc = u.get("CORRECTION_RETRIES", 0), u.get("CORRECTION_RETRY_PLANS", 0), u["ESCALATIONS"]
        if not (n <= esc and found <= n and n - found <= 1) or (n - found == 1 and u["end"] != "EMPTY_PLAN"):
            bad.append((f"{fam}-{t}", n, found, esc, u["end"]))
    print("| family | escalations | retries | retry plans | runs that retried | failed retry ended EMPTY_PLAN |")
    print("|---|---|---|---|---|---|")
    for fam in families(pairs):
        us = [p["unified"] for (f, _), p in pairs.items() if f == fam]
        s = lambda k: sum(u.get(k, 0) for u in us)
        failed = [u for u in us if u.get("CORRECTION_RETRIES", 0) - u.get("CORRECTION_RETRY_PLANS", 0) == 1]
        print(f"| {fam} | {s('ESCALATIONS')} | {s('CORRECTION_RETRIES')} | {s('CORRECTION_RETRY_PLANS')} | "
              f"{sum(u.get('CORRECTION_RETRIES', 0) > 0 for u in us)} | {sum(u['end'] == 'EMPTY_PLAN' for u in failed)}/{len(failed)} |")
    print(f"violations {len(bad)} {bad[:10]}")
    ok = not bad
    print(f"R1: {'holds' if ok else 'FAILS'}")
    return ok


def retry_time(pairs):
    print("\n## R2 (Phase 06A) search and tick time, unified runs with at least one retry (nearest-rank p95)")
    us = [p["unified"] for p in pairs.values() if p["unified"].get("CORRECTION_RETRIES", 0) > 0]
    if not us:
        print("no unified run retried")
        print("R2: not exercised")
        return "not exercised"
    rm = [u["retryNsMax"] / 1e6 for u in us]
    tm = [u["tickMaxNs"] / 1e6 for u in us]
    rp, rx, tp, tx = nearest_rank(rm, 0.95), max(rm), nearest_rank(tm, 0.95), max(tm)
    ok = rp <= RETRY_P95_MS and rx <= RETRY_MAX_MS and tp <= TICK_P95_MS and tx <= TICK_MAX_MS
    print(f"runs {len(us)}; retryNsMax p95 {rp:.2f} ms (<= {RETRY_P95_MS}), max {rx:.2f} ms (<= {RETRY_MAX_MS}); "
          f"tickMaxNs p95 {tp:.2f} ms (<= {TICK_P95_MS}), max {tx:.2f} ms (<= {TICK_MAX_MS}); "
          f"retryNsMax median {statistics.median(rm):.2f} ms")
    print("\ndiagnostic, per family: unified retry time and its share of unified plan time")
    print("| family | runs that retried | retry ms total | plan ms total | share |")
    print("|---|---|---|---|---|")
    for fam in families(pairs):
        fs = [p["unified"] for (f, _), p in pairs.items() if f == fam]
        rt = sum(u.get("retryNs", 0) for u in fs) / 1e6
        pt = sum(u["planNs"] for u in fs) / 1e6
        print(f"| {fam} | {sum(u.get('CORRECTION_RETRIES', 0) > 0 for u in fs)} | {rt:.1f} | {pt:.1f} | {(rt / pt if pt else 0):.1%} |")
    v = "holds" if ok else "missed"
    print(f"R2: {v}")
    return v


def slip_mechanism(pairs):
    print("\n## Slip mechanism (Phase 06A): retry plans over the unified slip runs")
    us = [p["unified"] for (f, _), p in pairs.items() if f == "slip"]
    found = sum(u.get("CORRECTION_RETRY_PLANS", 0) for u in us)
    rescued = [u for u in us if u.get("CORRECTION_RETRY_PLANS", 0) > 0]
    ends = {}
    for u in rescued:
        ends[u["end"]] = ends.get(u["end"], 0) + 1
    print(f"retry plans {found}; runs with a retry plan {len(rescued)}, their ends {ends} (arrived {ends.get('ARRIVED', 0)})")
    ok = found > 0
    print(f"slip mechanism: {'holds' if ok else 'FAILS'}")
    return ok


def main(path):
    pairs = load(path)
    print(f"# Phase 06/06A headless batch: {len(pairs)} paired trials, families {families(pairs)}")
    orders = {}
    for p in pairs.values():
        orders[p["unified"]["order"]] = orders.get(p["unified"]["order"], 0) + 1
    print(f"unified ran first in {orders.get(0, 0)} trials, second in {orders.get(1, 0)}")
    r1, r2, r3, r4 = h1(pairs), h2(pairs), h3(pairs), h4(pairs)
    r5, fewer, faster = h5(pairs)
    r6 = h6(pairs)
    r7 = h7(pairs)
    q1, q2, sm = retry_bounded(pairs), retry_time(pairs), slip_mechanism(pairs)
    print("\n# Tier S summary")
    print(f"H1 {r1}; H2 {r2}; H3 {r3}; H4 {r4}; H5 no regression {r5}; H6 {r6}; H7 {r7}; R1 {q1}; R2 {q2}; slip mechanism {sm}")
    fail = not (r1 and r2 and r3 and r4 and r5 and r6 and q1) or r7 == "FAIL"
    print(f"Tier S: {'FAIL' if fail else 'no failure'}; fewer replans in {[f for f, v in fewer.items() if v]}; "
          f"faster in {[f for f, v in faster.items() if v]} (Tier S only)")
    s2 = r2 and q1 and q2 == "holds" and sm
    print(f"S2 (slip blocker): {'resolved' if s2 else 'not resolved'} (H2 every family {r2}, R1 {q1}, R2 {q2}, slip mechanism {sm})")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: python scripts/unified-batch.py <headless-batch.csv>")
    main(sys.argv[1])
