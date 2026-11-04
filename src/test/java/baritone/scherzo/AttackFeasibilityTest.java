/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import baritone.scherzo.AttackFeasibility.Verdict;
import org.junit.Test;

import static org.junit.Assert.*;

public class AttackFeasibilityTest {

    // a player: 0.6 wide, 1.8 tall, eye 1.62 over its feet; the spear's 26.3 range: 2.0..4.5, margin 0.125
    private static final double W = 0.6, H = 1.8, EYE = 64 + 1.62, MIN = 2.0, MAX = 4.5, MARGIN = 0.125;

    private static Prediction foe(double x, double y, double z, double spread, double bound, double up, double down) {
        return new Prediction(x, y, z, 0, 0, 0, spread, bound, up, down, 3, 0, Prediction.Confidence.HIGH, 0);
    }

    private static Verdict ahead(Prediction p, double to) {
        return AttackFeasibility.ray(0, EYE, 0, 0, 0, 1, MIN, to, MARGIN, p, W, H);
    }

    @Test
    public void lookMatchesVanillaViewVector() {
        double[] south = AttackFeasibility.look(0, 0);
        assertArrayEquals(new double[]{0, 0, 1}, south, 1e-12);
        double[] east = AttackFeasibility.look(0, -90); // yaw -90 faces +x
        assertEquals(1, east[0], 1e-12);
        assertEquals(0, east[2], 1e-12);
        double[] down = AttackFeasibility.look(90, 0);
        assertEquals(-1, down[1], 1e-12);
    }

    @Test
    public void slabTest() {
        assertEquals(0.3, AttackFeasibility.enter(0, 0, 0, 0, 0, 10, -1, -1, 3, 1, 1, 4), 1e-12);
        assertEquals(0, AttackFeasibility.enter(0, 0, 3.5, 0, 0, 10, -1, -1, 3, 1, 1, 4), 1e-12); // starts inside
        assertTrue(Double.isNaN(AttackFeasibility.enter(0, 2, 0, 0, 0, 10, -1, -1, 3, 1, 1, 4))); // parallel, above
        assertTrue(Double.isNaN(AttackFeasibility.enter(0, 0, 0, 0, 0, 2, -1, -1, 3, 1, 1, 4))); // stops short
    }

    @Test
    public void fourLevels() {
        assertEquals(Verdict.SURE, ahead(foe(0, 64, 3, 0.05, 0.1, 0.05, 0.05), MAX));
        assertEquals(Verdict.LIKELY, ahead(foe(0, 64, 3, 0.1, 0.5, 0.05, 0.05), MAX));
        assertEquals(Verdict.POSSIBLE, ahead(foe(0.8, 64, 3, 0.1, 1.0, 0.05, 0.05), MAX));
        assertEquals(Verdict.MISS, ahead(foe(3, 64, 3, 0.1, 0.5, 0.05, 0.05), MAX));
        assertEquals(Verdict.UNKNOWN, ahead(null, MAX));
    }

    @Test
    public void insideMinimumReachIsMissed() {
        // vanilla starts the jab's segment at the minimum reach: a box wholly closer is never crossed
        assertEquals(Verdict.MISS, ahead(foe(0, 64, 1.5, 0.05, 0.05, 0.05, 0.05), MAX));
    }

    @Test
    public void motionAlongTheLookLengthensTheRay() {
        Prediction far = foe(0, 64, 5, 0.05, 0.05, 0.05, 0.05);
        assertEquals(Verdict.MISS, ahead(far, MAX));
        assertEquals(Verdict.SURE, ahead(far, MAX + 0.5));
    }

    @Test
    public void entryIsTheNearFaceGrownByTheMargin() {
        Prediction p = foe(0, 64, 3, 0.05, 0.05, 0.05, 0.05);
        assertEquals(3 - W / 2 - MARGIN, AttackFeasibility.entry(0, EYE, 0, 0, 0, 1, MIN, MAX, MARGIN, p, W, H), 1e-12);
        assertTrue(Double.isNaN(AttackFeasibility.entry(0, EYE, 0, 0, 0, 1, MIN, MAX, MARGIN, foe(3, 64, 3, 0, 0, 0, 0), W, H)));
    }

    @Test
    public void aJumpWithinReachIsOnlyPossible() {
        // the ray passes 0.5 over the head of a standing foe: missed where it stands, met if it jumps
        double over = 64 + H + MARGIN + 0.5;
        Prediction standing = foe(0, 64, 3, 0.05, 0.1, 1.25, 0.05);
        assertEquals(Verdict.POSSIBLE, AttackFeasibility.ray(0, over, 0, 0, 0, 1, MIN, MAX, MARGIN, standing, W, H));
        Prediction noJump = foe(0, 64, 3, 0.05, 0.1, 0.05, 0.05);
        assertEquals(Verdict.MISS, AttackFeasibility.ray(0, over, 0, 0, 0, 1, MIN, MAX, MARGIN, noJump, W, H));
    }

    @Test
    public void verticalUncertaintyKeepsSureHonest() {
        // feet anywhere in [63.6, 64.4]: every one of those bodies holds [64.4, 65.4], grown by the margin to
        // [64.275, 65.525]; a ray at 65.0 is sure, one at 65.7 meets the body where predicted but not every one allowed
        Prediction loose = foe(0, 64, 3, 0.05, 0.05, 0.4, 0.4);
        assertEquals(Verdict.SURE, AttackFeasibility.ray(0, 65.0, 0, 0, 0, 1, MIN, MAX, MARGIN, loose, W, H));
        assertEquals(Verdict.LIKELY, AttackFeasibility.ray(0, 65.7, 0, 0, 0, 1, MIN, MAX, MARGIN, loose, W, H));
        // feet anywhere over 2.2 blocks, more than a body and both margins: no height is held by every body
        Prediction wide = foe(0, 64, 3, 0.05, 0.05, 1.1, 1.1);
        for (double y = 63; y <= 67; y += 0.1)
            assertNotEquals("y=" + y, Verdict.SURE, AttackFeasibility.ray(0, y, 0, 0, 0, 1, MIN, MAX, MARGIN, wide, W, H));
    }
}
