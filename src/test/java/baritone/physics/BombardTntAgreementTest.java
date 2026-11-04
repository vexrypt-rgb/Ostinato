package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import baritone.bombard.TntMechanics;
import org.junit.Test;

import static baritone.physics.PpeWorlds.*;
import static org.junit.Assert.*;

/**
 * Ostinato (Bombard): Bombard's closed-form TNT tick ({@link TntMechanics#step}) must agree with the predictive physics
 * engine, the authority on TNT flight, tick by tick on a flat stone floor. A disagreement means one of them is wrong.
 */
public class BombardTntAgreementTest {
    private static final Terrain FLAT = flat(STONE);

    private static void agree(double vx, double vy, double vz) {
        double[] s = {0.5, 64, 0.5, vx, vy, vz};
        for (int k = 1; k <= 60; k++) {
            s = TntMechanics.step(s, 64);
            BodyState tnt = BodyState.builder(BodyState.Type.PRIMED_TNT).pos(0.5, 64, 0.5).vel(vx, vy, vz).fuse(80, 4).build();
            PredictionRequest q = PredictionRequest.trajectory(tnt, k).fidelity(PredictionRequest.Fidelity.HIGH).build();
            PredictionResult r = new Predictor().run(q, FLAT, q.fidelity, () -> false);
            String at = "tick " + k + " from v=(" + vx + ", " + vy + ", " + vz + ")";
            assertEquals(at, PredictionResult.Status.OK, r.status);
            assertEquals(at, s[0], r.x, 1e-9);
            assertEquals(at, s[1], r.y, 1e-9);
            assertEquals(at, s[2], r.z, 1e-9);
            assertEquals(at, s[3], r.vx, 1e-9);
            assertEquals(at, s[4], r.vy, 1e-9);
            assertEquals(at, s[5], r.vz, 1e-9);
        }
    }

    @Test
    public void aLitBlocksHopAgrees() {
        agree(TntMechanics.SPAWN_VH, TntMechanics.SPAWN_VY, -TntMechanics.SPAWN_VH);
    }

    @Test
    public void aSpearJabAgrees() {
        double[] v = TntMechanics.spearPush(30, 1);
        agree(v[0], v[1], v[2]);
    }

    @Test
    public void aRodReelAgrees() {
        double[] v = TntMechanics.rodPull(0.5, 64, 0.5, -4.5, 65, 3.5);
        agree(v[0], v[1], v[2]);
    }
}
