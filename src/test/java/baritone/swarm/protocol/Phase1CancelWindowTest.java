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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: a cancel keeps re-checking confirmStopped once per tick for
 * {@link CommandProtocol#CANCEL_CONFIRM_WINDOW_TICKS}, then settles exactly once and frees the slot.
 */
public class Phase1CancelWindowTest {

    private static final int WINDOW = CommandProtocol.CANCEL_CONFIRM_WINDOW_TICKS;

    @Test
    public void stopsAfterSomeTicksSettlesCanceledOnce() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bobExec.pathingFor = 5; // first check plus four ticks still pathing
        int mark = pair.acks.size();
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));

        assertState(pair.bob.lookup(id), ExecutionState.RUNNING, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(cancel), ExecutionState.RUNNING, ObservationState.CONFIRMED);
        assertEquals("busy", reject(pair, "goto 9 9 9")); // the slot is held while the stop is open

        for (int i = 0; i < 4; i++) {
            pair.bob.pollExecutions();
        }
        assertState(pair.bob.lookup(id), ExecutionState.RUNNING, ObservationState.CONFIRMED);
        pair.bob.pollExecutions(); // fifth re-check confirms
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(cancel), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertEquals(6, pair.bobExec.confirmCalls);

        tick(pair, WINDOW * 2);
        assertEquals(6, pair.bobExec.confirmCalls); // window closed; no more checks
        assertOutcomes(pair, mark, id, ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertFinals(pair, mark, cancel, 1, ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertIssuer(pair, id, ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertIssuer(pair, cancel, ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertNewGotoAccepted(pair);
    }

    @Test
    public void neverStopsTimesOutRunningUnknown() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bobExec.confirm = false;
        int mark = pair.acks.size();
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));

        tick(pair, WINDOW - 1);
        assertState(pair.bob.lookup(id), ExecutionState.RUNNING, ObservationState.CONFIRMED);
        assertEquals(0, finals(pair, mark, id).size());
        pair.bob.pollExecutions(); // the window runs out on this tick
        assertState(pair.bob.lookup(id), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertState(pair.bob.lookup(cancel), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertEquals(1 + WINDOW, pair.bobExec.confirmCalls);

        tick(pair, WINDOW * 2);
        assertEquals(1 + WINDOW, pair.bobExec.confirmCalls);
        assertOutcomes(pair, mark, id, ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertFinals(pair, mark, cancel, 1, ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertIssuer(pair, id, ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertIssuer(pair, cancel, ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertNewGotoAccepted(pair);
    }

    @Test
    public void stopsImmediatelySettlesWithoutTicking() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        int mark = pair.acks.size();
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));

        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(cancel), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertEquals(1, pair.bobExec.confirmCalls);
        tick(pair, WINDOW * 2);
        assertEquals(1, pair.bobExec.confirmCalls);
        assertOutcomes(pair, mark, id, ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertFinals(pair, mark, cancel, 0, ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertIssuer(pair, id, ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertNewGotoAccepted(pair);
    }

    @Test
    public void secondCancelDuringWindowJoinsTheSameStop() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bobExec.pathingFor = 6;
        int mark = pair.acks.size();
        UUID first = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        tick(pair, 2);
        UUID second = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        assertState(pair.bob.lookup(second), ExecutionState.RUNNING, ObservationState.CONFIRMED);
        // Redelivery of the first cancel is a duplicate: no new check, no new outcome.
        int confirms = pair.bobExec.confirmCalls;
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, pair.alice.lookupSent(first).body);
        assertEquals(confirms, pair.bobExec.confirmCalls);

        tick(pair, WINDOW * 2);
        assertState(pair.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(first), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertState(pair.bob.lookup(second), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertEquals(7, pair.bobExec.confirmCalls); // one window: 6 misses then the confirm, never doubled
        assertOutcomes(pair, mark, id, ExecutionState.CANCELED, ObservationState.CONFIRMED);
        // interim + the duplicate's recorded-state reply (both non-final) + one final
        assertFinals(pair, mark, first, 2, ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertFinals(pair, mark, second, 1, ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        assertNewGotoAccepted(pair);
    }

    @Test
    public void linkDropDuringWindowEndsUnknownWithoutAcks() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bobExec.pathingFor = 1_000;
        int mark = pair.acks.size();
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        tick(pair, 3);
        pair.bob.onDisconnect();
        int confirms = pair.bobExec.confirmCalls;
        tick(pair, WINDOW * 2);
        assertEquals(confirms, pair.bobExec.confirmCalls);
        pair.bob.onReconnect();
        tick(pair, WINDOW * 2);
        assertEquals(confirms, pair.bobExec.confirmCalls); // the window is gone, not resumed

        assertState(pair.bob.lookup(id), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertState(pair.bob.lookup(cancel), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertEquals(0, finals(pair, mark, id).size());
        assertEquals(0, finals(pair, mark, cancel).size());
        pair.bobExec.pathingFor = 0;
        assertNewGotoAccepted(pair);
    }

    // --- helpers ---

    private static void tick(Pair pair, int n) {
        for (int i = 0; i < n; i++) {
            pair.alice.pollExecutions();
            pair.bob.pollExecutions();
        }
    }

    private static String reject(Pair pair, String line) {
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", line));
        CommandProtocol.Tracked t = pair.bob.lookup(id);
        assertNotNull(t);
        return t.rejectReason;
    }

    private static void assertNewGotoAccepted(Pair pair) {
        int before = pair.bobExec.count("goto");
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 4 5 6"));
        CommandProtocol.Tracked t = pair.bob.lookup(id);
        assertNotNull(t);
        assertNull(t.rejectReason);
        assertEquals(ExecutionState.RUNNING, t.execution);
        assertEquals(before + 1, pair.bobExec.count("goto"));
    }

    /** Acks for {@code id} sent after {@code mark} whose state is final: terminal, or observation unknown. */
    private static List<String[]> finals(Pair pair, int mark, UUID id) {
        List<String[]> out = new ArrayList<>();
        for (String body : pair.acks.subList(mark, pair.acks.size())) {
            String[] f = body.split("\\|", -1);
            if (f.length < 7 || !id.toString().equals(f[2])) {
                continue;
            }
            boolean terminal = !ExecutionState.RUNNING.label().equals(f[5]) && !ExecutionState.ACCEPTED.label().equals(f[5]);
            if (terminal || ObservationState.UNKNOWN.label().equals(f[6])) {
                out.add(f);
            }
        }
        return out;
    }

    private static void assertOutcomes(Pair pair, int mark, UUID target, ExecutionState exec, ObservationState obs) {
        List<String[]> got = finals(pair, mark, target);
        assertEquals("exactly one outcome for the stopped command", 1, got.size());
        assertEquals(exec.label(), got.get(0)[5]);
        assertEquals(obs.label(), got.get(0)[6]);
    }

    private static void assertFinals(Pair pair, int mark, UUID cancel, int interim, ExecutionState exec, ObservationState obs) {
        List<String[]> got = finals(pair, mark, cancel);
        assertEquals("exactly one final ack per cancel", 1, got.size());
        assertEquals(exec.label(), got.get(0)[5]);
        assertEquals(obs.label(), got.get(0)[6]);
        int all = 0;
        for (String body : pair.acks.subList(mark, pair.acks.size())) {
            if (body.contains(cancel.toString())) {
                all++;
            }
        }
        assertTrue("interim acks for " + cancel + ": " + all, all <= interim + 1);
    }

    private static void assertIssuer(Pair pair, UUID id, ExecutionState exec, ObservationState obs) {
        CommandProtocol.Sent sent = pair.alice.lookupSent(id);
        assertNotNull(sent);
        assertEquals(exec, sent.execution);
        assertEquals(obs, sent.observation);
    }

    private static void assertState(CommandProtocol.Tracked t, ExecutionState exec, ObservationState obs) {
        assertNotNull(t);
        assertEquals(exec, t.execution);
        assertEquals(obs, t.observation);
    }
}
