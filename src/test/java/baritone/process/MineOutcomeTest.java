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

package baritone.process;

import baritone.api.process.ProcessOutcome;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: a mine request succeeds only if it broke a target block (release gate, 17 Nov). */
public class MineOutcomeTest {

    @Test
    public void aMineWithNothingToMineFails() {
        MineOutcome m = new MineOutcome();
        m.start();
        m.running();
        m.endFor(ProcessOutcome.Reason.MISSING_RESOURCE); // "No locations for ... known"
        m.finish();
        assertEquals(ProcessOutcome.State.FAILED, m.outcome().state());
        assertEquals(ProcessOutcome.Reason.MISSING_RESOURCE, m.outcome().reason());
    }

    @Test
    public void aMineThatBrokeATargetSucceeds() {
        MineOutcome m = new MineOutcome();
        m.start();
        m.minedOne();
        m.endFor(ProcessOutcome.Reason.MISSING_RESOURCE); // ran out of ore afterwards
        m.finish();
        assertEquals(ProcessOutcome.State.SUCCEEDED, m.outcome().state());
    }

    @Test
    public void reachingTheQuantityWithoutMiningIsNotASuccess() {
        MineOutcome m = new MineOutcome();
        m.start();
        m.endFor(ProcessOutcome.Reason.NONE); // "Have N valid items" on the first tick
        m.finish();
        assertEquals(ProcessOutcome.State.FAILED, m.outcome().state());
        assertEquals(ProcessOutcome.Reason.MISSING_RESOURCE, m.outcome().reason());
    }

    @Test
    public void otherReasonsAreKept() {
        MineOutcome m = new MineOutcome();
        m.start();
        m.endFor(ProcessOutcome.Reason.UNREACHABLE);
        m.finish();
        assertEquals(ProcessOutcome.Reason.UNREACHABLE, m.outcome().reason());
    }

    @Test
    public void controlTakenAwayIsCancelledNotSucceeded() {
        MineOutcome m = new MineOutcome();
        m.start();
        m.minedOne();
        m.finish(); // no endFor: a stop or another process took control
        assertEquals(ProcessOutcome.State.CANCELLED, m.outcome().state());
    }

    @Test
    public void aNewRequestClosesThePreviousOneAndCountsAfresh() {
        MineOutcome m = new MineOutcome();
        m.start();
        m.minedOne();
        long first = m.outcome().request();
        m.start(); // replaced before it ended itself
        assertEquals(first + 1, m.outcome().request());
        assertEquals(0, m.mined());
        assertEquals(ProcessOutcome.State.ACCEPTED, m.outcome().state());
        m.endFor(ProcessOutcome.Reason.MISSING_RESOURCE);
        m.finish();
        assertEquals("the first request's block does not count for the second", ProcessOutcome.State.FAILED,
                m.outcome().state());
    }

    @Test
    public void finishingTwiceKeepsTheFirstResult() {
        MineOutcome m = new MineOutcome();
        m.start();
        m.minedOne();
        m.endFor(ProcessOutcome.Reason.NONE);
        m.finish();
        m.finish();
        assertEquals(ProcessOutcome.State.SUCCEEDED, m.outcome().state());
    }
}
