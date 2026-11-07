/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import org.junit.Test;

import static org.junit.Assert.*;

public class PredictionAuditTest {

    private static Prediction at(double x, double y, double z, double spread, double bound) {
        return new Prediction(x, y, z, 0, 0, 0, spread, bound, 0.1, 0.1, 1, 0, Prediction.Confidence.HIGH, 0);
    }

    @Test
    public void settlesOnlyOnTheDueTick() {
        PredictionAudit a = new PredictionAudit();
        a.expect(10, 0, at(1, 64, 0, 0.5, 1), 0, 64, 0); // h=1: due on 11
        a.actual(10, 1, 64, 0);
        assertEquals(0, a.settled(0));
        a.actual(11, 1.2, 64, 0);
        assertEquals(1, a.settled(0));
        assertEquals(0.2, a.model[0].mean(), 1e-9);
        assertEquals(1.2, a.baseline[0].mean(), 1e-9);
        assertEquals(1, a.inSpread[0]);
        assertEquals(1, a.inBound[0]);
        assertEquals(1, a.inVertical[0]);
        // settled once only
        a.actual(11, 1.2, 64, 0);
        assertEquals(1, a.settled(0));
    }

    @Test
    public void countsMissesOfSpreadBoundAndVertical() {
        PredictionAudit a = new PredictionAudit();
        a.expect(0, 1, at(0, 64, 0, 0.1, 0.3), 0, 64, 0); // h=3
        a.actual(3, 0.5, 64.5, 0);
        assertEquals(1, a.settled(1));
        assertEquals(0, a.inSpread[1]);
        assertEquals(0, a.inBound[1]);
        assertEquals(0, a.inVertical[1]);
        assertEquals(0.5, a.vertical[1].mean(), 1e-9);
    }

    @Test
    public void discardedPredictionsAreNeverJudged() {
        PredictionAudit a = new PredictionAudit();
        a.expect(0, 0, at(0, 64, 0, 1, 1), 0, 64, 0);
        a.discardPending();
        a.actual(1, 9, 64, 9);
        assertEquals(0, a.settled(0));
        assertEquals("", a.report("x"));
    }

    @Test
    public void quantilesComeFromTheHistogram() {
        PredictionAudit.Stat s = new PredictionAudit.Stat();
        for (int i = 0; i < 100; i++) s.add(i * 0.01); // 0.00 .. 0.99
        assertEquals(0.50, s.quantile(0.5), 0.051);
        assertEquals(0.95, s.quantile(0.95), 0.051);
        assertEquals(0.99, s.max, 1e-9);
        s.add(100); // past the last bin
        assertEquals(PredictionAudit.BINS * PredictionAudit.BIN, s.quantile(1.0), 1e-9);
    }

    @Test
    public void reportNamesEachSettledHorizon() {
        PredictionAudit a = new PredictionAudit();
        a.expect(0, 0, at(0, 64, 0, 1, 1), 0, 64, 0);
        a.expect(0, 2, at(0, 64, 0, 1, 1), 0, 64, 0);
        a.actual(1, 0, 64, 0);
        a.actual(5, 0, 64, 0);
        String r = a.report("fight");
        assertTrue(r, r.contains("scherzo fight h=1 n=1"));
        assertTrue(r, r.contains("scherzo fight h=5 n=1"));
        assertFalse(r, r.contains("h=3"));
        a.clear();
        assertEquals("", a.report("fight"));
    }

    @Test
    public void ringDoesNotConfuseHorizons() {
        // many ticks in a row: each prediction is judged against the tick it was for, not one RING ticks earlier
        PredictionAudit a = new PredictionAudit();
        for (long t = 0; t < 100; t++) {
            a.actual(t, t, 64, 0);
            for (int i = 0; i < PredictionAudit.HORIZONS.length; i++) {
                int h = PredictionAudit.HORIZONS[i];
                a.expect(t, i, at(t + h, 64, 0, 0.01, 0.01), t, 64, 0);
            }
        }
        for (int i = 0; i < PredictionAudit.HORIZONS.length; i++) {
            assertEquals(100 - PredictionAudit.HORIZONS[i], a.settled(i));
            assertEquals(0, a.model[i].mean(), 1e-9);
            assertEquals(PredictionAudit.HORIZONS[i], a.baseline[i].mean(), 1e-9);
        }
    }
}
