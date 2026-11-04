/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import org.junit.Test;

import java.util.Random;

import static baritone.scherzo.OpponentModel.Rules;
import static org.junit.Assert.*;

public class OpponentModelTest {

    private static final double EPS = 1e-9;
    private static final OpponentModel.Ground FLAT_64 = (x, y, z) -> y >= 64 ? 64 : Double.NaN;

    /** Vanilla's jump arc from standing at y0: the height after each tick, index 0 the first airborne tick. */
    private static double[] arc(double y0, int ticks) {
        double[] out = new double[ticks];
        double y = y0, v = Rules.JUMP;
        for (int i = 0; i < ticks; i++) {
            y += v;
            v = (v - Rules.GRAVITY) * Rules.DRAG;
            out[i] = Math.max(y0, y);
        }
        return out;
    }

    @Test
    public void standingStillPredictsStandingStill() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 10; t++) m.observe(t, 3.5, 64, -2.5, true);
        Prediction p = m.predict(9, 5, FLAT_64);
        assertEquals(3.5, p.x(), EPS);
        assertEquals(64, p.y(), EPS);
        assertEquals(-2.5, p.z(), EPS);
        assertEquals(Prediction.Confidence.HIGH, p.confidence());
        assertEquals(Rules.BASE, p.spread(), EPS);
        assertTrue("a standing player can still walk off", p.bound() > p.spread());
        assertTrue("a standing player can jump", p.up() > 1.0);
    }

    @Test
    public void constantVelocityIsExtrapolatedExactly() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 10; t++) m.observe(t, 0.28 * t, 64, 0.1 * t, true);
        for (int h : new int[]{1, 3, 5, 10}) {
            Prediction p = m.predict(9, h, FLAT_64);
            assertEquals(0.28 * (9 + h), p.x(), 1e-6);
            assertEquals(0.1 * (9 + h), p.z(), 1e-6);
            assertFalse(p.has(Prediction.TURNING));
            assertEquals(Prediction.Confidence.HIGH, p.confidence());
        }
        assertEquals(0, m.residual(), 1e-9);
    }

    @Test
    public void fractionalHorizonInterpolates() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 6; t++) m.observe(t, 0.2 * t, 64, 0, true);
        assertEquals(0.2 * 5 + 0.2 * 2.5, m.predict(5, 2.5, FLAT_64).x(), 1e-6);
    }

    @Test
    public void reversalIsFollowedInOneTick() {
        OpponentModel m = new OpponentModel();
        double x = 0;
        for (int t = 0; t < 8; t++) m.observe(t, x += 0.28, 64, 0, true);
        m.observe(8, x -= 0.28, 64, 0, true);
        assertTrue(m.turned());
        assertEquals(-0.28, m.vx(), 1e-6);
        Prediction p = m.predict(8, 3, FLAT_64);
        assertTrue(p.has(Prediction.TURNING));
        assertEquals(Prediction.Confidence.MEDIUM, p.confidence());
        assertEquals(x - 0.84, p.x(), 1e-6);
        // the plain line through the last five samples would still be heading the old way
        m.observe(9, x -= 0.28, 64, 0, true);
        assertTrue("still reads as the new heading", m.vx() < 0);
    }

    @Test
    public void startFromStandIsATurn() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 6; t++) m.observe(t, 0, 64, 0, true);
        m.observe(6, 0.28, 64, 0, true);
        assertTrue(m.turned());
        assertEquals(0.28, m.vx(), 1e-9);
    }

    @Test
    public void jumpIsDetectedAndFollowsVanillaArc() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 5; t++) m.observe(t, 0, 64, 0, true);
        double[] arc = arc(64, 20);
        m.observe(5, 0, arc[0], 0, false);
        assertTrue(m.jumped());
        assertTrue(m.airborne());
        assertEquals((Rules.JUMP - Rules.GRAVITY) * Rules.DRAG, m.vy(), 1e-9);
        for (int h : new int[]{1, 3, 5}) {
            Prediction p = m.predict(5, h, FLAT_64);
            assertEquals("h=" + h, arc[h], p.y(), 1e-9);
            assertTrue(p.has(Prediction.JUMPING));
            assertTrue(p.has(Prediction.AIRBORNE));
        }
        // lands back on the floor and stays there
        assertEquals(64, m.predict(5, 18, FLAT_64).y(), EPS);
        // mid-arc, from the falling half
        for (int t = 1; t < 9; t++) m.observe(5 + t, 0, arc[t], 0, false);
        assertEquals(arc[11], m.predict(13, 3, FLAT_64).y(), 1e-9);
    }

    /**
     * The synced ground flag runs ahead of the position the client plays back: it drops a tick before the shown
     * rise and comes back while the body is still shown falling. The jump is still seen, and the landing still falls.
     */
    @Test
    public void groundFlagAheadOfShownPosition() {
        OpponentModel m = new OpponentModel();
        double[] arc = arc(64, 14);
        for (int t = 0; t < 5; t++) m.observe(t, 0, 64, 0, true);
        m.observe(5, 0, 64, 0, false); // flag already off, still shown standing
        m.observe(6, 0, arc[0], 0, false);
        assertTrue("jump seen though the flag dropped first", m.jumped());
        int t = 7;
        for (int i = 1; i < 9; i++) m.observe(t++, 0, arc[i], 0, i >= 7); // flag back on two ticks before the floor
        assertTrue("flagged down but shown falling is airborne", m.airborne());
        assertEquals(64, m.predict(t - 1, 3, FLAT_64).y(), EPS);
        assertTrue(m.predict(t - 1, 1, FLAT_64).y() < arc[8]);
    }

    @Test
    public void stepUpOnTheGroundIsNotAJump() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 5; t++) m.observe(t, 0.2 * t, 64, 0, true);
        m.observe(5, 1.0, 64.5, 0, true); // a slab, walked up with the flag on
        assertFalse(m.jumped());
        assertFalse(m.airborne());
    }

    @Test
    public void floorIsAssumedFromTheLastGroundWhenUnknown() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 5; t++) m.observe(t, 0, 70, 0, true);
        m.observe(5, 0, 70 + Rules.JUMP, 0, false);
        Prediction p = m.predict(5, 30, null);
        assertTrue(p.has(Prediction.FLOOR_ASSUMED));
        assertEquals(70, p.y(), EPS);
        assertEquals(Prediction.Confidence.MEDIUM, p.confidence());
    }

    @Test
    public void risingPastALedgeIsNotClampedOntoIt() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 5; t++) m.observe(t, 0, 64, 0, true);
        m.observe(5, 0, 64 + Rules.JUMP, 0, false);
        // a block top at 65 under the predicted column: the rising body passes it, the falling one lands on it
        OpponentModel.Ground step = (x, y, z) -> 65;
        assertTrue(m.predict(5, 1, step).y() > 64 + Rules.JUMP);
        assertEquals(65, m.predict(5, 12, step).y(), EPS);
    }

    @Test
    public void walkingOffALedgeFalls() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 6; t++) m.observe(t, 0.2 * t, 64, 0, true);
        // the floor two blocks down from x >= 1.5
        OpponentModel.Ground cliff = (x, y, z) -> x >= 1.5 ? 62 : 64;
        Prediction p = m.predict(5, 10, cliff);
        assertTrue(p.has(Prediction.LEDGE));
        assertEquals(62, p.y(), EPS);
    }

    @Test
    public void staleWidensThenGivesUp() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t <= 10; t++) m.observe(t, 0.1 * t, 64, 0, true);
        Prediction fresh = m.predict(10, 2, FLAT_64);
        Prediction stale = m.predict(15, 2, FLAT_64);
        assertEquals(0, fresh.age());
        assertEquals(5, stale.age());
        assertEquals(7, stale.horizon(), EPS);
        assertTrue(stale.has(Prediction.STALE));
        assertEquals(Prediction.Confidence.LOW, stale.confidence());
        assertTrue(stale.bound() > fresh.bound());
        assertEquals(0.1 * 17, stale.x(), 1e-6);
        assertNull(m.predict(10 + Rules.LOST + 1, 0, FLAT_64));
    }

    @Test
    public void teleportRestartsHistory() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 8; t++) m.observe(t, 0.28 * t, 64, 0, true);
        m.observe(8, 50, 64, 50, true);
        assertEquals(1, m.resets());
        assertEquals(1, m.samples());
        Prediction p = m.predict(8, 3, FLAT_64);
        assertEquals(50, p.x(), EPS);
        assertTrue(p.has(Prediction.FEW_SAMPLES));
        assertEquals(Prediction.Confidence.LOW, p.confidence());
    }

    @Test
    public void longGapRestartsHistory() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 8; t++) m.observe(t, 0.28 * t, 64, 0, true);
        m.observe(8 + Rules.GAP + 1, 3, 64, 0, true);
        assertEquals(1, m.resets());
        assertEquals(1, m.samples());
    }

    @Test
    public void missingTicksAreTimedByTheirTicks() {
        OpponentModel m = new OpponentModel();
        m.observe(0, 0, 64, 0, true);
        m.observe(2, 0.5, 64, 0, true);
        m.observe(4, 1.0, 64, 0, true);
        m.observe(6, 1.5, 64, 0, true);
        assertEquals(0.25, m.vx(), 1e-9);
        assertEquals(2.0, m.predict(6, 2, FLAT_64).x(), 1e-6);
    }

    @Test
    public void repeatedOrOlderTicksAreIgnored() {
        OpponentModel m = new OpponentModel();
        m.observe(5, 0, 64, 0, true);
        m.observe(5, 9, 64, 0, true);
        m.observe(4, 9, 64, 0, true);
        assertEquals(1, m.samples());
        assertEquals(0, m.predict(5, 0, FLAT_64).x(), EPS);
    }

    @Test
    public void resetForgetsThePath() {
        OpponentModel m = new OpponentModel();
        for (int t = 0; t < 8; t++) m.observe(t, 0.28 * t, 64, 0, true);
        m.reset();
        assertEquals(0, m.samples());
        assertNull(m.predict(8, 1, FLAT_64));
        assertEquals(8, m.behavior().samples()); // reset keeps what was learnt about the opponent, forget() does not
        m.forget();
        assertEquals(0, m.behavior().samples());
    }

    /**
     * The bound's promise, checked over a long random walk: a player whose velocity never changes by more than
     * {@link Rules#ACCEL} a tick, and never passes {@link Rules#VMAX}, is never outside it, at any horizon.
     */
    @Test
    public void boundHoldsForAnyPlayerInsideTheAccelerationRule() {
        Random r = new Random(0x5c4e220L);
        int n = 4000;
        double[] x = new double[n], z = new double[n];
        double ux = 0, uz = 0;
        for (int t = 1; t < n; t++) {
            // some ticks keep their heading, some swerve as hard as the rule allows
            double ang = r.nextDouble() * Math.PI * 2, mag = r.nextInt(3) == 0 ? Rules.ACCEL : r.nextDouble() * Rules.ACCEL;
            ux += Math.cos(ang) * mag;
            uz += Math.sin(ang) * mag;
            double s = Math.hypot(ux, uz), cap = 0.45;
            if (s > cap) {
                ux *= cap / s;
                uz *= cap / s;
            }
            x[t] = x[t - 1] + ux;
            z[t] = z[t - 1] + uz;
        }
        OpponentModel m = new OpponentModel();
        int checked = 0, inSpread = 0;
        for (int t = 0; t < n; t++) {
            m.observe(t, x[t], 64, z[t], true);
            for (int h : PredictionAudit.HORIZONS) {
                if (t + h >= n) continue;
                Prediction p = m.predict(t, h, FLAT_64);
                double err = Math.hypot(x[t + h] - p.x(), z[t + h] - p.z());
                assertTrue("t=" + t + " h=" + h + " err=" + err + " bound=" + p.bound(), err <= p.bound() + 1e-9);
                assertTrue(p.spread() <= p.bound() + 1e-12);
                checked++;
                if (err <= p.spread()) inSpread++;
            }
        }
        assertTrue(checked > 15000);
        assertTrue("spread should cover some of a random walk, covered " + inSpread + "/" + checked, inSpread > 0);
    }

    @Test
    public void behaviourCountsJumpsAndTurns() {
        OpponentModel m = new OpponentModel();
        long t = 0;
        double x = 0;
        for (int rep = 0; rep < 3; rep++) {
            for (int i = 0; i < 6; i++) m.observe(t++, x += 0.25, 64, 0, true);
            for (int i = 0; i < 6; i++) m.observe(t++, x -= 0.25, 64, 0, true);
        }
        for (int i = 0; i < 4; i++) m.observe(t++, x, 64, 0, true);
        double[] arc = arc(64, 12);
        for (double y : arc) m.observe(t++, x, y, 0, y <= 64);
        OpponentModel.Behavior b = m.behavior();
        assertTrue("turns " + b.turns(), b.turns() >= 5);
        assertEquals(1, b.jumps());
        assertTrue(b.turnRate() > 0 && b.turnRate() < 1);
    }
}
