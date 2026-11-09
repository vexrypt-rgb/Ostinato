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

import baritone.api.utils.input.Input;
import baritone.pathing.physics.UnifiedHarness.Blocks;
import baritone.pathing.physics.UnifiedHarness.End;
import baritone.pathing.physics.UnifiedHarness.Result;
import baritone.pathing.physics.UnifiedHarness.Scenario;
import baritone.pathing.unified.UnifiedExecutor.Counter;
import baritone.pathing.unified.UnifiedExecutor.Outcome;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato 2.0 Phase 06, Tier S: the physics driver's tick with and without the unified executor, on block worlds.
 * <p>
 * Each scenario runs both arms through {@link UnifiedHarness}. The asserts are the executor's promises (it arrives
 * where legacy arrives, keeps its keys, stops only on the ground, leaves no key down), plus tick-for-tick equality
 * where nothing pushes the player. Elsewhere how the arms compare is printed, not asserted. The harness copies the
 * driver's tick; it is not {@link PhysicsTravel}. These say nothing about the game: see the live A/B.
 */
public class UnifiedHeadlessTest {

    private static Blocks walkWorld() {
        return new Blocks().ground(-1, 1, -1, 8);
    }

    private static Blocks longWorld() {
        return new Blocks().ground(-1, 1, -1, 20);
    }

    private static Blocks gapWorld() {
        Blocks w = new Blocks();
        for (int z = -2; z <= 2; z++) {
            w.add(0, -1, z);
        }
        for (int z = 5; z <= 8; z++) {
            w.add(0, -1, z);
        }
        return w;
    }

    private static Blocks cornerWorld() {
        Blocks w = new Blocks();
        for (int z = 0; z <= 3; z++) {
            w.add(0, -1, z);
        }
        for (int x = 0; x <= 4; x++) {
            w.add(x, -1, 3);
        }
        return w;
    }

    private static Result[] both(Scenario s) {
        Result legacy = UnifiedHarness.run(s, false);
        Result unified = UnifiedHarness.run(s, true);
        System.out.println("UNIFIED-HEADLESS " + legacy);
        System.out.println("UNIFIED-HEADLESS " + unified);
        System.out.println("UNIFIED-HEADLESS " + s.name + " tickNanos p50/p95/p99 legacy="
                + UnifiedHarness.quantile(legacy.tickNanos, 0.5) + "/" + UnifiedHarness.quantile(legacy.tickNanos, 0.95) + "/"
                + UnifiedHarness.quantile(legacy.tickNanos, 0.99) + " unified=" + UnifiedHarness.quantile(unified.tickNanos, 0.5)
                + "/" + UnifiedHarness.quantile(unified.tickNanos, 0.95) + "/" + UnifiedHarness.quantile(unified.tickNanos, 0.99));
        return new Result[]{legacy, unified};
    }

    /** What the executor promises however the run ended. */
    private static void clean(Result u) {
        assertTrue(u.toString(), u.unified);
        assertEquals("the driver's keys were never lifted by someone else: " + u, 0, u.count(Counter.RELEASED_EARLY));
        assertEquals("no other key or analog vector rode on an owned tick: " + u, 0, u.count(Counter.OVERWRITTEN));
        assertTrue("no key is left down: " + u, u.keys.down.isEmpty());
        assertFalse("the lease is gone: " + u, u.ux.leased());
        assertEquals("no stale write was let through: " + u, 0, u.count(Counter.STALE_REJECTS));
        switch (u.end) {
            case ARRIVED:
                assertEquals(u.toString(), Outcome.COMPLETED_VERIFIED, u.ux.state());
                break;
            case CANCEL_STOP:
                assertEquals(u.toString(), Outcome.CANCELLED_CONFIRMED, u.ux.state());
                break;
            case EMPTY_PLAN:
                assertEquals(u.toString(), Outcome.FAILED, u.ux.state());
                break;
            default:
                throw new AssertionError("the unified arm ended " + u.end + ": " + u);
        }
    }

