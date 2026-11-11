package baritone.swarm.protocol; // Ostinato:

import baritone.api.swarm.protocol.ExecutionState; // Ostinato:
import baritone.api.swarm.protocol.ObservationState; // Ostinato:
import baritone.swarm.SwarmControl; // Ostinato:
import baritone.swarm.transport.InMemorySwarmBus; // Ostinato:
import org.junit.Test; // Ostinato:
import java.util.List; // Ostinato:
import java.util.UUID; // Ostinato:
import static org.junit.Assert.*; // Ostinato:

public class Phase1RegressionTest { // Ostinato:
    @Test public void signedCancelRetriesLostAckAndStopsExactlyOnce() throws Exception { // Ostinato:
        Phase1Support.Signed pair = new Phase1Support.Signed(new InMemorySwarmBus()); // Ostinato:
        UUID work = Phase1Support.idOf(pair.aliceP.submit("Bob", "goto 80 75 80")); // Ostinato:
        pair.tick(); pair.tick(); // Ostinato:
        assertEquals(ExecutionState.RUNNING, pair.aliceP.lookupSent(work).execution); // Ostinato:
        pair.bus.setInterceptor((from, to, line) -> "Bob".equals(from) ? List.of() : List.of(line)); // Ostinato:
        UUID cancel = Phase1Support.idOf(pair.aliceP.submit("Bob", "cancel " + work)); // Ostinato:
        assertEquals(1, pair.aliceP.lookupSent(cancel).sends); // Ostinato:
        pair.tick(); pair.tick(); // Ostinato:
        assertEquals(ExecutionState.CANCELED, pair.bobP.lookup(work).execution); // Ostinato:
        assertFalse(pair.aliceP.lookupSent(cancel).acked); // Ostinato:
        pair.bus.setInterceptor((from, to, line) -> List.of(line)); // Ostinato:
        pair.clock.addAndGet(4000); // Ostinato:
        pair.tick(); pair.tick(); // Ostinato:
        assertEquals(2, pair.aliceP.lookupSent(cancel).sends); // Ostinato:
        assertTrue(pair.aliceP.lookupSent(cancel).acked); // Ostinato:
        assertEquals(1, pair.bobExec.confirmCalls); // Ostinato:
        pair.clock.addAndGet(4000); pair.tick(); // Ostinato:
        assertEquals(2, pair.aliceP.lookupSent(cancel).sends); // Ostinato:
    } // Ostinato:

    @Test public void signedCancelStopsRetryingAtDeadline() throws Exception { // Ostinato:
        Phase1Support.Signed pair = new Phase1Support.Signed(new InMemorySwarmBus()); // Ostinato:
        pair.bus.setInterceptor((from, to, line) -> List.of()); // Ostinato:
        UUID cancel = Phase1Support.idOf(pair.aliceP.submit("Bob", "cancel " + UUID.randomUUID())); // Ostinato:
        assertEquals(1, pair.aliceP.lookupSent(cancel).sends); // Ostinato:
        pair.clock.addAndGet(4000); pair.tick(); // Ostinato:
        assertEquals(2, pair.aliceP.lookupSent(cancel).sends); // Ostinato:
        pair.clock.set(pair.aliceP.lookupSent(cancel).deadline); pair.tick(); // Ostinato:
        assertTrue(pair.aliceP.lookupSent(cancel).gaveUp); // Ostinato:
        assertEquals(2, pair.aliceP.lookupSent(cancel).sends); // Ostinato:
    } // Ostinato:

    @Test public void crashedPeerExpiresWithoutLocalDisconnectOrExecutionChange() throws Exception { // Ostinato:
        Phase1Support.Signed pair = new Phase1Support.Signed(new InMemorySwarmBus()); // Ostinato:
        UUID work = Phase1Support.idOf(pair.aliceP.submit("Bob", "goto 80 75 80")); // Ostinato:
        pair.tick(); pair.tick(); // Ostinato:
        assertEquals(ObservationState.CONFIRMED, pair.aliceP.lookupSent(work).observation); // Ostinato:
        pair.bobEp.close(); // Ostinato:
        pair.clock.addAndGet(SwarmControl.PEER_TIMEOUT_MS); // Ostinato:
        pair.aliceCtl.tick(); // Ostinato:
        assertTrue(pair.aliceP.linkUp()); // Ostinato:
        assertEquals(ExecutionState.RUNNING, pair.aliceP.lookupSent(work).execution); // Ostinato:
        assertEquals(ObservationState.UNKNOWN, pair.aliceP.lookupSent(work).observation); // Ostinato:
    } // Ostinato:

    @Test public void disconnectPreservesAcceptedAndTerminalExecution() { // Ostinato:
        Phase1Support.Pair pair = new Phase1Support.Pair(); // Ostinato:
        UUID work = Phase1Support.idOf(pair.alice.submit("Bob", "goto 80 75 80")); // Ostinato:
        pair.alice.lookupSent(work).execution = ExecutionState.ACCEPTED; // Ostinato:
        pair.alice.onPeerDisconnect("Bob"); // Ostinato:
        assertEquals(ExecutionState.ACCEPTED, pair.alice.lookupSent(work).execution); // Ostinato:
        assertEquals(ObservationState.UNKNOWN, pair.alice.lookupSent(work).observation); // Ostinato:
        pair.alice.onDisconnect(); // Ostinato:
        assertEquals(ExecutionState.ACCEPTED, pair.alice.lookupSent(work).execution); // Ostinato:
        Phase1Support.Pair done = new Phase1Support.Pair(); // Ostinato:
        UUID echo = Phase1Support.idOf(done.alice.submit("Bob", "echo hi")); // Ostinato:
        done.alice.onPeerDisconnect("Bob"); done.alice.onDisconnect(); // Ostinato:
        assertEquals(ExecutionState.SUCCEEDED, done.alice.lookupSent(echo).execution); // Ostinato:
        assertEquals(ObservationState.CONFIRMED, done.alice.lookupSent(echo).observation); // Ostinato:
    } // Ostinato:
} // Ostinato:
