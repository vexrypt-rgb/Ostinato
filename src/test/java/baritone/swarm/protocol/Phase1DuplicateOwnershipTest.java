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
import baritone.swarm.protocol.Phase1Support.Pair;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Gate: duplicate active task ownership = 0. */
public class Phase1DuplicateOwnershipTest {

    @Test
    public void duplicateDeliveryExecutesAtMostOnce() {
        Pair pair = new Pair();
        UUID id = UUID.randomUUID();
        String body = Phase1Support.cmd(pair.clock.get(), id, "Alice", "Bob", "mine", "diamond_ore");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(1, pair.bobExec.count("mine"));
        assertEquals(1, pair.bob.startCount());
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        assertEquals(3, pair.acks.size());
        assertEquals(pair.acks.get(0), pair.acks.get(1));
        assertEquals(pair.acks.get(0), pair.acks.get(2));
    }

    @Test
    public void randomizedRedeliveryReturnsRecordedOutcome() {
        Pair pair = new Pair();
        UUID eta = UUID.randomUUID();
        UUID help = UUID.randomUUID();
        String etaBody = Phase1Support.cmd(pair.clock.get(), eta, "Alice", "Bob", "eta", "");
        String helpBody = Phase1Support.cmd(pair.clock.get(), help, "Alice", "Bob", "help", "path");
        List<String> burst = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            burst.add(i % 2 == 0 ? etaBody : helpBody);
        }
        Collections.shuffle(burst, new Random(42));
        for (String body : burst) {
            pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        }
        assertEquals(120, burst.size());
        assertTrue(burst.size() >= 100);
        assertEquals(1, pair.bobExec.count("eta"));
        assertEquals(1, pair.bobExec.count("help"));
        assertEquals(2, pair.bob.startCount());
        assertEquals(ExecutionState.SUCCEEDED, pair.bob.lookup(eta).execution);
        assertEquals(ExecutionState.SUCCEEDED, pair.bob.lookup(help).execution);
        String etaAck = null;
        String helpAck = null;
        int etaAcks = 0;
        int helpAcks = 0;
        for (String ack : pair.acks) {
            if (ack.contains(eta.toString())) {
                etaAcks++;
                if (etaAck == null) {
                    etaAck = ack;
                }
                assertEquals(etaAck, ack);
            }
            if (ack.contains(help.toString())) {
                helpAcks++;
                if (helpAck == null) {
                    helpAck = ack;
                }
                assertEquals(helpAck, ack);
            }
        }
        assertEquals(60, etaAcks);
        assertEquals(60, helpAcks);
    }

    @Test
    public void reorderedDuplicatesDoNotReexecute() throws Exception {
        Phase1Support.Signed world = new Phase1Support.Signed(new baritone.swarm.transport.InMemorySwarmBus());
        List<String> held = new ArrayList<>();
        world.bus.setInterceptor((from, to, line) -> {
            held.add(line);
            return List.of();
        });
        world.aliceP.submit("Bob", "eta");
        world.aliceP.submit("Bob", "help topic");
        assertTrue(held.size() >= 2);
        // Acks sent while these lines are opened must not mutate the list being walked.
        List<String> replay = new ArrayList<>(held);
        Collections.reverse(replay);
        for (String line : replay) {
            var message = world.bobEp.receiveLine(line);
            if (message != null) {
                world.bobP.receive("link", message.from(), message.type(), message.body());
            }
            assertNullOrReplay(world, line);
        }
        assertEquals(1, world.bobExec.count("eta"));
        assertEquals(1, world.bobExec.count("help"));
    }

    @Test
    public void secondCommandWhileOneIsActiveDoesNotStart() {
        Pair pair = new Pair();
        String sent = pair.alice.submit("Bob", "mine diamond_ore");
        UUID id = Phase1Support.idOf(sent);
        assertEquals(1, pair.bobExec.count("mine"));
        String other = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Alice", "Bob", "goto", "1 2 3");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, other);
        assertEquals(0, pair.bobExec.count("goto"));
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        assertNotNull(pair.bob.lookup(id));
    }

    @Test
    public void inFlightRecordIsNotEvictedByTtl() {
        Pair pair = new Pair();
        pair.bob.setLedgerTtlMs(5);
        String etaLine = pair.alice.submit("Bob", "eta");
        UUID eta = Phase1Support.idOf(etaLine);
        assertEquals(ExecutionState.SUCCEEDED, pair.bob.lookup(eta).execution);
        String sent = pair.alice.submit("Bob", "mine diamond_ore");
        UUID mine = Phase1Support.idOf(sent);
        pair.clock.addAndGet(50);
        pair.bob.pump();
        assertNotNull(pair.bob.lookup(mine));
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(mine).execution);
        assertEquals(null, pair.bob.lookup(eta));
    }

    private static void assertNullOrReplay(Phase1Support.Signed world, String line) {
        var again = world.bobEp.receiveLine(line);
        if (again != null) {
            world.bobP.receive("link", again.from(), again.type(), again.body());
        }
    }
}
