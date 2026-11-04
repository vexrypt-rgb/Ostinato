package baritone.scherzo;

import org.junit.Test;
import static org.junit.Assert.*;

public class ManeuverChoiceTest {
    private static Prediction prediction(double x, long age) {
        return new Prediction(x, 64, 0, 0, 0, 0, 0.1, 1, 0, 0, 3, age, Prediction.Confidence.HIGH, 0);
    }
    @Test public void closesAndRetreatsToPredictedBand() {
        var hold = new ManeuverChoice.Candidate(0, 0, 0, 0, true);
        var forward = new ManeuverChoice.Candidate(1, 0, 0.3, 0, true);
        var back = new ManeuverChoice.Candidate(-1, 0, -0.3, 0, true);
        assertSame(forward, ManeuverChoice.choose(prediction(4, 0), 1.5, 3, hold, forward, back));
        assertSame(back, ManeuverChoice.choose(prediction(1, 0), 1.5, 3, hold, forward, back));
        assertSame(hold, ManeuverChoice.choose(prediction(2, 0), 1.5, 3, hold, forward, back));
    }
    @Test public void hazardsAndStaleObservationsCannotAuthorizeMovement() {
        var unsafe = new ManeuverChoice.Candidate(1, 0, 0.3, 0, false);
        assertNull(ManeuverChoice.choose(prediction(4, 0), 1.5, 3, unsafe));
        assertNull(ManeuverChoice.choose(prediction(4, 1), 1.5, 3,
                new ManeuverChoice.Candidate(1, 0, 0.3, 0, true)));
    }
}
