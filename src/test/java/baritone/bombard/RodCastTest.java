package baritone.bombard;

import org.junit.Test;
import static org.junit.Assert.*;

public class RodCastTest {
    private static final double EYE = 65.62, HALF = TntMechanics.SIZE / 2;

    /** TNT feet at the same spot for every PPE tick 0..9. */
    private static float[] still(double x, double y, double z) {
        float[] path = new float[3 * (RodCast.HORIZON + 1)];
        for (int i = 0; i <= RodCast.HORIZON; i++) { path[3 * i] = (float)x; path[3 * i + 1] = (float)y; path[3 * i + 2] = (float)z; }
        return path;
    }

    @Test public void levelCastMatchesVanillaFirstTick() {
        var p = RodCast.point(0, EYE, 0, 0, 0, 1);
        assertEquals(0, p.x(), 1e-9);
        assertEquals(1.4, p.z(), 1e-9);
        assertEquals(EYE - (double)0.03f, p.y(), 1e-9);
    }

    @Test public void pointFollowsTheVanillaHookTick() {
        float yaw = 30, pitch = -20;
        double y = Math.toRadians(yaw), e = Math.toRadians(-pitch), h = 0.6 * Math.cos(e) + 0.5;
        double px = -Math.sin(y) * 0.3, py = EYE, pz = Math.cos(y) * 0.3;
        double vx = -Math.sin(y) * h, vy = Math.tan(e) * h, vz = Math.cos(y) * h;
        for (int k = 1; k <= 8; k++) {
            vy -= (double)0.03f; px += vx; py += vy; pz += vz; vx *= 0.92; vy *= 0.92; vz *= 0.92;
            var p = RodCast.point(0, EYE, 0, yaw, pitch, k);
            assertEquals(px, p.x(), 1e-9); assertEquals(py, p.y(), 1e-9); assertEquals(pz, p.z(), 1e-9);
        }
    }

    @Test public void marginFollowsComputeMargin() {
        assertEquals(0, RodCast.margin(1), 0);
        assertEquals(0, RodCast.margin(2), 0);
        assertEquals(0.05, RodCast.margin(3), 1e-6);
        assertEquals(0.3, RodCast.margin(8), 1e-6);
        assertEquals(0.3, RodCast.margin(40), 1e-6);
    }

    @Test public void segmentCrossingBetweenHookPointsIsAHit() {
        // Spawn z 0.3 and first move end z 1.4 both lie outside the box [0.39, 1.37]; only the swept segment enters it.
        float[] path = still(0, EYE - HALF, 0.88);
        assertEquals(1, RodCast.hits(0, EYE, 0, 0, 0, path, 1, RodCast.SHRINK));
    }

    @Test public void theOneTickLateTntMustAlsoBeHit() {
        float[] path = still(0, EYE - HALF, 0.88);
        for (int i = 2; i <= RodCast.HORIZON; i++) path[3 * i + 1] = 1000;
        assertEquals(-1, RodCast.hits(0, EYE, 0, 0, 0, path, 1, RodCast.SHRINK));
    }

    @Test public void aGrazeIsNotAHit() {
        // 0.52 sideways: inside the box grown by the shrink allowance, outside the shrunk one.
        float[] path = still(0.52, EYE - HALF, 0.88);
        assertEquals(-1, RodCast.hits(0, EYE, 0, 0, 0, path, 1, RodCast.SHRINK));
        assertEquals(1, RodCast.hits(0, EYE, 0, 0, 0, still(0.30, EYE - HALF, 0.88), 1, RodCast.SHRINK));
    }

    @Test public void solveAccountsForGravityAndItsRotationHits() {
        float[] path = still(0, 64, 4);
        var solution = RodCast.solve(0, EYE, 0, path, 1);
        assertNotNull(solution);
        assertTrue(solution.ticks() >= 1 && solution.ticks() <= RodCast.MAX_TICKS);
        assertEquals(solution.ticks(), RodCast.hits(0, EYE, 0, solution.yaw(), solution.pitch(), path, 1, RodCast.SHRINK));
        assertEquals(0, solution.yaw(), 1e-3);
    }

    @Test public void heldRotationOffTheSolutionMisses() {
        float[] path = still(0, 64, 4);
        var solution = RodCast.solve(0, EYE, 0, path, 1);
        assertNotNull(solution);
        assertEquals(-1, RodCast.hits(0, EYE, 0, solution.yaw() + 10, solution.pitch(), path, 1, RodCast.SHRINK));
        assertEquals(-1, RodCast.hits(0, EYE, 0, solution.yaw(), solution.pitch() - 15, path, 1, RodCast.SHRINK));
    }

    @Test public void fallingTntOverAWallIsReachable() {
        // Live rod fixture geometry: eye (0.5, 251.62, 0.5); TNT dropped at (0.5, 254, 4.5) from rest.
        float[] path = new float[3 * (RodCast.HORIZON + 1)];
        double y = 254, vy = 0;
        for (int i = 0; i <= RodCast.HORIZON; i++) {
            path[3 * i] = 0.5f; path[3 * i + 1] = (float)y; path[3 * i + 2] = 4.5f;
            vy -= 0.04; y += vy; vy *= (double)0.98f;
        }
        var solution = RodCast.solve(0.5, 251.62, 0.5, path, 1);
        assertNotNull(solution);
        assertTrue(solution.pitch() < 0);
        // Centred in its window: a few degrees of look overshoot either way still hits.
        for (float d : new float[]{-4, 4})
            assertTrue(RodCast.hits(0.5, 251.62, 0.5, solution.yaw(), solution.pitch() + d, path, 1, RodCast.SHRINK) > 0);
        var ahead = RodCast.lead(0.5, 251.62, 0.5, path, 1, 1);
        assertNotNull(ahead);
        assertTrue("falling TNT is met lower a tick later", ahead.pitch() > solution.pitch());
        assertEquals(ahead, RodCast.solve(0.5, 251.62, 0.5, java.util.Arrays.copyOfRange(path, 3, path.length), 1));
        assertNull(RodCast.lead(0.5, 251.62, 0.5, path, 1, 100));
    }

    @Test public void heightAtAgreesWithPoint() {
        for (int k = 1; k <= 8; k++) {
            var p = RodCast.point(0, EYE, 0, 0, -25, k);
            assertEquals(p.y(), RodCast.heightAt(EYE, -25, p.z()), 1e-9);
        }
    }

    @Test public void unreachableOrMissingPathIsRejected() {
        assertNull(RodCast.solve(0, 65, 0, null, 1));
        assertNull(RodCast.solve(0, 65, 0, still(0, 64, 4), 4));
        assertNull(RodCast.solve(0, 65, 0, still(1000, 1000, 1000), 1));
        assertEquals(-1, RodCast.hits(0, 65, 0, 0, 0, null, 1, RodCast.SHRINK));
    }
}
