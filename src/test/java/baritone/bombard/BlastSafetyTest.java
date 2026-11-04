package baritone.bombard;

import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionResult;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class BlastSafetyTest {
    private static PredictionResult result(float self, float foe) {
        return new PredictionResult.Builder(Fidelity.STANDARD).horizon(10).explosion(
                new PredictionResult.Explosion(10, 0, 64, 0, 4, List.of(
                        new PredictionResult.Exposure(1, self, false, 0, 0, 0),
                        new PredictionResult.Exposure(1, foe, false, 0, 0, 0)), null, null, new long[0])).build();
    }
    private static BlastForecast forecast(float self, float foe) { return BlastForecast.of(result(self, foe), 0, 2); }
    @Test public void improvementMustNotIncreaseSelfRisk() {
        assertTrue(BlastSafety.worthwhile(forecast(3, 2), forecast(2, 5), 20, 3));
        assertFalse(BlastSafety.worthwhile(forecast(1, 2), forecast(2, 5), 20, 3));
        assertFalse(BlastSafety.worthwhile(forecast(3, 5), forecast(2, 5), 20, 3));
    }
    @Test public void lethalUnauthorizedAndUnknownAreRejected() {
        assertFalse(BlastSafety.worthwhile(forecast(3, 2), forecast(2, 5), 2, 3));
        assertFalse(BlastSafety.worthwhile(forecast(3, 2), forecast(2, 5), 20, 1));
        assertFalse(BlastSafety.worthwhile(forecast(3, 2), forecast(Float.NaN, 5), 20, 3));
        assertFalse(BlastSafety.worthwhile(forecast(3, 2), forecast(2, Float.NaN), 20, 3));
        assertFalse(BlastSafety.worthwhile(forecast(3, 2), forecast(2, 5), 20, Float.POSITIVE_INFINITY));
    }
    @Test public void missingMechanicsCannotAuthorizeActions() {
        assertTrue(BlastSafety.usable(result(2, 5)));
        assertFalse(BlastSafety.usable(null));
        assertFalse(BlastSafety.usable(new PredictionResult.Builder(Fidelity.STANDARD)
                .explosion(result(2, 5).explosion).assume(PredictionResult.Assumption.FLUID_CURRENT).build()));
        assertFalse(BlastSafety.usable(new PredictionResult.Builder(Fidelity.STANDARD)
                .explosion(result(2, 5).explosion).partial(PredictionResult.Failure.DEADLINE).build()));
    }
    @Test public void onlyTimingFailuresAreRetried() {
        var deadline = new PredictionResult.Builder(Fidelity.STANDARD).partial(PredictionResult.Failure.DEADLINE).build();
        var outside = new PredictionResult.Builder(Fidelity.STANDARD).partial(PredictionResult.Failure.OUT_OF_SNAPSHOT).build();
        assertTrue(BlastSafety.transientFailure(deadline));
        assertTrue(BlastSafety.transientFailure(PredictionResult.failed(PredictionResult.Status.EXPIRED,
                PredictionResult.Failure.DEADLINE, Fidelity.STANDARD, 0)));
        assertFalse(BlastSafety.transientFailure(outside));
        assertFalse(BlastSafety.transientFailure(result(2, 5)));
        assertFalse(BlastSafety.transientFailure(null));
        assertEquals("PARTIAL_DEADLINE", BlastSafety.unusable(deadline));
        assertEquals("PARTIAL_OUT_OF_SNAPSHOT", BlastSafety.unusable(outside));
        assertNull(BlastSafety.unusable(result(2, 5)));
    }
    @Test public void invalidTargetIndicesAreUnknown() {
        assertTrue(Float.isNaN(forecast(2, 5).damageTo(-1)));
        assertTrue(Float.isNaN(forecast(2, 5).damageTo(2)));
    }
}
