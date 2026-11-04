/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato Presto v4: the warm-up's terrain is a real flat ground, and it starts once. */
public class PredictorWarmUpTest {

    @Test
    public void warmUpGroundHoldsAStandingPlayer() {
        PredictionRequest r = PredictionRequest.trajectory(BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).onGround(true).build(), 20).build();
        PredictionResult out = new Predictor().run(r, PredictorWarmUp.ground(), r.fidelity, () -> false);
        assertEquals(PredictionResult.Status.OK, out.status);
        assertEquals(64, out.y, 1e-9);
        assertTrue(out.onGround);
    }

    /** The fall warm-up (Grace Note's request shape) lands on that ground inside its horizon. */
    @Test
    public void warmUpFallLands() {
        BodyState high = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 76, 0.5).vel(0, -0.5, 0).build();
        PredictionRequest f = PredictionRequest.fall(high, 40).fidelity(PredictionRequest.Fidelity.SCREENING).reach(4.5f).build();
        PredictionResult out = new Predictor().run(f, PredictorWarmUp.ground(), f.fidelity, () -> false);
        assertEquals(PredictionResult.Status.OK, out.status);
        assertTrue(out.onGround);
        assertEquals(64, out.y, 1e-9);
    }

    /** It starts once, and says when it is done (what Grace Note waits for before its own warm-up forecast). */
    @Test
    public void runsStartsOnceAndFinishes() throws InterruptedException {
        PredictorWarmUp.run(3);
        PredictorWarmUp.start(); // true unless another test in this JVM started it first
        assertFalse("a second start is a no-op", PredictorWarmUp.start());
        long until = System.nanoTime() + 60_000_000_000L;
        while (!PredictorWarmUp.done() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(PredictorWarmUp.done());
    }
}