    private static void arrivedBoth(Result[] r) {
        assertEquals(r[0].toString(), End.ARRIVED, r[0].end);
        assertEquals(r[1].toString(), End.ARRIVED, r[1].end);
        clean(r[1]);
        assertEquals("one lease, held to the end: " + r[1], 1, r[1].count(Counter.ACQUIRES));
        assertEquals(r[1].toString(), 1, r[1].count(Counter.COMPLETIONS));
    }

    @Test
    public void walk() {
        Result[] r = both(new Scenario("walk", walkWorld(), 0.5, 0.5, 0.5, 5.5));
        arrivedBoth(r);
        assertEquals("nothing pushed it off the plan: " + r[1], 0, r[1].count(Counter.DIVERGENCES));
        assertEquals("same keys, same ticks, when nothing goes wrong", r[0].ticks, r[1].ticks);
        assertEquals(r[0].plans, r[1].plans);
    }

    @Test
    public void turn() {
        Result[] r = both(new Scenario("turn", cornerWorld(), 0.5, 0.5, 3.5, 3.5));
        arrivedBoth(r);
        assertEquals(r[0].ticks, r[1].ticks);
    }

    @Test
    public void sprint() {
        Result[] r = both(new Scenario("sprint", longWorld(), 0.5, 0.5, 0.5, 16.5));
        arrivedBoth(r);
        assertTrue("a long straight is sprinted: " + r[1], r[1].sprints > 0);
        assertEquals(r[0].ticks, r[1].ticks);
    }

    @Test
    public void jump() {
        Result[] r = both(new Scenario("jump", gapWorld(), 0.5, 0.5, 0.5, 6.5));
        arrivedBoth(r);
        assertTrue("the gap is jumped: " + r[1], r[1].jumps > 0);
        assertEquals(r[0].ticks, r[1].ticks);
    }

    @Test
    public void collision() {
        // A wall across the left of the walk, and a shove into it.
        Blocks w = new Blocks().ground(-2, 2, -1, 8);
        for (int x = -2; x <= 0; x++) {
            w.add(x, 0, 3).add(x, 1, 3);
        }
        Result[] r = both(new Scenario("collision", w, 0.5, 0.5, 0.5, 6.5).kickAtZ(2.4, -0.5, 0));
        arrivedBoth(r);
        assertTrue("the shove put it against the wall: " + r[1], r[1].collidedTicks > 0 && r[0].collidedTicks > 0);
        assertTrue("and that was seen: " + r[1], r[1].count(Counter.DIVERGENCES) >= 1);
    }

    @Test
    public void velocityChange() {
        Blocks w = new Blocks().ground(-3, 3, -1, 12);
        Result[] r = both(new Scenario("velocity", w, 0.5, 0.5, 0.5, 8.5).kick(5, 0.3, 0));
        arrivedBoth(r);
        assertTrue("the shove is seen: " + r[1], r[1].count(Counter.DIVERGENCES) >= 1);
        assertTrue("and the one correction is tried: " + r[1], r[1].count(Counter.CORRECTIONS) >= 1);
        assertTrue("legacy replans on it: " + r[0], r[0].driftReplans >= 1);
    }

    @Test
    public void nudge() {
        // A small shove back along the walk, the kind one correction may absorb.
        Blocks w = new Blocks().ground(-3, 3, -1, 12);
        Result[] r = both(new Scenario("nudge", w, 0.5, 0.5, 0.5, 8.5).kick(4, 0, -0.15));
        arrivedBoth(r);
        assertTrue(r[1].toString(), r[1].count(Counter.DIVERGENCES) >= 1);
        assertTrue("at most one correction a plan: " + r[1], r[1].count(Counter.CORRECTIONS) <= r[1].plans);
        // Kept or escalated, the correction must do no worse than replanning at once.
        assertTrue("no more plans than legacy: " + r[1] + " vs " + r[0], r[1].plans <= r[0].plans);
        assertTrue("no later than legacy: " + r[1] + " vs " + r[0], r[1].ticks <= r[0].ticks);
    }

