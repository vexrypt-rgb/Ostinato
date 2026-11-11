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
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.protocol.Phase1Support.Signed;
import baritone.swarm.transport.InMemorySwarmBus;
import baritone.swarm.transport.LocalSpoolTransport;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Gate: injected disconnects end in a known safe state. A dropped mine is not a success,
 * and it is not mined again.
 */
public class Phase1DisconnectTest {

    @Test
    public void busDropDelayAndDuplicateReachKnownSafeStates() throws Exception {
        Signed dropped = new Signed(new InMemorySwarmBus());
        dropped.bus.setInterceptor((from, to, line) -> List.of());
        String sent = dropped.aliceP.submit("Bob", "mine diamond_ore");
        dropped.tick();
        assertEquals(0, dropped.bobExec.count("mine"));
        UUID id = Phase1Support.idOf(sent);
        dropped.clock.set(dropped.aliceP.lookupSent(id).deadline);
        dropped.tick();
        CommandProtocol.Sent gaveUp = dropped.aliceP.lookupSent(id);
        assertTrue(gaveUp.gaveUp);
        assertEquals(ExecutionState.RUNNING, gaveUp.execution);
        assertEquals(ObservationState.UNKNOWN, gaveUp.observation);
        assertFalse(gaveUp.execution == ExecutionState.SUCCEEDED);
        int sends = gaveUp.sends;
        String again = dropped.aliceP.submit("Bob", "mine iron_ore");
        assertTrue(again, again.contains("not retrying"));
        String named = dropped.aliceP.submit("Bob", "mine iron_ore", dropped.clock.get() + 60_000L, false, id);
        assertTrue(named, named.contains("not retrying"));
        assertEquals(sends, dropped.aliceP.lookupSent(id).sends);
        assertEquals(0, dropped.bobExec.count("mine"));
        assertEquals(id, dropped.aliceP.lookupSent(id).id);

        Signed delayed = new Signed(new InMemorySwarmBus());
        List<String> held = new ArrayList<>();
        delayed.bus.setInterceptor((from, to, line) -> {
            held.add(line);
            return List.of();
        });
        String waiting = delayed.aliceP.submit("Bob", "mine diamond_ore");
        delayed.tick();
        assertEquals(0, delayed.bobExec.count("mine"));
        assertFalse(held.isEmpty());
        delayed.bus.setInterceptor((from, to, line) -> List.of(line));
        for (String line : held) {
            var message = delayed.bobEp.receiveLine(line);
            if (message != null) {
                delayed.bobP.receive("link", message.from(), message.type(), message.body());
            }
        }
        UUID delayedId = Phase1Support.idOf(waiting);
        assertEquals(1, delayed.bobExec.count("mine"));
        assertEquals(ExecutionState.RUNNING, delayed.bobP.lookup(delayedId).execution);
        assertEquals(ObservationState.CONFIRMED, delayed.bobP.lookup(delayedId).observation);
        assertFalse(delayed.bobP.lookup(delayedId).execution.terminal());

        Signed dup = new Signed(new InMemorySwarmBus());
        dup.bus.setInterceptor((from, to, line) -> List.of(line, line));
        String mined = dup.aliceP.submit("Bob", "mine diamond_ore");
        dup.tick();
        assertEquals(1, dup.bobExec.count("mine"));
        assertTrue(dup.bobEp.rejectCount(SwarmReject.REPLAY) >= 1);
        CommandProtocol.Tracked once = dup.bobP.lookup(Phase1Support.idOf(mined));
        assertNotNull(once);
        assertEquals(ExecutionState.RUNNING, once.execution);
        assertFalse(once.execution == ExecutionState.SUCCEEDED);
    }

