package baritone.bombard;

import baritone.api.physics.PredictionResult;

/** Hard feasibility gates, with no adaptive weights or invented success probabilities. */
public final class BlastSafety {
    private BlastSafety() {}
    public static boolean usable(PredictionResult r) {
        return trajectoryUsable(r) && r.explosion != null;
    }
    public static boolean trajectoryUsable(PredictionResult r) {
        return unusable(r) == null;
    }
    /** Why {@link #trajectoryUsable} refuses r, for diagnostics; null when it is usable. */
    public static String unusable(PredictionResult r) {
        if (r == null) return "NO_RESULT";
        if (r.status != PredictionResult.Status.OK)
            return r.failure == null || r.failure == PredictionResult.Failure.NONE ? r.status.name() : r.status + "_" + r.failure;
        if (r.snapshotAgeTicks > 20) return "STALE_SNAPSHOT";
        for (PredictionResult.Assumption a : r.assumptionSet()) {
            // These are unavoidable, explicit observation assumptions; other missing mechanics reject the action.
            if (a.changesOutcome && a != PredictionResult.Assumption.CLIENT_STATE
                    && a != PredictionResult.Assumption.TARGETS_FROZEN) return a.name();
        }
        return null;
    }
    /** A missed deadline or full queue says nothing about the blast; asking again next tick may succeed. */
    public static boolean transientFailure(PredictionResult r) {
        return r != null && (r.status == PredictionResult.Status.EXPIRED || r.status == PredictionResult.Status.REJECTED
                || r.status == PredictionResult.Status.PARTIAL && r.failure == PredictionResult.Failure.DEADLINE);
    }
    public static boolean worthwhile(BlastForecast before, BlastForecast after, float health, float allowed) {
        if (before == null || after == null || !before.ok() || !after.ok() || !Float.isFinite(allowed)) return false;
        float self = after.damageTo(0), foe = after.damageTo(1);
        return Float.isFinite(self) && Float.isFinite(foe) && self <= allowed && self < health
                && self <= before.damageTo(0) && foe > self && foe > before.damageTo(1);
    }
}
