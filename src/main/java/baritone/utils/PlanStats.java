package baritone.utils;

import java.util.concurrent.atomic.AtomicLong;

/** Ostinato: planning-time counters (A*, physics search, kinematic rollouts) the test harness reads per goal. */
public final class PlanStats {
    private PlanStats() {}

    public static final AtomicLong astarNs = new AtomicLong(), physNs = new AtomicLong(), kinNs = new AtomicLong(),
            hhNs = new AtomicLong(), hhCalls = new AtomicLong();
    /** Ostinato: the jump sims split out of hhNs: head-hitter sims, local partial-block sims, full-grid partial sims. */
    public static final AtomicLong ceilNs = new AtomicLong(), ceilSims = new AtomicLong(), partNs = new AtomicLong(), partSims = new AtomicLong(),
            fullNs = new AtomicLong(), fullSims = new AtomicLong();
}