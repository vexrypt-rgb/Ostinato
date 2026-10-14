package baritone.pathing.movement.movements;

import baritone.pathing.kinematic.PlayerSim;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: honey halves a jump, so nothing is jumped up onto from it; soul sand only slows. */
public class HoneyJumpTest {

    private static double apex(final float jumpFactor) {
        PlayerSim s = new PlayerSim(new PlayerSim.World() {
            @Override
            public void collect(double a, double b, double c, double d, double e, double f, List<double[]> out) {
                if (b < 0) out.add(new double[]{-5, -1, -5, 5, 0, 5});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public float jumpFactor(int x, int y, int z) {
                return jumpFactor;
            }
        });
        s.x = 0.5; s.z = 0.5; s.onGround = true;
        double top = 0;
        for (int t = 0; t < 20; t++) {
            s.tick(0f, false, false, t == 0);
            top = Math.max(top, s.y);
        }
        return top;
    }

    @Test
    public void honeyJumpCannotClimbABlockOrSlab() {
        assertTrue(apex(0.5f) < 0.5);
        assertTrue(apex(1f) > 1.0);
    }

    @Test
    public void onlyHoneysFactorIsALowJump() {
        assertTrue(MovementParkour.lowJump(0.5f));
        assertFalse(MovementParkour.lowJump(1f));
    }
}
