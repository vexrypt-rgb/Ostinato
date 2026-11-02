/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics.mc;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: the region a request's snapshot covers (no game needed). */
public class PpeRegionTest {

    private static int[] region(PredictionRequest r) {
        int[] b = new int[6];
        PredictivePhysics.region(r, b);
        return b;
    }

    private static int sections(int[] b) {
        return ((b[3] >> 4) - (b[0] >> 4) + 1) * ((b[4] >> 4) - (b[1] >> 4) + 1) * ((b[5] >> 4) - (b[2] >> 4) + 1);
    }

    @Test
    public void explosionCoversItsDamageRadius() {
        int[] b = region(PredictionRequest.explosion(0.5, 64, 0.5, 4, true).build());
        assertArrayEquals(new int[]{-9, 55, -9, 9, 73, 9}, b); // 2 * power + 1 each way
    }

    @Test
    public void standingFallReachesDownNotOut() {
        BodyState p = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).build();
        int[] b = region(PredictionRequest.fall(p, 200).build());
        assertArrayEquals(new int[]{-2, 64 - PredictivePhysics.REACH_DOWN, -2, 2, 67, 2}, b);
        assertTrue(sections(b) <= 4 * 8);
    }

    @Test
    public void walkingWidensWithTheHorizon() {
        BodyState p = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).onGround(true).build();
        PredictionRequest.Input fwd = new PredictionRequest.Input(1, 0, false, true, false, Float.NaN, Float.NaN);
        int[] near = region(PredictionRequest.trajectory(p, 20).input(fwd).build());
        int[] far = region(PredictionRequest.trajectory(p, 60).input(fwd).build());
        assertTrue(near[3] - 0.5 >= 0.45 * 20); // sprint-jumping is ~0.3 blocks a tick
        assertTrue(far[3] > near[3]);
    }

    @Test
    public void fastBodiesAreCappedSoTheRegionStaysBounded() {
        BodyState elytra = BodyState.builder(BodyState.Type.PLAYER).pos(15.9, 64, 15.9).vel(2, 0, 2).flags(BodyState.FALL_FLYING).build();
        int[] b = region(PredictionRequest.trajectory(elytra, 1200).build());
        assertEquals(PredictivePhysics.REACH_H, b[3] - 15, 1);
        assertTrue(sections(b) <= 360);
        assertTrue(sections(b) <= TerrainCapture.MAX_REGION);
        BodyState arrow = BodyState.builder(BodyState.Type.ARROW).pos(0.5, 64, 0.5).vel(3, 0, 0).build();
        int[] a = region(PredictionRequest.projectile(arrow, 100).build());
        assertEquals(PredictivePhysics.REACH_H, a[3], 1);
    }

    @Test
    public void primedTntIncludesItsBlast() {
        BodyState tnt = BodyState.builder(BodyState.Type.PRIMED_TNT).pos(0.5, 64, 0.5).build();
        int[] b = region(PredictionRequest.trajectory(tnt, 80).build());
        assertTrue(b[3] >= 9 && b[0] <= -9 && b[4] >= 64 + 9);
    }
}
