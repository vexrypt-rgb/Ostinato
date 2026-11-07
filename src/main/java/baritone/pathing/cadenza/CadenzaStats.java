/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cadenza counters (process-wide, read by the live harness), and the per-interaction lifecycle the processes report.
 * <p>
 * The lifecycle keeps navigation and interaction apart: reaching an execution pose is not the interaction, and an
 * attempted interaction is not a confirmed one. Only the process that owns the interaction reports confirmation.
 */
public final class CadenzaStats {

    private CadenzaStats() {}

    /** Where one interaction is. */
    public enum Phase {
        /** A Cadenza goal was handed to the planner. */
        PATH_REQUESTED,
        /** Standing in a candidate cell (or the target became reachable on the way). */
        POSE_REACHED,
        /** Stood in a candidate cell but the live reach check found nothing to act on; the cell is dropped for a while. */
        POSE_UNUSABLE,
        /** The process started the click (look + left / right button). */
        ATTEMPTED,
        /** The world shows it done: the block is gone, the container opened, the block is placed. */
        CONFIRMED,
        /** Tried and gave up (timeout, the click did nothing). */
        FAILED,
        /** The target changed under the plan (gone, replaced) before it was done. */
        INVALIDATED,
        /** No candidate cell (see {@link Cadenza.Status}); the caller fell back to its old goal. */
        NO_CANDIDATE
    }

    private static final Map<Phase, AtomicLong> PHASES = new EnumMap<>(Phase.class);
    private static final Map<Cadenza.Status, AtomicLong> STATUSES = new EnumMap<>(Cadenza.Status.class);
    private static final AtomicLong GOALS = new AtomicLong(), CELLS = new AtomicLong(), RAYS = new AtomicLong(), NANOS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong(), TERMINAL_CHARGES = new AtomicLong();

    static {
        for (Phase p : Phase.values()) PHASES.put(p, new AtomicLong());
        for (Cadenza.Status s : Cadenza.Status.values()) STATUSES.put(s, new AtomicLong());
    }

    public static void phase(Phase p) {
        PHASES.get(p).incrementAndGet();
    }

    public static long phases(Phase p) {
        return PHASES.get(p).get();
    }

    static void result(Cadenza.Result r, long nanos) {
        STATUSES.get(r.status).incrementAndGet();
        CELLS.addAndGet(r.candidates.size());
        RAYS.addAndGet(r.rays);
        NANOS.addAndGet(nanos);
    }

    static void cacheHit() {
        CACHE_HITS.incrementAndGet();
    }

    public static void goal() {
        GOALS.incrementAndGet();
    }

    /** The search paid a terminal cost at a goal cell (see AStarPathFinder). */
    public static void terminalCharge() {
        TERMINAL_CHARGES.incrementAndGet();
    }

    public static long statuses(Cadenza.Status s) {
        return STATUSES.get(s).get();
    }

    public static long goals() {
        return GOALS.get();
    }

    public static long rays() {
        return RAYS.get();
    }

    public static long nanos() {
        return NANOS.get();
    }

    public static long terminalCharges() {
        return TERMINAL_CHARGES.get();
    }

    public static String line() {
        StringBuilder s = new StringBuilder("CADENZA goals=").append(GOALS.get());
        for (Cadenza.Status st : Cadenza.Status.values()) s.append(' ').append(st.name().toLowerCase()).append('=').append(STATUSES.get(st).get());
        s.append(" cells=").append(CELLS.get()).append(" rays=").append(RAYS.get()).append(" ms=").append(NANOS.get() / 1_000_000)
                .append(" cacheHits=").append(CACHE_HITS.get()).append(" charges=").append(TERMINAL_CHARGES.get());
        for (Phase p : Phase.values()) s.append(' ').append(p.name().toLowerCase()).append('=').append(PHASES.get(p).get());
        return s.toString();
    }

    public static void reset() {
        for (AtomicLong a : PHASES.values()) a.set(0);
        for (AtomicLong a : STATUSES.values()) a.set(0);
        GOALS.set(0);
        CELLS.set(0);
        RAYS.set(0);
        NANOS.set(0);
        CACHE_HITS.set(0);
        TERMINAL_CHARGES.set(0);
    }
}
