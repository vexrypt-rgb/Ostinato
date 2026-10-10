package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the extension controller's gating rests on: how wide a gap a sprint jump from a run-up crosses on its own. */
public class JumpReachTest {

    /** Floor at y=-1 for x < 0 and x >= gap, nothing between. */
    private static PlayerSim.World gap(final int width) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                        if (PlayerSim.floor(minY) <= -1 && PlayerSim.floor(maxY) >= -1 && (bx < 0 || bx >= width)) {
                            out.add(new double[]{bx, -1, bz, bx + 1, 0, bz + 1});
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

    /** Whether any single jump tick, taken from a long run-up, carries the player over a gap of this width. */
    private static boolean crossable(int width) {
        for (int delay = 0; delay < 40; delay++) {
            PlayerSim s = new PlayerSim(gap(width));
            s.x = -9.5;
            s.z = 0.5;
            s.onGround = true;
            boolean left = false;
            for (int t = 0; t < 80 && s.y > -3; t++) {
                s.tick(-90f, true, true, t == delay);
                left |= !s.onGround;
            }
            if (left && s.onGround && s.x >= width) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void sprintJumpCrossesAFourBlockGap() {
        assertTrue(crossable(4)); // parkour of distance 5
    }

    @Test
    public void fiveBlockGapIsOutOfReach() {
        assertFalse(crossable(5)); // distance 6 needs a placed block: the extension jump
    }
}
