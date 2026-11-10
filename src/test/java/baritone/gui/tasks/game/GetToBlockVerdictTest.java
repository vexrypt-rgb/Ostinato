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

package baritone.gui.tasks.game;

import baritone.api.pathing.goals.Goal;
import baritone.api.process.IGetToBlockProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.process.ProcessOutcome;
import baritone.api.process.ProcessOutcome.Reason;
import baritone.api.process.ProcessOutcome.State;
import baritone.api.utils.BlockOptionalMeta;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato 2.0 Phase 09: the task runner's verified GET_TO_BLOCK step, judged only through {@link ProcessOutcome} and
 * a postcondition supplier, with no client. The messages are Phase 08's.
 */
public class GetToBlockVerdictTest {

    private static ProcessOutcome ended(long request, State s, Reason r) {
        ProcessOutcome o = ProcessOutcome.NONE;
        while (o.request() < request) {
            o = o.accept();
        }
        return o.running().end(s, r);
    }

    @Test
    public void passesOnlyOnOurSuccessWithThePostcondition() {
        assertNull(BaritoneTaskAdapter.getToBlockVerdict(3, ended(3, State.SUCCEEDED, Reason.NONE), () -> true, "chest"));
        assertEquals("reported reached, but chest is not open or next to the player",
                BaritoneTaskAdapter.getToBlockVerdict(3, ended(3, State.SUCCEEDED, Reason.NONE), () -> false, "chest"));
    }

    @Test
    public void aResultForAnotherRequestIsRejected() {
        // stale: another get to block replaced the step's request, even if that one succeeded and the menu is open
        assertEquals("replaced by another get to block",
                BaritoneTaskAdapter.getToBlockVerdict(3, ended(4, State.SUCCEEDED, Reason.NONE), () -> true, "chest"));
        assertEquals("replaced by another get to block",
                BaritoneTaskAdapter.getToBlockVerdict(3, ended(2, State.SUCCEEDED, Reason.NONE), () -> true, "chest"));
    }

    @Test
    public void failureCancelAndUnknownPropagateWithTheirReason() {
        assertEquals("get to block failed: timeout",
                BaritoneTaskAdapter.getToBlockVerdict(1, ended(1, State.FAILED, Reason.TIMEOUT), () -> true, "chest"));
        assertEquals("get to block failed: world changed",
                BaritoneTaskAdapter.getToBlockVerdict(1, ended(1, State.FAILED, Reason.WORLD_CHANGED), () -> true, "chest"));
        assertEquals("get to block failed: invalidated goal",
                BaritoneTaskAdapter.getToBlockVerdict(1, ended(1, State.FAILED, Reason.INVALIDATED_GOAL), () -> true, "chest"));
        assertEquals("get to block cancelled: ownership lost",
                BaritoneTaskAdapter.getToBlockVerdict(1, ended(1, State.CANCELLED, Reason.OWNERSHIP_LOST), () -> true, "chest"));
        // the click was out when control was lost: the server may still open it, so not a success
        assertEquals("get to block unknown: ownership lost",
                BaritoneTaskAdapter.getToBlockVerdict(1, ended(1, State.UNKNOWN, Reason.OWNERSHIP_LOST), () -> true, "chest"));
        ProcessOutcome running = ProcessOutcome.NONE.accept().running();
        assertEquals("get to block running: none", BaritoneTaskAdapter.getToBlockVerdict(1, running, () -> true, "chest"));
    }

    @Test
    public void aPostconditionCheckThatThrowsFails() {
        assertEquals("cannot check chest", BaritoneTaskAdapter.getToBlockVerdict(1, ended(1, State.SUCCEEDED, Reason.NONE), () -> {
            throw new IllegalArgumentException("bad block");
        }, "chest"));
    }

    @Test
    public void anImplementationThatCannotObserveNeverPasses() {
        // the contract's defaults: no menu identity, and the postcondition is never claimed
        IGetToBlockProcess bare = new IGetToBlockProcess() {
            @Override
            public void getToBlock(BlockOptionalMeta block) {
            }

            @Override
            public boolean blacklistClosest() {
                return false;
            }

            @Override
            public boolean isActive() {
                return false;
            }

            @Override
            public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
                return new PathingCommand((Goal) null, PathingCommandType.DEFER);
            }

            @Override
            public boolean isTemporary() {
                return false;
            }

            @Override
            public void onLostControl() {
            }

            @Override
            public String displayName0() {
                return "bare";
            }
        };
        assertNull(bare.openMenu());
        assertSame(ProcessOutcome.NONE, bare.outcome());
        assertFalse(bare.reached(null, null));
        assertEquals("reported reached, but chest is not open or next to the player",
                BaritoneTaskAdapter.getToBlockVerdict(1, ended(1, State.SUCCEEDED, Reason.NONE), () -> bare.reached(null, bare.openMenu()), "chest"));
    }
}