    @Test
    public void slipperyGround() {
        // The planner thinks 0.6; the ground is slicker. Every tick drifts a little.
        Scenario s = new Scenario("slippery", new Blocks().ground(-3, 3, -1, 12), 0.5, 0.5, 0.5, 8.5).truthSlip(0.8f);
        s.maxTicks = 400;
        Result[] r = both(s);
        clean(r[1]);
        assertTrue("the drift is seen: " + r[1], r[1].count(Counter.DIVERGENCES) >= 1);
        assertTrue("at most one correction a plan: " + r[1], r[1].count(Counter.CORRECTIONS) <= r[1].plans);
        if (r[0].end == End.ARRIVED) {
            assertEquals("unified arrives where legacy does: " + r[1], End.ARRIVED, r[1].end);
        }
    }

    @Test
    public void cancel() {
        Result[] r = both(new Scenario("cancel", longWorld(), 0.5, 0.5, 0.5, 16.5).cancelAt(8));
        Result legacy = r[0], unified = r[1];
        assertEquals(unified.toString(), End.CANCEL_STOP, unified.end);
        clean(unified);
        assertTrue("stopped standing: " + unified, unified.onGroundAtEnd);
        assertTrue("no sooner than the second refusal: " + unified, unified.cancelLatency >= 1);
        assertTrue("and not long after: " + unified, unified.cancelLatency <= 15);
        assertEquals(unified.toString(), 1, unified.count(Counter.CANCEL_STOPS));
        assertTrue("well short of the goal: " + unified, unified.endZ < 14);
        assertEquals("legacy is carried to the goal: " + legacy, End.ARRIVED, legacy.end);
        assertTrue("so its cancel waits longer: " + legacy + " vs " + unified, legacy.cancelLatency > unified.cancelLatency);
    }

    @Test
    public void cancelMidJump() {
        // Asked to stop in the air over the gap: the stop waits for the landing.
        Result[] r = both(new Scenario("cancelMidJump", gapWorld(), 0.5, 0.5, 0.5, 6.5).cancelAfterJump());
        Result unified = r[1];
        clean(unified);
        assertTrue("stopped standing: " + unified, unified.onGroundAtEnd);
        assertTrue("not in the air: " + unified, unified.cancelLatency >= 2);
        assertTrue(unified.toString(), unified.endY > -0.5);
    }

    @Test
    public void conflictingInputs() {
        Scenario s = new Scenario("conflict", walkWorld(), 0.5, 0.5, 0.5, 5.5).presses(Input.MOVE_LEFT, Input.MOVE_BACK, 3, 12);
        s.pressEvery = 2;
        Result[] r = both(s);
        Result legacy = r[0], unified = r[1];
        arrivedBoth(r);
        assertEquals("no press after the write reached the keys: " + unified, 0, unified.conflictLanded);
        assertTrue("each was refused: " + unified, unified.count(Counter.CONFLICTS) >= 5);
        assertTrue("a press ahead of the write lands, and the write lifts it: " + unified,
                unified.preLanded > 0 && unified.count(Counter.ERASED) > 0);
        assertTrue("on legacy the back key lands: " + legacy, legacy.conflictLanded > 0);
        assertEquals("with the gate, the keys are the plan's: same ticks as the clean walk",
                UnifiedHarness.run(new Scenario("walk", walkWorld(), 0.5, 0.5, 0.5, 5.5), true).ticks, unified.ticks);
    }

    @Test
    public void releaseAfterFailure() {
        // A one-wide walkway over nothing, and a shove off it: no plan from there.
        Blocks w = new Blocks();
        for (int z = -1; z <= 10; z++) {
            w.add(0, -1, z);
        }
        Result[] r = both(new Scenario("fail", w, 0.5, 0.5, 0.5, 8.5).kick(5, 1.0, 0));
        Result legacy = r[0], unified = r[1];
        assertEquals(unified.toString(), End.EMPTY_PLAN, unified.end);
        clean(unified);
        assertEquals(unified.toString(), 1, unified.count(Counter.FAILURES));
        assertEquals("legacy ends the same way: " + legacy, End.EMPTY_PLAN, legacy.end);
        // Legacy's drop leaves the last frame's keys for the next layer to overwrite; reported, not asserted.
        System.out.println("UNIFIED-HEADLESS fail legacy keys left down: " + legacy.keys.down);
    }
}
