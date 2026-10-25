package baritone.pathing.movement;

import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: TickSim agrees with the vanilla speeds the stock tables were measured from. */
public class TickSimTest {

    @Test
    public void steadyStateMatchesTables() {
        assertEquals(WALK_ONE_BLOCK_COST, 1 / TickSim.steadyGroundSpeed(false), 0.005);
        assertEquals(SPRINT_ONE_BLOCK_COST, 1 / TickSim.steadyGroundSpeed(true), 0.005);
        assertEquals(WALK_OFF_BLOCK_COST, TickSim.walkOffTicks(false), 0.01);
    }

    @Test
    public void standstillCostsMore() {
        assertTrue(TickSim.standstillPenalty(1, false) > 0.5);
    }

    @Test
    public void hopsAreSane() {
        double up = TickSim.ascendTicks(false), down = TickSim.descendLandTicks(false);
        System.out.println("ascend " + up + " walkOff " + TickSim.walkOffTicks(false) + " land " + down
                + " standstill " + TickSim.standstillPenalty(1, false));
        assertTrue(up >= WALK_ONE_BLOCK_COST && up < 15); // never below the heuristic's per-block floor
        assertTrue(down >= FALL_N_BLOCKS_COST[1] - 0.01 && down < 10);
    }
}
