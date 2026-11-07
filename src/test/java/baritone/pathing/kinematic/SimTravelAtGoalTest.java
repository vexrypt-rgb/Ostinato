/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: SimTravel's "already on the landing" case. presto-v4-fix2 lap 0: after Jump 40>44 the player stood on the
 * landing block, SimProbe answered "there, 0 ticks", and
 * SimTravel claimed the tick without steering, so the last plan's forward and sprint stayed held and carried the
 * player across the one-wide walkway and off. Now SimTravel declines once on the landing, and lets go of its keys whenever it steps aside.
 */
public class SimTravelAtGoalTest {

    @Test
    public void finalDestinationAdvancesOnlyAfterTouchdown() {
        var start = new baritone.api.utils.BetterBlockPos(0, 64, 0);
        var goal = new baritone.api.utils.BetterBlockPos(4, 64, 0);
        var positions = java.util.List.of(start, goal);
        assertEquals(1, SimTravel.ahead(positions, 0, goal, true));
        assertEquals(-1, SimTravel.ahead(positions, 0, goal, false));
        assertEquals(-1, SimTravel.ahead(positions, 0, start, true));
    }

    @Test
    public void intermediateAdvanceStaysBoundedAndCanChainInAir() {
        var positions = new java.util.ArrayList<baritone.api.utils.BetterBlockPos>();
        for (int x = 0; x < 7; x++) positions.add(new baritone.api.utils.BetterBlockPos(x, 64, 0));
        assertEquals(4, SimTravel.ahead(positions, 0, positions.get(4), false));
        assertEquals(-1, SimTravel.ahead(positions, 0, positions.get(5), true));
        assertEquals(6, SimTravel.ahead(positions, 5, positions.get(6), true));
        assertEquals(-1, SimTravel.ahead(positions, 5, positions.get(6), false));
    }

    private static PlayerSim at(PlayerSim.World w, double x, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = 0;
        s.z = z;
        s.vy = -0.0784;
        s.onGround = true;
        return s;
    }

    /**
     * The trace: at t=426 (x 44.744, z -0.010) the box was over blocks 44 and 45 with the feet beside the walkway, so
     * the probe planned on; at t=431 (x 44.402, z 0.294) the box touched block 44 alone and the probe answered "there,
     * 0 ticks", with the Jump still unfinished.
     */
    @Test
    public void onTheLandingByTheBoxWithTheFeetBeside() {
        PlayerSim.World w = TakeoffSlewTest.course();
        PlayerSim before = at(w, 44.744, -0.010);
        assertNotEquals("t=426 not there yet", 0, SimProbe.search(w, before, SimProbe.yawToward(before.x, before.z, 44, 0), 44, 0, 0).ticks());
        PlayerSim s = at(w, 44.402, 0.294);
        SimProbe.Result r = SimProbe.search(w, s, SimProbe.yawToward(s.x, s.z, 44, 0), 44, 0, 0);
        assertTrue(r.found());
        assertEquals("t=431 the probe calls it reached", 0, r.ticks());
    }

    /** What the held keys did: forward and sprint at the yaw toward the landing's centre walk off the far side. */
    @Test
    public void heldSprintLeavesTheWalkway() {
        PlayerSim.World w = TakeoffSlewTest.course();
        PlayerSim s = at(w, 44.433, 0.076);
        s.vx = -0.0327;
        s.vz = 0.0903;
        s.sprinting = true;
        for (int t = 0; t < 10; t++) {
            s.tick(-0.7f, true, true, false);
        }
        assertTrue("off the walkway, z " + s.z, s.z > 1.3 && !s.onGround || s.y < -0.5);
    }

    /** SimTravel steps aside on the landing: by the box with the feet beside it, and run on to its far edge. */
    @Test
    public void onLandingCoversWhereThePlansWentWrong() {
        PlayerSim.World w = TakeoffSlewTest.course();
        baritone.api.utils.BetterBlockPos dest = new baritone.api.utils.BetterBlockPos(44, 0, 0);
        assertTrue("fix2 lap 0 t=426", SimTravel.onLanding(at(w, 44.744, -0.010), dest));
        assertTrue("fix2 lap 0 t=431", SimTravel.onLanding(at(w, 44.402, 0.294), dest));
        assertTrue("fix3 lap 3, box over 44 and 45", SimTravel.onLanding(at(w, 44.720, 0.515), dest));
        assertFalse("past it", SimTravel.onLanding(at(w, 45.400, 0.500), dest));
        assertFalse("on the take-off block", SimTravel.onLanding(at(w, 40.500, 0.500), dest));
        PlayerSim air = at(w, 44.5, 0.5);
        air.onGround = false;
        air.y = 0.6;
        assertFalse("still in the air", SimTravel.onLanding(air, dest));
    }

    /** Presto v4.1: the keys a plan step held are let go on the first tick that presses none, however that tick ends. */
    @Test
    public void heldKeysAreReleasedOnHandover() {
        assertTrue("held, then stepped aside or advanced without pressing", SimTravel.releases(true, false));
        assertFalse("still pressing its plan", SimTravel.releases(true, true));
        assertFalse("nothing was held", SimTravel.releases(false, false));
        assertFalse("first step of a plan", SimTravel.releases(false, true));
    }
}
