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

import baritone.api.command.CommandPermission;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ISwarmStatusView;
import baritone.api.swarm.protocol.ObservationState;
import baritone.swarm.protocol.Phase1Support.Pair;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Gate: illegal transitions are rejected, and unknown is never an execution state. */
public class Phase1StateModelTest {

    @Test
    public void illegalTransitionsRejected() {
        try {
            ExecutionState.transit(null, ExecutionState.RUNNING);
            fail("null -> running");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("illegal"));
        }
        try {
            ExecutionState.transit(ExecutionState.SUCCEEDED, ExecutionState.CANCELED);
            fail("succeeded -> canceled");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("illegal"));
        }
        try {
            ExecutionState.transit(ExecutionState.ACCEPTED, ExecutionState.SUCCEEDED);
            fail("accepted -> succeeded");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("illegal"));
        }
        try {
            ExecutionState.transit(ExecutionState.RUNNING, null);
            fail("null destination");
        } catch (IllegalStateException expected) {
            // refused
        }
        assertEquals(ExecutionState.ACCEPTED, ExecutionState.transit(null, ExecutionState.ACCEPTED));
        assertEquals(ExecutionState.RUNNING, ExecutionState.transit(ExecutionState.ACCEPTED, ExecutionState.RUNNING));
        assertEquals(ExecutionState.RUNNING, ExecutionState.transit(ExecutionState.RUNNING, ExecutionState.RUNNING));
    }

    @Test
    public void unknownIsNeverAnExecutionState() {
        try {
            ExecutionState.parse("unknown");
            fail("unknown parsed as an execution state");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknown"));
        }
        assertEquals(ObservationState.UNKNOWN, ObservationState.parse("unknown"));
        for (ExecutionState state : ExecutionState.values()) {
            assertFalse("unknown".equals(state.label()));
            assertFalse("UNKNOWN".equals(state.name()));
        }
    }

    @Test
    public void disconnectIsRunningPlusUnknownNotAnExecutionResult() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bob.onDisconnect();
        CommandProtocol.Tracked tracked = pair.bob.lookup(id);
        assertEquals(ExecutionState.RUNNING, tracked.execution);
        assertEquals(ObservationState.UNKNOWN, tracked.observation);
        assertFalse(tracked.execution.terminal());
        assertFalse("unknown".equals(tracked.execution.label()));
    }

    @Test
    public void ambiguousCompletionIsNotSucceeded() {
        Pair pair = new Pair();
        pair.bobExec.extra.put("chain", CommandCatalog.at("chain"));
        UUID id = UUID.randomUUID();
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD,
                Phase1Support.cmd(pair.clock.get(), id, "Alice", "Bob", "chain", ""));
        assertEquals(1, pair.bobExec.count("chain"));
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        assertEquals(ObservationState.UNKNOWN, pair.bob.lookup(id).observation);
        assertTrue(pair.bob.lookup(id).ambiguous);
        pair.bobExec.poll = ExecutionState.SUCCEEDED;
        pair.bob.pollExecutions();
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        assertEquals(ObservationState.UNKNOWN, pair.bob.lookup(id).observation);
        assertFalse(pair.bob.lookup(id).execution == ExecutionState.SUCCEEDED);
    }

    @Test
    public void atCatalogTextRoundTripsAsAmbiguousNavigation() {
        String text = """
                ---
                v1
                name=goto
                help=Go to a place
                perm=swarm
                caps=navigation
                lease=false
                mutate=false
                ambiguous=true
                origin=at
                arg=target:String:required
                """;
        CommandCatalog.Entry entry = CommandCatalog.parse(text).get(0);
        assertEquals("goto", entry.name);
        assertEquals(CommandPermission.SWARM, entry.permission);
        assertTrue(entry.requires.contains("navigation"));
        assertTrue(entry.ambiguous);
        assertTrue(entry.argsRequired);
        assertFalse(entry.leaseNeeding);
        assertFalse(entry.worldMutation);
        assertFalse(entry.ownerEmergency);
    }

    @Test
    public void closingStatusViewDoesNotChangeBots() {
        Pair pair = new Pair();
        ISwarmStatusView view = pair.bob.openStatusView();
        assertTrue(view.isOpen());
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        int starts = pair.bob.startCount();
        ExecutionState execution = pair.bob.lookup(id).execution;
        ObservationState observation = pair.bob.lookup(id).observation;
        assertFalse(view.lines().isEmpty());
        view.close();
        assertFalse(view.isOpen());
        assertTrue(view.lines().isEmpty());
        assertEquals(0, pair.bob.openViewCount());
        assertEquals(starts, pair.bob.startCount());
        assertEquals(execution, pair.bob.lookup(id).execution);
        assertEquals(observation, pair.bob.lookup(id).observation);
        assertEquals(1, pair.bobExec.count("goto"));
        boolean mentioned = false;
        for (String line : pair.bob.statusLines()) {
            if (line.contains(id.toString())) {
                mentioned = true;
            }
        }
        assertTrue(mentioned);
    }
}
