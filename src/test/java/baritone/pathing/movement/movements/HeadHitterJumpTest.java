package baritone.pathing.movement.movements;

import baritone.pathing.kinematic.JumpTemplates;
import baritone.pathing.kinematic.PlayerSim;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: head-hitter jumps are checked in the sim against the real ceiling instead of being refused. */
public class HeadHitterJumpTest {

    /** Floor at y=-1 for x <= 0 and x == dest, a solid ceiling at y=ceil (or none if ceil < 0). */
    private static PlayerSim.World course(final int dest, final int ceil) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++) {
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                            boolean floor = by == -1 && (bx <= 0 && bx >= -14 || bx >= dest && bx <= dest + 30);
                            boolean roof = ceil >= 0 && by == ceil && bx >= -14 && bx <= dest + 30;
                            if (floor || roof) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                        }
                    }
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    private static boolean lands(int a, int ceil) {
        return lands(a, ceil, 99);
    }

    private static boolean lands(int a, int ceil, int maxRunUp) {
        int[] f = MovementJump.FRAMES[0];
        for (JumpTemplates.Template t : JumpTemplates.HEAD_HITTERS) {
            if (t.runUp <= maxRunUp && t.dy == 0 && t.b == 0 && t.a == a && !t.neo
                    && MovementJump.headHitterLands(course(a * f[0], ceil), 0, 0, 0, f, t, a * f[0], 0, a * f[1])) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void gapOneUnderTwoHighCeiling() {
        assertTrue(lands(2, 2));
    }

    @Test
    public void gapTwoUnderFullTwoHighRoofNeedsMomentum() {
        // Ostinato: a short run-up caps the arc too low (refused), a long run of timed hops makes it (real game agrees)
        assertFalse(lands(3, 2, 4));
        assertTrue(lands(3, 2));
    }

    @Test
    public void openSkyStillLands() {
        assertTrue(lands(3, -1));
    }

    @Test
    public void tooFarIsRefused() {
        assertFalse(lands(6, 2));
    }
}
