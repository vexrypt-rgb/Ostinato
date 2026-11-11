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

package baritone.swarm.protocol;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import baritone.swarm.protocol.Phase1Support.Pair;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Gate: every stop reaches canceled (confirmed), or stays running with observation unknown.
 */
public class Phase1StopScenariosTest {

    @Test
    public void stopConfirmedReachesCanceled() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        pair.bobExec.confirm = true;
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(cancel), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertEquals(1, pair.bobExec.confirmCalls);
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, pair.alice.lookupSent(cancel).body);
        assertEquals(1, pair.bobExec.confirmCalls);
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
    }

    @Test
    public void stopDuringDisconnectStaysRunningUnknown() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bob.onDisconnect();
        int confirms = pair.bobExec.confirmCalls;
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        assertEquals(confirms, pair.bobExec.confirmCalls);
        assertState(pair.bob.lookup(id), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertState(pair.bob.lookup(cancel), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertFalse(pair.bob.lookup(id).execution.terminal());
        assertFalse(pair.bob.lookup(cancel).execution.terminal());
    }

    @Test
    public void stopUnknownIdStaysRunningUnknown() {
        Pair pair = new Pair();
        UUID missing = UUID.randomUUID();
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + missing));
        assertNull(pair.bob.lookup(missing));
        assertEquals(0, pair.bobExec.confirmCalls);
        assertEquals(0, pair.bobExec.total());
        CommandProtocol.Tracked stop = pair.bob.lookup(cancel);
        assertState(stop, ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertEquals("unknown-command", stop.rejectReason);
        assertFalse(stop.execution == ExecutionState.CANCELED);
        assertFalse(stop.execution == ExecutionState.FAILED);
    }

    @Test
    public void stopRacingCompletionKeepsVerifiedTerminal() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bobExec.poll = ExecutionState.SUCCEEDED;
        pair.bob.pollExecutions();
        assertState(pair.bob.lookup(id), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        int confirms = pair.bobExec.confirmCalls;
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "stop " + id));
        assertEquals(confirms, pair.bobExec.confirmCalls);
        assertState(pair.bob.lookup(id), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        CommandProtocol.Tracked stop = pair.bob.lookup(cancel);
        assertState(stop, ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertEquals("already-finished", stop.rejectReason);
    }

    @Test
    public void completionRacingStopDoesNotOverwriteCancel() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bobExec.confirm = true;
        pair.alice.submit("Bob", "cancel " + id);
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        pair.bobExec.poll = ExecutionState.SUCCEEDED;
        pair.bob.pollExecutions();
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
    }

    @Test
    public void ownerStopOutranksLocalSurvival() {
        Pair pair = new Pair();
        pair.bob.setLocalSurvival(true);
        UUID blocked = UUID.randomUUID();
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD,
                Phase1Support.cmd(pair.clock.get(), blocked, "Alice", "Bob", "goto", "1 2 3"));
        assertEquals(0, pair.bobExec.count("goto"));
        assertEquals("outranked-by-local-survival", pair.bob.lookup(blocked).rejectReason);

        pair.bob.setLocalSurvival(false);
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bob.setLocalSurvival(true);
        pair.bobExec.confirm = true;
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(cancel), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertTrue(pair.bobExec.confirmCalls >= 1);
    }

    @Test
    public void immediateSafetyBlocksEvenOwnerStop() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bob.setImmediateSafety(true);
        UUID cancel = UUID.randomUUID();
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD,
                Phase1Support.cmd(pair.clock.get(), cancel, "Alice", "Bob", "pause", ""));
        assertEquals(0, pair.bobExec.count("pause"));
        assertEquals(0, pair.bobExec.confirmCalls);
        assertEquals("outranked-by-immediate-safety", pair.bob.lookup(cancel).rejectReason);
        assertState(pair.bob.lookup(id), ExecutionState.RUNNING, ObservationState.CONFIRMED);
        assertFalse(pair.bob.lookup(id).execution.terminal());
    }

    @Test
    public void issuerLocalSurvivalDoesNotBlockSendToPeer() {
        Pair pair = new Pair();
        pair.alice.setLocalSurvival(true);
        String line = pair.alice.submit("Bob", "goto 1 2 3");
        assertTrue("issuer survival blocked peer send: " + line, line.startsWith("swarm: sent "));
        assertEquals(1, pair.bobExec.count("goto"));
        pair.bob.setLocalSurvival(true);
        UUID blocked = java.util.UUID.randomUUID();
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD,
                Phase1Support.cmd(pair.clock.get(), blocked, "Alice", "Bob", "goto", "4 5 6"));
        assertEquals(1, pair.bobExec.count("goto"));
        assertEquals("outranked-by-local-survival", pair.bob.lookup(blocked).rejectReason);
    }

    @Test
    public void swarmMemberCanCancelOwnCommandWhenNotTheOwner() {
        // Ostinato: live bots have no ownerName, so each bot used to treat itself as owner
        // and reject a peer's cancel as unauthorized. Cancel is SWARM.
        Pair pair = new Pair("Carol");
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        pair.bobExec.confirm = true;
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(cancel), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
    }

    private static void assertState(CommandProtocol.Tracked tracked, ExecutionState execution, ObservationState observation) {
        assertNotNull(tracked);
        assertEquals(execution, tracked.execution);
        assertEquals(observation, tracked.observation);
        assertFalse("UNKNOWN".equals(tracked.execution.name()));
    }
}
