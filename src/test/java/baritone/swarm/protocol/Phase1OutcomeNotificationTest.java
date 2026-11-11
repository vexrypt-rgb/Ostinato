package baritone.swarm.protocol;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import baritone.swarm.protocol.Phase1Support.Pair;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Ostinato: terminal outcomes must reach the issuer without another command or retry.
public class Phase1OutcomeNotificationTest {
    @Test
    public void polledTerminalOutcomesReachBothSides() {
        for (ExecutionState result : new ExecutionState[] {
                ExecutionState.SUCCEEDED, ExecutionState.FAILED, ExecutionState.CANCELED}) {
            Pair pair = new Pair();
            UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
            pair.bobExec.poll = result;
            pair.bob.pollExecutions();
            assertEquals(result, pair.bob.lookup(id).execution);
            assertEquals(ObservationState.CONFIRMED, pair.bob.lookup(id).observation);
            assertSent(pair, id, result);
            assertTrue(pair.alice.outcomeLine(id.toString()).contains(result.label()));
            assertTrue(pair.alice.outcomeLine(id.toString()).contains("confirmed"));
            int acks = pair.acks.size();
            pair.bob.pollExecutions();
            assertEquals(acks, pair.acks.size());
        }
    }

    @Test
    public void confirmedCancelAcknowledgesOriginalAndCancel() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.bobExec.confirm = true;
        UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
        assertSent(pair, id, ExecutionState.CANCELED);
        assertSent(pair, cancel, ExecutionState.SUCCEEDED);
        assertEquals(ExecutionState.CANCELED, pair.bob.lookup(id).execution);
        assertEquals(ObservationState.CONFIRMED, pair.bob.lookup(id).observation);
        assertEquals(ExecutionState.SUCCEEDED, pair.bob.lookup(cancel).execution);
        assertEquals(ObservationState.CONFIRMED, pair.bob.lookup(cancel).observation);
        assertTrue(pair.alice.outcomeLine(id.toString()).contains("canceled"));
        assertTrue(pair.acks.stream().anyMatch(body -> body.startsWith("1|ack|" + id + "|")
                && body.contains("canceled (confirmed)|confirmed")));
    }

    @Test
    public void cancelAckAloneConfirmsOriginalForEveryStopAlias() {
        for (String name : new String[] {"cancel", "stop", "forcecancel"}) {
            Pair pair = new Pair();
            UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
            pair.bobExec.confirm = false;
            // Ostinato: deliver only the successful cancel ACK, as if the target ACK was dropped.
            UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", name + " " + id));
            assertEquals(ExecutionState.RUNNING, pair.alice.lookupSent(id).execution);
            ack(pair, cancel, ExecutionState.SUCCEEDED);
            assertSent(pair, id, ExecutionState.CANCELED);
            assertSent(pair, cancel, ExecutionState.SUCCEEDED);
        }
    }

    @Test
    public void delayedCancelAcksCannotRewriteConfirmedTerminals() {
        for (ExecutionState result : new ExecutionState[] {
                ExecutionState.SUCCEEDED, ExecutionState.FAILED, ExecutionState.CANCELED}) {
            Pair pair = new Pair();
            UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
            pair.bobExec.poll = result;
            pair.bob.pollExecutions();
            UUID cancel = Phase1Support.idOf(pair.alice.submit("Bob", "cancel " + id));
            ack(pair, cancel, ExecutionState.SUCCEEDED);
            ack(pair, id, ExecutionState.CANCELED);
            assertSent(pair, id, result);
        }
    }

    @Test
    public void submitReturnsSuccessWithoutLoggingItAgain() {
        Pair pair = new Pair();
        String line = pair.alice.submit("Bob", "goto 1 2 3");
        assertTrue(line.startsWith("swarm: sent "));
        assertFalse(pair.log.stream().anyMatch(message -> message.contains("swarm: sent")));
    }

    @Test
    public void plannedDisconnectIsSilentButPreservesObservationChanges() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "goto 1 2 3"));
        pair.log.clear();
        pair.bob.onDisconnect(false);
        pair.alice.onDisconnect(false);
        assertFalse(pair.bob.linkUp());
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        assertEquals(ObservationState.UNKNOWN, pair.bob.lookup(id).observation);
        assertEquals(ExecutionState.RUNNING, pair.alice.lookupSent(id).execution);
        assertEquals(ObservationState.UNKNOWN, pair.alice.lookupSent(id).observation);
        assertFalse(pair.log.stream().anyMatch(line -> line.contains("link down")));
        pair.bob.onDisconnect();
        pair.alice.onDisconnect(true);
        assertEquals(2L, pair.log.stream().filter(line -> line.contains("link down")).count());
    }

    private static void ack(Pair pair, UUID id, ExecutionState result) {
        pair.alice.receive("mem", "Bob", CommandProtocol.TYPE_ACK,
                "1|ack|" + id + "|accepted||" + result.label() + "|confirmed");
    }

    private static void assertSent(Pair pair, UUID id, ExecutionState result) {
        assertEquals(result, pair.alice.lookupSent(id).execution);
        assertEquals(ObservationState.CONFIRMED, pair.alice.lookupSent(id).observation);
    }
}
