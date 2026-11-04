package baritone.gracenote;

import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import org.junit.Test;
import static org.junit.Assert.*;

public class ForecastTrustTest {
    private PredictionResult.Builder result() {
        return new PredictionResult.Builder(PredictionRequest.Fidelity.SCREENING);
    }

    @Test public void acceptsFreshCompleteModelledResults() {
        assertNull(ForecastTrust.rejection(result().build()));
        assertNull(ForecastTrust.rejection(result().assume(PredictionResult.Assumption.INPUT_HELD).build()));
        assertNull(ForecastTrust.rejection(result().build().withTiming(0, ForecastTrust.MAX_SECTION_AGE, 0)));
    }

    @Test public void rejectsMissingPartialAndOldResults() {
        assertNotNull(ForecastTrust.rejection(null));
        assertNotNull(ForecastTrust.rejection(result().partial(PredictionResult.Failure.DEADLINE).build()));
        assertNotNull(ForecastTrust.rejection(result().build().withTiming(0, ForecastTrust.MAX_SECTION_AGE + 1, 0)));
    }

    @Test public void rejectsEveryOutcomeChangingAssumption() {
        for (PredictionResult.Assumption assumption : PredictionResult.Assumption.values()) {
            if (assumption.changesOutcome) {
                assertNotNull(assumption.name(), ForecastTrust.rejection(result().assume(assumption).build()));
            }
        }
    }
}
