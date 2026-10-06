package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ClimbTemplatesTest {

    /** A ladder column at x=2 hanging on a wall at x=3, floor at y=-1 for x<=1. */
    private static PlayerSim.World ladderWorld() {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++)
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++)
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                            if ((by == -1 && bx <= 1) || (bx == 3 && by >= -5)) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                        }
                out.add(new double[]{2.8125, -5, -50, 3, 50, 50}); // the ladder's plate
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return x == 2 && y >= -5 && y <= 50;
            }
        };
    }

    @Test
    public void hangingOnALadderSlowsTheFall() {
        PlayerSim s = new PlayerSim(ladderWorld());
        s.x = 2.5;
        s.y = 10;
        s.z = 0.5;
        for (int i = 0; i < 30; i++) s.tick(0, 0, false, false);
        assertTrue("slides at most 0.15/tick, fell " + (10 - s.y), 10 - s.y <= 30 * 0.15 + 0.2);
    }

    @Test
    public void pressingIntoALadderClimbs() {
        PlayerSim s = new PlayerSim(ladderWorld());
        s.x = 2.5;
        s.y = 0;
        s.z = 0.5;
        for (int i = 0; i < 20; i++) s.tick(-90, 1, false, false); // yaw -90 faces +x, into the wall
        assertEquals("vanilla climbs 2.35 blocks/s", 2.35, s.y, 0.2);
    }

    @Test
    public void templatesLoadAndAreSane() {
        assertFalse(ClimbTemplates.ALL.isEmpty());
        boolean grab = false, leap = false;
        for (ClimbTemplates.Template t : ClimbTemplates.ALL) {
            grab |= t.mode == ClimbTemplates.GRAB;
            leap |= t.mode == ClimbTemplates.LEAP;
            assertTrue(t.ticks > 0 && t.ticks < 80);
            assertEquals(JumpSearch.DIMS, t.plan.length);
            boolean ladders = false;
            for (int[] c : t.cells) ladders |= c[3] == 2;
            assertTrue("every template names its ladder cell(s)", ladders);
        }
        assertTrue(grab && leap);
    }

    @Test
    public void aGrabTemplateReplaysInItsOwnWorld() {
        ClimbTemplates.Template t = null;
        for (ClimbTemplates.Template c : ClimbTemplates.ALL) {
            if (c.mode == ClimbTemplates.GRAB && c.wall == ClimbTemplates.AHEAD && c.a == 2 && c.dy == 0 && c.b == 0) t = c;
        }
        assertNotNull(t);
        // floor under -runUp..0, ladder at (2,0,0) stuck to a wall at x=3: the template's own geometry, by hand
        final int runUp = t.runUp;
        PlayerSim.World w = new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int x = -runUp; x <= 0; x++) out.add(new double[]{x, -1, 0, x + 1, 0, 1});
                out.add(new double[]{3, 0, 0, 4, 1, 1});
                out.add(new double[]{3 - 0.1875, 0, 0, 3, 1, 1});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return x == 2 && y == 0 && z == 0;
            }
        };
        JumpSearch js = new JumpSearch(w);
        js.dirX = 1;
        js.dirZ = 0;
        js.edge = 1;
        js.destX = 2;
        js.destY = 0;
        js.destZ = 0;
        js.grab = true;
        PlayerSim s = new PlayerSim(w);
        s.x = -runUp + 0.5;
        s.z = 0.5 + t.lateral;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        System.arraycopy(t.plan, 0, js.plan, 0, JumpSearch.DIMS);
        assertTrue(js.run(s, js.plan, false, 0, null));
    }
}
