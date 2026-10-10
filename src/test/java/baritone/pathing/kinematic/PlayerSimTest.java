package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayerSimTest {

    /** Floor at y=0 (blocks y=-1), plus an optional one-block wall at x=5. */
    private static PlayerSim.World flat(final boolean wall, final boolean step) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++) {
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                            boolean solid = by == -1 || (wall && bx == 5 && by <= 1) || (step && bx >= 5 && by == 0);
                            if (solid) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
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

    private static PlayerSim start(PlayerSim.World w) {
        PlayerSim s = new PlayerSim(w);
        s.x = 0.5; s.z = 0.5; s.onGround = true;
        return s;
    }

    @Test
    public void sprintTopSpeed() {
        PlayerSim s = start(flat(false, false));
        for (int i = 0; i < 60; i++) s.tick(-90, true, true, false);
        double before = s.x;
        s.tick(-90, true, true, false);
        assertEquals(0.2806, s.x - before, 0.002); // vanilla sprint 5.612 m/s
        assertEquals(0.0, s.y, 1e-9);
    }

    @Test
    public void speedEffectScalesGroundSpeed() {
        PlayerSim s = start(flat(false, false));
        s.speedScale = 1.2;
        for (int i = 0; i < 60; i++) s.tick(-90, true, true, false);
        double before = s.x;
        s.tick(-90, true, true, false);
        assertEquals(0.2806 * 1.2, s.x - before, 0.002);
    }

    /** In the air the effect adds nothing: a standing jump forward goes as far with it as without. */
    @Test
    public void speedEffectLeavesAirControlAlone() {
        double[] reach = new double[2];
        for (int k = 0; k < 2; k++) {
            PlayerSim s = start(flat(false, false));
            s.speedScale = k == 0 ? 1 : 1.4;
            s.tick(-90, 0, false, true); // straight up, nothing held
            while (!s.onGround) s.tick(-90, true, false, false);
            reach[k] = s.x;
        }
        assertEquals(reach[0], reach[1], 1e-9);
    }

    /** Jump Boost I adds 0.1 to the jump: 1.836 blocks up, where a plain jump tops out at 1.252. */
    @Test
    public void jumpBoostRaisesTheApex() {
        PlayerSim s = start(flat(false, false));
        s.jumpBoost = 0.1;
        double apex = 0;
        for (int i = 0; i < 30; i++) {
            s.tick(0, false, false, i == 0);
            apex = Math.max(apex, s.y);
        }
        assertEquals(1.8361, apex, 0.001);
        assertTrue(s.onGround);
    }

    @Test
    public void rolloutsCarryTheEffectsAlong() {
        PlayerSim real = start(flat(false, false));
        real.speedScale = 1.2;
        real.jumpBoost = 0.1;
        PlayerSim copy = new PlayerSim(flat(false, false)).copyFrom(real);
        assertEquals(1.2, copy.speedScale, 0);
        assertEquals(0.1, copy.jumpBoost, 0);
    }

    /** The floor of {@link #flat} made of a block that slows (0.4, soul sand or honey) and may halve a jump (honey). */
    private static PlayerSim.World slowFloor(final float jump) {
        final PlayerSim.World floor = flat(false, false);
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                floor.collect(minX, minY, minZ, maxX, maxY, maxZ, out);
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public float speedFactor(int x, int y, int z) {
                return y == -1 ? 0.4f : 1;
            }

            @Override
            public float jumpFactor(int x, int y, int z) {
                return y == -1 ? jump : 1;
            }
        };
    }

    /**
     * On soul sand each tick ends with the speed cut to 0.4 before friction takes its 0.546: the steady step is the
     * push over (1 - 0.4 * 0.546), 2.51 m/s walking where plain ground gives 4.32.
     */
    @Test
    public void soulSandSlowsTheWalk() {
        PlayerSim s = start(slowFloor(1));
        for (int i = 0; i < 60; i++) s.tick(-90, true, false, false);
        double before = s.x;
        s.tick(-90, true, false, false);
        assertEquals(0.098 / (1 - 0.4 * 0.546), s.x - before, 0.001);
        assertEquals(2.51, (s.x - before) * 20, 0.02);
    }

    /** Above the block by more than half a block the drag is gone: a jump off soul sand is slowed only on the way off. */
    @Test
    public void theDragEndsHalfABlockUp() {
        PlayerSim s = start(slowFloor(1));
        s.y = 0.6;
        s.onGround = false;
        s.vx = 0.2;
        s.tick(-90, 0, false, false);
        assertEquals(0.2 * 0.91, s.vx, 1e-9);
        s.y = 0.4;
        s.vy = 0;
        s.vx = 0.2;
        s.tick(-90, 0, false, false);
        assertEquals(0.2 * 0.4f * 0.91, s.vx, 1e-9);
    }

    /** Honey halves the jump: 0.21 up, an apex of 0.38 blocks, not enough for a full block. */
    @Test
    public void honeyHalvesTheJump() {
        PlayerSim s = start(slowFloor(0.5f));
        double apex = 0;
        for (int i = 0; i < 20; i++) {
            s.tick(0, false, false, i == 0);
            apex = Math.max(apex, s.y);
        }
        assertEquals(0.3839, apex, 0.001);
        assertTrue(s.onGround);
    }

    /** A honey block in cell x=1, its box a sixteenth short of the cell on every side, over open air. */
    private static PlayerSim.World honeyWall() {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++) {
                    out.add(new double[]{1.0625, by, -8, 1.9375, by + 0.9375, 8});
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public boolean sticky(int x, int y, int z) {
                return x == 1;
            }
        };
    }

    /** Pressed against the side of honey a fall settles at the slide: 0.05 set each tick, 0.1274 after gravity. */
    @Test
    public void honeySlidesAFallDownItsSide() {
        PlayerSim s = new PlayerSim(honeyWall());
        s.x = 0.5; s.y = 50.5; s.z = 0.5; s.vy = -1.5;
        for (int i = 0; i < 20; i++) s.tick(-90, true, false, false);
        assertEquals(1.0625 - PlayerSim.HALF_WIDTH, s.x, 1e-9);
        assertEquals((-0.05 - 0.08) * 0.98, s.vy, 1e-9);
        double before = s.y;
        s.tick(-90, true, false, false);
        assertEquals(-0.1274, s.y - before, 1e-9);
    }

    /** A block away from the honey the same fall is a plain one. */
    @Test
    public void awayFromTheSideThereIsNoSlide() {
        PlayerSim s = new PlayerSim(honeyWall());
        s.x = -0.5; s.y = 50.5; s.z = 0.5; s.vy = -1.5;
        s.tick(-90, 0, false, false);
        assertEquals((-1.5 - 0.08) * 0.98, s.vy, 1e-9);
    }

    @Test
    public void jumpApex() {
        PlayerSim s = start(flat(false, false));
        double apex = 0;
        for (int i = 0; i < 20; i++) {
            s.tick(0, false, false, i == 0);
            apex = Math.max(apex, s.y);
        }
        assertEquals(1.2522, apex, 0.001);
        assertTrue(s.onGround);
    }

    @Test
    public void wallStopsAndStepClimbsSlab() {
        PlayerSim w = start(flat(true, false));
        for (int i = 0; i < 40; i++) w.tick(-90, true, true, false);
        assertEquals(4.7, w.x, 1e-6);
        PlayerSim s = start(flat(false, true));
        for (int i = 0; i < 40; i++) s.tick(-90, true, false, false);
        assertTrue(s.x < 5.0); // a full block is higher than the 0.6 step
    }
}
