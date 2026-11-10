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

import baritone.pathing.physics.UnifiedBatchBench.Trial;
import baritone.pathing.physics.UnifiedHarness.End;
import baritone.pathing.physics.UnifiedHarness.Result;
import baritone.pathing.unified.UnifiedExecutor.Counter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato 2.0 Phase 06A: the bounded retry after a correction-triggered replan comes back empty.
 * <p>
 * Runs {@link UnifiedHarness}, which mirrors the {@link PhysicsTravel} retry, on slip trials from the Phase 06 seed
 * (6061). Those trials were already seen in Phase 06; they are not gate evidence. Asserted here: the retry budget, at
 * most one retry per empty replan, a failed retry ends the run, the counters stay consistent, and legacy never retries.
 */
public class CorrectionRetryTest {

    /** The Phase 06 batch seed; slip is family 2. */
    private static final long SEED = 6061;
    private static final int SLIP = 2;

    private static List<Trial> trials(String family, int familyIndex, int n) {
        Random r = new Random(SEED + familyIndex);
        List<Trial> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(UnifiedBatchBench.generate(family, i, r));
        }
        return out;
    }

    /** R1 for one unified run, plus the search bound. */
    private static void assertBounded(Result u, String name) {
        long retries = u.count(Counter.CORRECTION_RETRIES);
        long retryPlans = u.count(Counter.CORRECTION_RETRY_PLANS);
        assertTrue(name + ": retries " + retries + " > escalations", retries <= u.count(Counter.ESCALATIONS));
        assertTrue(name + ": retry plans > retries", retryPlans <= retries);
        long failed = retries - retryPlans;
        assertTrue(name + ": " + failed + " failed retries", failed <= 1);
        if (failed == 1) {
            assertEquals(name + ": a failed retry must end the run", End.EMPTY_PLAN, u.end);
        }
        assertTrue(name + ": a retry popped " + u.retryExpansionsMax + " nodes", u.retryExpansionsMax <= CorrectionPolicy.RETRY_NODES);
        if (retries == 0) {
            assertEquals(name, -1, u.retryExpansionsMax);
            assertEquals(name, 0L, u.retryNanos);
        } else {
            assertTrue(name + ": a retry ran but popped nothing", u.retryExpansionsMax > 0);
            assertTrue(name, u.retryNanosMax > 0 && u.retryNanosMax <= u.retryNanos && u.retryNanos <= u.planNanos);
        }
    }

    private static void assertNoRetry(Result legacy, String name) {
        assertEquals(name, 0L, legacy.count(Counter.CORRECTION_RETRIES));
        assertEquals(name, 0L, legacy.count(Counter.CORRECTION_RETRY_PLANS));
        assertEquals(name, -1, legacy.retryExpansionsMax);
        assertEquals(name, 0L, legacy.retryNanos);
    }

    @Test
    public void retryBudgetIsDeeperThanTheReplan() {
        assertEquals(3000, CorrectionPolicy.RETRY_NODES);
        assertTrue(CorrectionPolicy.RETRY_NODES > UnifiedHarness.NODE_BUDGET);
    }

    /** Slip trial 19 ran out of nodes on a correction replan in Phase 06; the deeper search finds a plan from there. */
    @Test
    public void slipReplanThatRanOutRetriesOnceAndFindsAPlan() {
        Trial t = trials("slip", SLIP, 20).get(19);
        Result u = UnifiedHarness.run(t.scenario, true);
        assertTrue("retries " + u, u.count(Counter.CORRECTION_RETRIES) >= 1);
        assertTrue("retry plans " + u, u.count(Counter.CORRECTION_RETRY_PLANS) >= 1);
        assertBounded(u, "slip 19");
        // The retry is neither a plan nor a drift replan: H4 still pairs escalations with drift replans.
        assertEquals(u.count(Counter.ESCALATIONS), (long) u.driftReplans);

        Result legacy = UnifiedHarness.run(t.scenario, false);
        assertNoRetry(legacy, "slip 19 legacy");
    }

    /** Every slip run keeps R1; legacy never retries; and some run in the set does retry. */
    @Test
    public void everySlipRunKeepsTheRetryBounded() {
        int retried = 0;
        for (Trial t : trials("slip", SLIP, 60)) {
            String name = "slip " + t.index;
            Result u = UnifiedHarness.run(t.scenario, true);
            assertBounded(u, name);
            assertEquals(name, u.count(Counter.ESCALATIONS), (long) u.driftReplans);
            if (u.count(Counter.CORRECTION_RETRIES) > 0) {
                retried++;
            }
            assertNoRetry(UnifiedHarness.run(t.scenario, false), name + " legacy");
        }
        assertTrue("no slip run retried", retried > 0);
    }

    /** Nothing pushes the player on clean ground, so no correction escalates and nothing retries. */
    @Test
    public void cleanRunsNeverRetry() {
        for (Trial t : trials("clean", 0, 20)) {
            Result u = UnifiedHarness.run(t.scenario, true);
            assertEquals("clean " + t.index, 0L, u.count(Counter.CORRECTION_RETRIES));
            assertBounded(u, "clean " + t.index);
        }
    }
}
