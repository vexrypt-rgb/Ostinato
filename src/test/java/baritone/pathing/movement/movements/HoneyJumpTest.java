package baritone.pathing.movement.movements;

import baritone.pathing.kinematic.PlayerSim;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
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
    public void honeyAndSoulSandAreSlowTakeoffs() {
        assertTrue(MovementParkour.slowTakeoff(0.4f, 0.5f));  // honey
        assertTrue(MovementParkour.slowTakeoff(0.4f, 1f));    // soul sand
        assertFalse(MovementParkour.slowTakeoff(1f, 1f));     // stone
    }

    @Test
    public void soulSandSlowsTheRunUpInTheSim() {
        assertTrue(runUp(0.4f) < runUp(1f) * 0.7); // vanilla: the 0.4 drag only hits the carried speed, the fresh push moves in full
    }

    private static double runUp(final float speed) {
        PlayerSim s = new PlayerSim(new PlayerSim.World() {
            @Override
            public void collect(double a, double b, double c, double d, double e, double f, List<double[]> out) {
                if (b < 0) out.add(new double[]{-50, -1, -50, 50, 0, 50});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public float speedFactor(int x, int y, int z) {
                return speed;
            }
        });
        s.x = 0.5; s.z = 0.5; s.onGround = true; s.vy = -0.0784000015258789;
        for (int t = 0; t < 20; t++) s.tick(-90f, true, true, false);
        return s.x - 0.5;
    }

    @Test
    public void slabOrCarpetOverHoneyReadsTheHoney() {
        assertEquals(149, MovementParkour.factorCellY(151, 0.5, 0));       // on a bottom slab (cell 150) over honey (149)
        assertEquals(149, MovementParkour.factorCellY(150, 0, 1 / 16.0));  // in a carpet's cell (150) over honey (149)
        assertEquals(150, MovementParkour.factorCellY(151, 1.0, 0));       // on a full block: that block itself
    }

    /** Bottom slab (top y=0.5 in cell 0) over honey (cell -1): vanilla's block-below fallback halves the jump. */
    @Test
    public void slabOverHoneyHalvesTheJumpInTheSim() {
        PlayerSim s = new PlayerSim(new PlayerSim.World() {
            @Override
            public void collect(double a, double b, double c, double d, double e, double f, List<double[]> out) {
                out.add(new double[]{-5, -1, -5, 5, 0, 5});
                out.add(new double[]{-5, 0, -5, 5, 0.5, 5});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public float jumpFactor(int x, int y, int z) {
                return y == -1 ? 0.5f : 1f;
            }
        });
        s.x = 0.5; s.z = 0.5; s.y = 0.5; s.onGround = true; s.vy = -0.0784000015258789;
        double top = 0.5;
        for (int t = 0; t < 20; t++) {
            s.tick(0f, false, false, t == 0);
            top = Math.max(top, s.y);
        }
        assertTrue("apex " + top, top - 0.5 < 0.5); // can't even reach another slab's height, let alone a block
    }
    @Test
    public void onlyHoneysFactorIsALowJump() {
        assertTrue(MovementParkour.lowJump(0.5f));
        assertFalse(MovementParkour.lowJump(1f));
    }
}
