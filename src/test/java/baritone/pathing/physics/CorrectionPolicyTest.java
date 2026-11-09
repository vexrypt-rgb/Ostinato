/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.physics;

import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.physics.CorrectionPolicy.Verdict;
import baritone.pathing.unified.UnifiedExecutor;
import baritone.pathing.unified.UnifiedExecutor.Counter;
import baritone.utils.InputArbiter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Ostinato 2.0 Phase 06, slices B and C: the predicted-vs-observed verdict and the one bounded correction. */
public class CorrectionPolicyTest {

    private static final class Blocks implements PlayerSim.World {
        final Set<Long> solid = new HashSet<>();

        void add(int x, int y, int z) {
            solid.add(((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512));
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++)
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++)
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++)
                        if (solid.contains(((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512))) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }
    }

    /** A three-wide walkway along z, x from -1 to 2. */
    private static Blocks walkway() {
        Blocks w = new Blocks();
        for (int z = -2; z <= 30; z++) for (int x = -1; x <= 1; x++) w.add(x, -1, z);
        return w;
    }

    private static PlayerSim standingAt(PlayerSim.World w, double x, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = 0;
        s.z = z;
        for (int i = 0; i < 3; i++) s.tick(0, false, false, false);
        assertTrue(s.onGround);
        return s;
    }

    /** Walk {@code walk} ticks at yaw 0, then stand until the slide has died out. */
    private static List<PhysicsPathfinder.Action> walkThenStop(int walk, boolean jumpLast) {
        List<PhysicsPathfinder.Action> plan = new ArrayList<>();
        for (int i = 0; i < walk; i++) plan.add(new PhysicsPathfinder.Action(0, true, false, false));
        for (int i = 0; i < 12; i++) plan.add(new PhysicsPathfinder.Action(0, false, false, false));
        if (jumpLast) plan.add(new PhysicsPathfinder.Action(0, false, false, true));
        return plan;
    }

    /** Where the plan ends, flown from {@code from}. */
    private static PlayerSim fly(PlayerSim.World w, PlayerSim from, List<PhysicsPathfinder.Action> plan, int step) {
        PlayerSim s = new PlayerSim(w).copyFrom(from);
        for (int i = step; i < plan.size(); i++) {
            PhysicsPathfinder.Action a = plan.get(i);
            s.tick(a.yaw, a.forward, a.sprint, a.jump);
        }
        return s;
    }

    private static UnifiedExecutor executor() {
        return new UnifiedExecutor(new InputArbiter(), new UnifiedExecutor.Keys() {
            @Override
            public boolean forcedRaw(baritone.api.utils.input.Input input) {
                return false;
            }

            @Override
            public boolean analogForced() {
                return false;
            }

            @Override
            public void releaseRaw(baritone.api.utils.input.Input input) {
            }
        });
    }

    // ---- the verdict ----

    @Test
    public void oneCorrectionAPlan() {
        assertEquals(1, CorrectionPolicy.BUDGET);
    }

    @Test
    public void noPredictionIsUnavailableAndNeverReplans() {
        for (boolean drifted : new boolean[]{false, true}) {
            Verdict v = CorrectionPolicy.judge(false, drifted, 0, false, false);
            assertEquals(Verdict.UNAVAILABLE, v);
            assertFalse(v.replan);
            assertFalse(v.spent);
        }
    }

    @Test
    public void onPlanKeepsThePlan() {
        Verdict v = CorrectionPolicy.judge(true, false, 0, false, false);
        assertEquals(Verdict.ON_PLAN, v);
        assertFalse(v.replan);
        // A spent budget does not matter while the player is on the plan.
        assertEquals(Verdict.ON_PLAN, CorrectionPolicy.judge(true, false, 1, false, false));
    }

    @Test
    public void firstDriftReplaysSecondEscalates() {
        assertEquals(Verdict.REPLAY, CorrectionPolicy.judge(true, true, 0, false, false));
        Verdict second = CorrectionPolicy.judge(true, true, CorrectionPolicy.BUDGET, false, false);
        assertEquals(Verdict.ESCALATED, second);
        assertTrue(second.replan);
        assertFalse("an escalation spends nothing", second.spent);
    }

    @Test
    public void donePlanOrNewGoalEscalatesWithoutAReplay() {
        assertEquals(Verdict.ESCALATED, CorrectionPolicy.judge(true, true, 0, true, false));
        assertEquals(Verdict.ESCALATED, CorrectionPolicy.judge(true, true, 0, false, true));
    }

    @Test
    public void replayDecidesBetweenKeepAndReplan() {
        Verdict kept = CorrectionPolicy.replayed(true);
        assertEquals(Verdict.CORRECTED, kept);
        assertFalse(kept.replan);
        assertTrue(kept.spent);
        Verdict missed = CorrectionPolicy.replayed(false);
        assertEquals(Verdict.MISSED, missed);
        assertTrue(missed.replan);
        assertTrue(missed.spent);
    }

    @Test(expected = IllegalStateException.class)
    public void anUnjudgedReplayIsNotCounted() {
        Verdict.REPLAY.count(executor());
    }

    @Test
    public void countsFollowTheVerdict() {
        UnifiedExecutor ux = executor();
        Verdict.UNAVAILABLE.count(ux);
        assertEquals(1, ux.count(Counter.PREDICTION_UNAVAILABLE));
        assertEquals(0, ux.count(Counter.DIVERGENCE_CHECKS));

        Verdict.ON_PLAN.count(ux);
        assertEquals(1, ux.count(Counter.DIVERGENCE_CHECKS));
        assertEquals(0, ux.count(Counter.DIVERGENCES));

        Verdict.CORRECTED.count(ux);
        assertEquals(2, ux.count(Counter.DIVERGENCE_CHECKS));
        assertEquals(1, ux.count(Counter.DIVERGENCES));
        assertEquals(1, ux.count(Counter.CORRECTIONS));
        assertEquals(1, ux.count(Counter.CORRECTIONS_ACCEPTED));
        assertEquals(0, ux.count(Counter.ESCALATIONS));

        Verdict.MISSED.count(ux);
        assertEquals(2, ux.count(Counter.DIVERGENCES));
        assertEquals(2, ux.count(Counter.CORRECTIONS));
        assertEquals(1, ux.count(Counter.CORRECTIONS_ACCEPTED));
        assertEquals(1, ux.count(Counter.ESCALATIONS));

        Verdict.ESCALATED.count(ux);
        assertEquals(4, ux.count(Counter.DIVERGENCE_CHECKS));
        assertEquals(3, ux.count(Counter.DIVERGENCES));
        assertEquals("an escalation is not a correction", 2, ux.count(Counter.CORRECTIONS));
        assertEquals(2, ux.count(Counter.ESCALATIONS));
        assertEquals(1, ux.count(Counter.PREDICTION_UNAVAILABLE));
    }

    /** The sequence PhysicsTravel runs: two drifts on one plan spend one correction, then escalate. */
    @Test
    public void budgetIsSpentOncePerPlan() {
        UnifiedExecutor ux = executor();
        int used = 0;
        int replans = 0;
        for (int drift = 0; drift < 3; drift++) {
            Verdict v = CorrectionPolicy.judge(true, true, used, false, false);
            if (v == Verdict.REPLAY) {
                used++;
                v = CorrectionPolicy.replayed(true);
            }
            v.count(ux);
            if (v.replan) {
                replans++;
                used = 0; // a replan is a new plan with its own correction
            }
        }
        assertEquals("kept, escalated, then kept on the new plan", 1, replans);
        assertEquals(2, ux.count(Counter.CORRECTIONS));
        assertEquals(2, ux.count(Counter.CORRECTIONS_ACCEPTED));
        assertEquals(1, ux.count(Counter.ESCALATIONS));
    }

    // ---- the replay ----

    @Test
    public void nothingToReplay() {
        Blocks w = walkway();
        PlayerSim from = standingAt(w, 0.5, 0.5);
        PlayerSim scratch = new PlayerSim(w);
        List<PhysicsPathfinder.Action> plan = walkThenStop(10, false);
        assertFalse(CorrectionPolicy.replayReaches(scratch, from, null, 0, 0.5, 0, 0.5, 0.35));
        assertFalse(CorrectionPolicy.replayReaches(scratch, from, plan, -1, 0.5, 0, 0.5, 0.35));
        assertFalse(CorrectionPolicy.replayReaches(scratch, from, plan, plan.size(), 0.5, 0, 0.5, 0.35));
        assertFalse(CorrectionPolicy.replayReaches(scratch, from, new ArrayList<>(), 0, 0.5, 0, 0.5, 0.35));
    }

    @Test
    public void planFromWhereItSaidStillReaches() {
        Blocks w = walkway();
        PlayerSim start = standingAt(w, 0.5, 0.5);
        List<PhysicsPathfinder.Action> plan = walkThenStop(12, false);
        PlayerSim end = fly(w, start, plan, 0);
        assertTrue(end.onGround);
        assertTrue("the plan goes somewhere: " + end.z, Math.abs(end.z - start.z) > 2);
        // Halfway along, where the plan said the player would be.
        PlayerSim mid = fly(w, start, plan.subList(0, 6), 0);
        assertTrue(CorrectionPolicy.replayReaches(new PlayerSim(w), mid, plan, 6, end.x, end.y, end.z, 0.35));
    }

    @Test
    public void smallSideStepIsCorrected() {
        Blocks w = walkway();
        PlayerSim start = standingAt(w, 0.5, 0.5);
        List<PhysicsPathfinder.Action> plan = walkThenStop(12, false);
        PlayerSim end = fly(w, start, plan, 0);
        PlayerSim mid = fly(w, start, plan.subList(0, 6), 0);
        mid.x += 0.15; // pushed sideways: still on the walkway, still on course
        assertTrue(CorrectionPolicy.replayReaches(new PlayerSim(w), mid, plan, 6, end.x, end.y, end.z, 0.35));
    }

    @Test
    public void bigSetbackEscalates() {
        Blocks w = walkway();
        PlayerSim start = standingAt(w, 0.5, 0.5);
        List<PhysicsPathfinder.Action> plan = walkThenStop(12, false);
        PlayerSim end = fly(w, start, plan, 0);
        PlayerSim mid = fly(w, start, plan.subList(0, 6), 0);
        // Set back a block along the walk: the rest of the plan stops a block short.
        mid.z -= Math.signum(end.z - start.z);
        assertFalse(CorrectionPolicy.replayReaches(new PlayerSim(w), mid, plan, 6, end.x, end.y, end.z, 0.35));
    }

    @Test
    public void driftOffTheEdgeIsAFall() {
        Blocks w = walkway();
        PlayerSim start = standingAt(w, 0.5, 0.5);
        List<PhysicsPathfinder.Action> plan = walkThenStop(12, false);
        PlayerSim end = fly(w, start, plan, 0);
        PlayerSim off = new PlayerSim(w).copyFrom(start);
        off.x = 2.6; // past the walkway's side, over nothing
        PlayerSim scratch = new PlayerSim(w);
        assertFalse(CorrectionPolicy.replayReaches(scratch, off, plan, 0, end.x, end.y, end.z, 5));
        assertTrue("stopped at the floor, " + scratch.y, scratch.y < -CorrectionPolicy.FLOOR);
        assertTrue("and not much further", scratch.y > -CorrectionPolicy.FLOOR - 4);
    }

    @Test
    public void endingInTheAirDoesNotCount() {
        Blocks w = walkway();
        PlayerSim start = standingAt(w, 0.5, 0.5);
        List<PhysicsPathfinder.Action> grounded = walkThenStop(8, false);
        PlayerSim end = fly(w, start, grounded, 0);
        // A wide radius so only the footing decides.
        assertTrue(CorrectionPolicy.replayReaches(new PlayerSim(w), start, grounded, 0, end.x, end.y, end.z, 2));
        assertFalse(CorrectionPolicy.replayReaches(new PlayerSim(w), start, walkThenStop(8, true), 0, end.x, end.y, end.z, 2));
    }

    @Test
    public void replayLeavesTheObservedStateAlone() {
        Blocks w = walkway();
        PlayerSim start = standingAt(w, 0.5, 0.5);
        double x = start.x, y = start.y, z = start.z, vz = start.vz;
        List<PhysicsPathfinder.Action> plan = walkThenStop(12, false);
        PlayerSim end = fly(w, start, plan, 0);
        PlayerSim scratch = new PlayerSim(w);
        assertTrue(CorrectionPolicy.replayReaches(scratch, start, plan, 0, end.x, end.y, end.z, 0.35));
        assertEquals(x, start.x, 0);
        assertEquals(y, start.y, 0);
        assertEquals(z, start.z, 0);
        assertEquals(vz, start.vz, 0);
        assertEquals(end.z, scratch.z, 1e-9);
    }

    @Test
    public void searchedPlanReplaysFromAMidpoint() {
        Blocks w = walkway();
        PlayerSim start = standingAt(w, 0.5, 0.5);
        List<PhysicsPathfinder.Action> plan = new PhysicsPathfinder(w, 1500).plan(start, 0.5, 0, 6.5, 0.35);
        assertNotNull(plan);
        int k = plan.size() / 2;
        PlayerSim mid = fly(w, start, plan.subList(0, k), 0);
        assertTrue(CorrectionPolicy.replayReaches(new PlayerSim(w), mid, plan, k, 0.5, 0, 6.5, 0.35));
    }
}
