/*
 * Ostinato: new file (Grace Note). Not part of upstream Baritone.
 */
package baritone.api.gracenote;

/**
 * Ostinato: Grace Note, predictive emergency survival. Ostinato owns how an emergency is met: detection over the
 * predictive physics engine, feasibility, taking and giving back movement, the maneuver, and checking what happened.
 * The caller (TenorClef) owns whether it may be met at all, through {@link #setPolicy} and {@link #setMode}.
 * <p>
 * Client thread only. Everything returned is immutable and tick-stamped: a status is what was true on that tick,
 * never a promise about this one.
 */
public interface IGraceNote {

    /** OFF: nothing runs. OBSERVE: detect, evaluate and verify only; never takes control. LIVE: may take control. */
    enum Mode { OFF, OBSERVE, LIVE }

    enum Phase {
        MONITORING, THREAT_DETECTED, EVALUATING, ACQUIRING_CONTROL, EXECUTING, VERIFYING, RELEASING_CONTROL,
        RECOVERING, COMPLETED, FAILED
    }

    /**
     * FALL: an unplanned damaging landing. KNOCKBACK_FALL: the same, begun by a hit. PLANNED_FALL: a path fall the
     * executor is running (observed; its own movement answers it). HAZARD_LANDING: a landing on or in something that
     * hurts apart from the fall (reported, no verified response). ENTITY: a creeper or projectile (reported only).
     */
    enum Emergency { NONE, FALL, KNOCKBACK_FALL, PLANNED_FALL, HAZARD_LANDING, ENTITY }

    enum Response { NONE, WATER_BUCKET }

    /**
     * How an episode ended. AVOIDED and SURVIVED need the response to have run; OBSERVED is an episode met without
     * control (OBSERVE mode, or no feasible response), with the outcome as measured.
     */
    enum Result {
        AVOIDED, SURVIVED, DIED, ABORTED, OBSERVED, FAILED_CONTROL, FAILED_WINDOW, FAILED_EXECUTION, REJECTED,
        INTERRUPTED
    }

    Mode mode();

    void setMode(Mode mode);

    GraceNotePolicy policy();

    /** Takes effect from the next episode; an episode under way keeps the policy it started with. */
    void setPolicy(GraceNotePolicy policy);

    GraceNoteStatus status();

    /** The last finished episode, null before the first. {@link GraceNoteOutcome#sequence()} rises by one each. */
    GraceNoteOutcome lastOutcome();

    /** Whether Grace Note owns movement this tick (from acquiring control until release is verified). */
    boolean holdsControl();

    Telemetry telemetry();

    /** Counters since load. Nanosecond figures are client-thread time spent by Grace Note, PPE calls included. */
    record Telemetry(long ticks, long screened, long predictions, long emergencies, long acquisitions, long clicks,
                     long avoided, long survived, long died, long failed, long aborted, long observed, long deferred,
                     long thrashBlocked, long totalNanos, long maxNanos, long predictNanos, long predictMaxNanos) {}
}
