package baritone.pathing.kinematic;

import baritone.api.pathing.movement.ActionCosts;
import baritone.utils.ExperimentalMovement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: the planner's effect tables against vanilla 26.3 and against what the planner allowed before. */
public class EffectReachTest {

    @Test
    public void plainReachMatchesPlannerLimits() {
        // MovementParkour without effects: flat up to dist 4 (3 gap) with sprint, ascend up to dist 3, never 2 up
        assertTrue(EffectReach.lands(0, 1, 4, 0, false));
        assertTrue(EffectReach.lands(0, 1, 3, 1, false));
        assertFalse(EffectReach.lands(0, 1, 4, 1, false));
        assertFalse(EffectReach.lands(0, 1, 2, 2, false));
    }

    @Test
    public void jumpBoostReachesFurtherAndHigher() {
        assertTrue(EffectReach.lands(1, 1, 4, 1, false));   // Jump Boost I: a 3 gap up one
        assertTrue(EffectReach.lands(1, 1, 5, 0, true));    // and a 4 gap flat off a run-up
        assertFalse(EffectReach.lands(1, 1, 2, 2, false));  // but not two up: apex 1.84
        assertTrue(EffectReach.lands(2, 1, 2, 2, false));   // Jump Boost II: two up (apex 2.52)
        assertTrue(EffectReach.lands(2, 1, 4, 2, false));
        assertTrue(EffectReach.lands(3, 1, 5, 2, false));   // III
    }

    @Test
    public void turtleMasterShortensReach() {
        double turtle = 1 - 0.15 * 4; // Slowness IV
        assertTrue(EffectReach.lands(0, turtle, 3, 0, false));
        assertFalse(EffectReach.lands(0, turtle, 4, 0, false));
        assertTrue(EffectReach.lands(0, turtle, 3, 1, false));
    }

    @Test
    public void apexIsVanillaJump() {
        for (int lv = 0; lv <= 5; lv++) {
            double v = 0.42F + (lv == 0 ? 0 : 0.1F * lv), y = 0, top = 0;
            while (v > 0) {
                y += v;
                top = Math.max(top, y);
                v = (v - 0.08) * 0.98;
            }
            assertEquals(top, EffectReach.apex(lv), 1e-6);
        }
        assertEquals(1.2522, EffectReach.apex(0), 1e-3);
        assertEquals(1.836, EffectReach.apex(1), 1e-3);
        assertEquals(2.517, EffectReach.apex(2), 1e-3);
    }

    @Test
    public void slowFallTicksFollowVanilla() {
        double depth = 0, v = 0;
        int t = 0;
        int[] check = {1, 10, 50, 300};
        int k = 0;
        while (k < check.length) {
            depth -= v;
            v = (v - 0.01) * 0.98;
            t++;
            while (k < check.length && depth >= check[k]) {
                assertEquals("blocks " + check[k], t, EffectReach.slowFallTicks(check[k]), 0);
                k++;
            }
        }
        // terminal speed 0.49 blocks a tick
        assertEquals(1000 / 0.49, EffectReach.slowFallTicks(2000) - EffectReach.slowFallTicks(1000), 3);
        // far slower than a plain fall
        assertTrue(EffectReach.slowFallTicks(20) > 3 * ActionCosts.FALL_N_BLOCKS_COST[20]);
    }

    @Test
    public void resistanceAndSafeFall() {
        assertEquals(0.8, ClientWorld.resistanceFactor(0), 1e-9);
        assertEquals(0.4, ClientWorld.resistanceFactor(2), 1e-9); // Turtle Master: Resistance III
        assertEquals(0.2, ClientWorld.resistanceFactor(3), 1e-9); // strong Turtle Master: Resistance IV
        assertEquals(0, ClientWorld.resistanceFactor(4), 1e-9);
        assertEquals(0, ClientWorld.resistanceFactor(9), 1e-9);
        assertEquals(3, ExperimentalMovement.fallDamage(9, 6)); // Jump Boost III
    }

    @Test
    public void scaledHeuristicStaysAdmissible() {
        double[] scales = {1, 1.2, 1.4, 1.2 * 0.85, 0.85, 0.4};
        for (double speed : scales) {
            double walkCostScale = 1 / speed, heuristicScale = Math.min(1, walkCostScale);
            double cheapest = ActionCosts.SPRINT_ONE_BLOCK_COST * walkCostScale;
            double heuristic = 3.563 * heuristicScale; // Settings.costHeuristic default, per block
            assertTrue("speed " + speed, heuristic <= cheapest);
        }
    }

}