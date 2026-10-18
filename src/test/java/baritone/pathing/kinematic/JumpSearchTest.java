package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class JumpSearchTest {

    /** Floor at y=-1 for z<=2 (take-off block z=2), gap at z=3..4, and one landing box at z=5. */
    private static PlayerSim.World gapWorld(double landMinY, double landMaxY) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++)
                    for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                        if (bz <= 2) out.add(new double[]{bx, -1, bz, bx + 1, 0, bz + 1});
                        if (bz == 5 && bx == 0) out.add(new double[]{bx, landMinY, bz, bx + 1, landMaxY, bz + 1});
                    }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    private static boolean gap2(PlayerSim.World w, int destY) {
        JumpSearch js = new JumpSearch(w);
        js.dirX = 0;
        js.dirZ = 1;
        js.edge = 3;
        js.destX = 0;
        js.destY = destY;
        js.destZ = 5;
        PlayerSim start = new PlayerSim(w);
        start.x = 0.5;
        start.y = 0;
        start.z = 0.5;
        start.onGround = true;
        return js.search(start, false);
    }

    @Test
    public void landsOnAFullBlock() {
        assertTrue(gap2(gapWorld(-1, 0), 0));
    }

    /** The look closes at most 32° a tick. A plan aimed 90° off the player is not that plan on the first tick. */
    @Test
    public void yawStepsTowardThePlan() {
        assertEquals(58f, JumpSearch.stepYaw(90f, 0f), 0.01f);
        assertEquals(-32f, JumpSearch.stepYaw(0f, -90f), 0.01f);
        assertEquals(10f, JumpSearch.stepYaw(0f, 10f), 0.01f);
    }

    /**
     * One-wide runway. Facing the jump, the plan lands. Started 90° off, the same inputs walk off the side while
     * the look is still turning, so that plan must not be flown.
     */
    @Test
    public void slewFromTheWrongYawDoesNotFlyTheSnap() {
        PlayerSim.World w = new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                    if (bz <= 2 || bz == 5) out.add(new double[]{0, -1, bz, 1, 0, bz + 1});
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
        JumpSearch js = new JumpSearch(w);
        js.dirX = 0;
        js.dirZ = 1;
        js.edge = 3;
        js.destX = 0;
        js.destY = 0;
        js.destZ = 5;
        PlayerSim start = new PlayerSim(w);
        start.x = 0.5;
        start.y = 0;
        start.z = 2.4;
        start.vz = 0.28;
        start.onGround = true;
        assertTrue(js.search(start, false));
        // A plan that swings from -15° to +90° at takeoff lands when the look snaps. With the 32° cap it does not.
        // Execution still flies a snap plan when no capped plan exists: waiting out the yaw dropped the run-up
        // speed and neo_2wall went 0/8.
        int[] swing = {0, 0, 3, JumpSearch.O1.length - 1, 0, 2, 0};
        assertTrue(js.run(start, swing, false, 0, null));
        js.slewYaw = true;
        js.faceYaw = js.yaw(swing, false, 0);
        assertFalse(js.run(start, swing, false, 0, null));
    }

    @Test
    public void landsOnABlockTopBelowTheCellFloor() {
        // soul sand / chest / farmland one level down: the feet rest at -0.125, still cell 0 as playerFeet counts it
        assertTrue(gap2(gapWorld(-1, -0.125), 0));
    }

    @Test
    public void landsUpOnSoulSandHeight() {
        // a 0.875 block in cell 0: the feet rest at 0.875, which is cell 1
        assertTrue(gap2(gapWorld(0, 0.875), 1));
    }

    @Test
    public void feetCellFollowsPlayerFeet() {
        assertEquals(1, PlayerSim.feetCell(0.875));
        assertEquals(1, PlayerSim.feetCell(1.0));
        assertEquals(0, PlayerSim.feetCell(0.5));
        assertEquals(-1, PlayerSim.feetCell(-0.2));
    }
}