    @Test
    public void spoolDropDelayAndDuplicateReachKnownSafeStates() throws Exception {
        Path dropRoot = Files.createTempDirectory("swarm-p1-drop");
        Path delayRoot = Files.createTempDirectory("swarm-p1-delay");
        Path dupRoot = Files.createTempDirectory("swarm-p1-dup");
        try {
            Signed dropped = new Signed(dropRoot);
            dropped.aliceSpool.setFault((recipient, line) -> LocalSpoolTransport.Fault.Action.DROP);
            String sent = dropped.aliceP.submit("Bob", "mine diamond_ore");
            dropped.tick();
            assertEquals(0, dropped.bobExec.count("mine"));
            UUID id = Phase1Support.idOf(sent);
            dropped.clock.set(dropped.aliceP.lookupSent(id).deadline);
            dropped.tick();
            assertEquals(ExecutionState.RUNNING, dropped.aliceP.lookupSent(id).execution);
            assertEquals(ObservationState.UNKNOWN, dropped.aliceP.lookupSent(id).observation);
            assertTrue(dropped.aliceP.submit("Bob", "place dirt").contains("not retrying"));
            assertEquals(0, dropped.bobExec.total());

            Signed delayed = new Signed(delayRoot);
            delayed.aliceSpool.setFault((recipient, line) -> LocalSpoolTransport.Fault.Action.DELAY);
            delayed.aliceP.submit("Bob", "mine diamond_ore");
            delayed.tick();
            assertEquals(0, delayed.bobExec.count("mine"));
            // One command is several sealed lines. Releasing the delay must deliver all of them.
            assertTrue(delayed.aliceSpool.releaseDelayed() >= 1);
            delayed.tick();
            assertEquals(1, delayed.bobExec.count("mine"));
            assertEquals(ExecutionState.RUNNING, onlyMine(delayed).execution);
            assertEquals(ObservationState.CONFIRMED, onlyMine(delayed).observation);
            assertFalse(onlyMine(delayed).execution.terminal());

            Signed dup = new Signed(dupRoot);
            dup.aliceSpool.setFault((recipient, line) -> LocalSpoolTransport.Fault.Action.DUPLICATE);
            dup.aliceP.submit("Bob", "mine diamond_ore");
            dup.tick();
            assertEquals(1, dup.bobExec.count("mine"));
            assertTrue(dup.bobEp.rejectCount(SwarmReject.REPLAY) >= 1);
            assertEquals(ExecutionState.RUNNING, onlyMine(dup).execution);
            assertEquals(ObservationState.CONFIRMED, onlyMine(dup).observation);
        } finally {
            deleteTree(dropRoot);
            deleteTree(delayRoot);
            deleteTree(dupRoot);
        }
    }

    @Test
    public void mineNotRetriedWhenObservationUnknown() throws Exception {
        Signed world = new Signed(new InMemorySwarmBus());
        world.bus.setInterceptor((from, to, line) -> List.of());
        String sent = world.aliceP.submit("Bob", "mine diamond_ore");
        UUID id = Phase1Support.idOf(sent);
        world.clock.set(world.aliceP.lookupSent(id).deadline);
        world.aliceP.pump();
        assertEquals(ObservationState.UNKNOWN, world.aliceP.lookupSent(id).observation);
        assertFalse(world.aliceP.mayRetry(id, "mine"));
        assertFalse(world.aliceP.mayRetry(id, "place"));
        assertTrue(world.aliceP.mayRetry(id, "goto"));
        String fresh = world.aliceP.submit("Bob", "place stone");
        assertTrue(fresh, fresh.contains("not retrying"));
        assertFalse(fresh.contains("swarm: sent"));
        assertEquals(0, world.bobExec.count("mine"));
        assertEquals(0, world.bobExec.count("place"));
        assertEquals(1, world.aliceP.lookupSent(id).sends);
    }

    @Test
    public void stopDuringDisconnectOverADroppedFrameStaysUnknown() throws Exception {
        Signed world = new Signed(new InMemorySwarmBus());
        String sent = world.aliceP.submit("Bob", "goto 1 2 3");
        world.tick();
        UUID id = Phase1Support.idOf(sent);
        assertEquals(1, world.bobExec.count("goto"));
        assertEquals(ExecutionState.RUNNING, world.bobP.lookup(id).execution);
        world.bus.setInterceptor((from, to, line) -> List.of());
        world.bobP.onDisconnect();
        world.aliceP.onDisconnect();
        int confirms = world.bobExec.confirmCalls;
        String cancel = world.aliceP.submit("Bob", "cancel " + id);
        world.tick();
        assertEquals(confirms, world.bobExec.confirmCalls);
        assertEquals(ExecutionState.RUNNING, world.bobP.lookup(id).execution);
        assertEquals(ObservationState.UNKNOWN, world.bobP.lookup(id).observation);
        assertFalse(world.bobP.lookup(id).execution.terminal());
        UUID cancelId = Phase1Support.idOf(cancel);
        world.clock.set(world.aliceP.lookupSent(cancelId).deadline);
        world.aliceP.pump();
        assertEquals(ExecutionState.RUNNING, world.aliceP.lookupSent(cancelId).execution);
        assertEquals(ObservationState.UNKNOWN, world.aliceP.lookupSent(cancelId).observation);
        assertTrue(world.aliceP.lookupSent(cancelId).gaveUp);
        assertNullTrackedNotCanceled(world, id);
    }

    private static void assertNullTrackedNotCanceled(Signed world, UUID id) {
        CommandProtocol.Tracked tracked = world.bobP.lookup(id);
        assertNotNull(tracked);
        assertFalse(tracked.execution == ExecutionState.CANCELED);
        assertFalse(tracked.execution == ExecutionState.SUCCEEDED);
    }

    private static CommandProtocol.Tracked onlyMine(Signed world) {
        for (String line : world.bobP.statusLines()) {
            if (line.contains(" mine ")) {
                UUID id = UUID.fromString(line.trim().split(" ")[0]);
                return world.bobP.lookup(id);
            }
        }
        throw new AssertionError(world.bobP.statusLines().toString());
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
