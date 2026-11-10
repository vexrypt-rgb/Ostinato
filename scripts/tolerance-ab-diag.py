# Ostinato 2.0 Phase 04 validation, diagnostics only (NOT pre-registered, never gated): where the flag's tick time goes.
# Reads the LegatoLiveTest run logs (physUs, driftReplans, turned per lap) and tolerance-ticks.csv (every lap tick's time
# and driver). Usage: python scripts/tolerance-ab-diag.py <ticks.csv[.gz]> <run log> [<ticks.csv[.gz]> <run log> ...]
import csv
import gzip
import math
import re
import statistics
import sys
from collections import defaultdict

ROW = re.compile(r"LEGATOTEST lap (\d+) (.*)")
KV = re.compile(r"(\w+)=(\{[^}]*\}|\S+)")


def q(v, p):
    return v[max(0, min(len(v) - 1, math.ceil(p * len(v)) - 1))] if v else float("nan")


def laps(path):
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = ROW.search(line)
        if m:
            kv = dict(KV.findall(m.group(2)))
            if "tickP95Us" in kv:
                out[(kv["leg"], int(kv["rep"]), kv["mode"])] = kv
    return out


def main(args):
    phys_d, turn_d, drift_on, drift_off = [], [], 0, 0
    ticks = defaultdict(list)   # (flag, driver class) -> per-tick ms
    for ticks_path, log_path in zip(args[0::2], args[1::2]):
        by = laps(log_path)
        for (leg, rep, mode), kv in by.items():
            if mode in "BD":
                base = by.get((leg, rep, "A" if mode == "B" else "C"))
                if base:
                    phys_d.append((int(kv["physUs"]) - int(base["physUs"])) / 1000.0)
                    turn_d.append(int(kv["turned"]) - int(base["turned"]))
                drift_on += int(kv["driftReplans"])
            else:
                drift_off += int(kv["driftReplans"])
        opener = gzip.open if ticks_path.endswith(".gz") else open
        for r in csv.DictReader(opener(ticks_path, "rt", newline="", encoding="utf-8")):
            if r["i"] == "0":
                continue    # the lap's start tick (teleport, plan) is not driving
            ticks[(r["tolerance"], r["driver"])].append(int(r["ns"]) / 1e6)
    print(f"pairs {len(phys_d)}; drift replans on {drift_on}, off {drift_off}")
    print(f"PhysicsTravel time per lap, paired on - off: median {statistics.median(phys_d):+.2f} ms, "
          f"mean {statistics.mean(phys_d):+.2f} ms")
    print(f"degrees turned per lap, paired on - off: median {statistics.median(turn_d):+.0f}, mean {statistics.mean(turn_d):+.0f}")
    print("\nper-tick client tick time by driver, all lap ticks but the first, ms")
    print("| driver | flag | n | p50 | p95 | p99 | max |")
    print("|---|---|---|---|---|---|---|")
    for key in sorted(ticks, key=lambda k: (k[1], k[0])):
        v = sorted(ticks[key])
        print(f"| {key[1]} | {'on' if key[0] == 'true' else 'off'} | {len(v)} | {q(v, .5):.2f} | {q(v, .95):.2f} | {q(v, .99):.2f} | {v[-1]:.2f} |")


if __name__ == "__main__":
    if len(sys.argv) < 3 or len(sys.argv) % 2 != 1:
        sys.exit("usage: python scripts/tolerance-ab-diag.py <ticks.csv[.gz]> <run log> [...]")
    main(sys.argv[1:])
