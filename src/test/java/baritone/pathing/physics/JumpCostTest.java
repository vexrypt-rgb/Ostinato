package baritone.pathing.physics;

import org.junit.Test;

import static org.junit.Assert.*;

/** Physics travel search: a take-off costs extra so flat ground is run, not hopped. */
public class JumpCostTest {

    @Test
    public void takeOffCostsMoreThanRunning() {
        assertTrue(PhysicsPathfinder.stepCost(true, true) > PhysicsPathfinder.stepCost(false, true));
        assertEquals(PhysicsPathfinder.HOLD + PhysicsPathfinder.JUMP_COST, PhysicsPathfinder.stepCost(true, true));
    }

    @Test
    public void holdingJumpInTheAirIsFree() {
        assertEquals(PhysicsPathfinder.stepCost(false, false), PhysicsPathfinder.stepCost(true, false));
    }
}