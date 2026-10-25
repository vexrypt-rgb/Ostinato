package baritone.pathing.movement;

import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.*;
import static org.junit.Assert.assertTrue;

/** Ostinato: momentumJumpCosts prices run-up gaps between sprint and the stock walking estimate. */
public class MomentumJumpCostTest {

    @Test
    public void sprintJumpIsSane() {
        for (int d = 2; d <= 3; d++) {
            double t = TickSim.sprintJumpTicks(d);
            System.out.println("sprintJump " + d + " = " + t);
            assertTrue(t > 0 && t < 20);
            assertTrue("faster than the walking price", Math.max(d * SPRINT_ONE_BLOCK_COST, t) < d * WALK_ONE_BLOCK_COST);
        }
        assertTrue(TickSim.sprintJumpTicks(3) > TickSim.sprintJumpTicks(2));
    }

    @Test
    public void parkourPriceIsFlooredAndOrdered() throws Exception {
        java.lang.reflect.Method m = Class.forName("baritone.pathing.movement.movements.MovementParkour")
                .getDeclaredMethod("momentumCost", int.class);
        m.setAccessible(true);
        for (int d = 2; d <= 3; d++) {
            double c = (double) m.invoke(null, d);
            assertTrue(c >= d * SPRINT_ONE_BLOCK_COST - 1e-9); // never under the heuristic's per-block floor
            assertTrue(c < d * WALK_ONE_BLOCK_COST);
        }
    }
}
