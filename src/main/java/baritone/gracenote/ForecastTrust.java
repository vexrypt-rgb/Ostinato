package baritone.gracenote;

import baritone.api.physics.PredictionResult;

/** Consumer acceptance rules; an engine's partial answer is not a complete emergency forecast. */
final class ForecastTrust {
    // TerrainCapture reuses unchanged sections for up to 20 ticks, invalidating on block/chunk updates.
    // This is section age, not subject age: GraceNote captures the player and predicts again each tick.
    static final int MAX_SECTION_AGE = 20;
    private ForecastTrust() {}

    static String rejection(PredictionResult result) {
        if (result == null) return "NO_RESULT";
        if (result.status != PredictionResult.Status.OK) return result.status + "_" + result.failure;
        if (result.snapshotAgeTicks < 0 || result.snapshotAgeTicks > MAX_SECTION_AGE) return "STALE_SNAPSHOT";
        if (result.confidence != PredictionResult.Confidence.EXACT
                && result.confidence != PredictionResult.Confidence.BOUNDED) return "UNCERTAIN_" + result.confidence;
        return null;
    }
}
