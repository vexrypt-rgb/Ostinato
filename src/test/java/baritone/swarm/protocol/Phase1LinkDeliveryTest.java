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

import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.swarm.transport.SwarmPriority;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

// Ostinato: recording wires deliberately avoid Pair's pre-learned capabilities and synchronous delivery.
public class Phase1LinkDeliveryTest {
    private static final class Frame {
        final String type;
        final String body;
        final SwarmPriority priority;

        Frame(String type, String body, SwarmPriority priority) {
            this.type = type;
            this.body = body;
            this.priority = priority;
        }
    }

    private static final class Wire implements CommandProtocol.Wire {
        final List<Frame> frames = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean send(String to, String type, String body) {
            return send(to, type, body, SwarmPriority.NORMAL);
        }

        @Override
        public boolean send(String to, String type, String body, SwarmPriority priority) {
            frames.add(new Frame(type, body, priority));
            return accept;
        }
    }

    private static CommandProtocol node(String name, Wire wire, AtomicLong clock) {
        CommandProtocol p = new CommandProtocol(new CommandProtocol.BotIdentity(name, name + "key"),
                Set.of("Alice", "Bob"), wire, new Phase1Support.RecordingExec(),
                new CommandProtocol.Policy("Alice", true, false, null), clock::get, null, 1000L);
        p.pinSignet("Alice", "Alicekey");
        p.pinSignet("Bob", "Bobkey");
        return p;
    }

    @Test
    public void lateJoinLearnsEarlierBotAndIdenticalHellosStop() {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        Wire aw = new Wire();
        CommandProtocol alice = node("Alice", aw, clock);
        alice.advertise(); // Bob is not online to receive this.
        Wire bw = new Wire();
        CommandProtocol bob = node("Bob", bw, clock);
        assertNull(bob.peerCapabilities("Alice"));
        bob.advertise();
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO, bw.frames.get(0).body);
        assertEquals(2, aw.frames.size());
        assertEquals(CommandProtocol.TYPE_HELLO, aw.frames.get(1).type);
        bob.receive("test", "Alice", CommandProtocol.TYPE_HELLO, aw.frames.get(1).body);
        assertEquals(CapabilityDescriptor.gameplay().kinds(), bob.peerCapabilities("Alice").kinds());
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO, bw.frames.get(1).body);
        assertEquals(2, aw.frames.size());
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO,
                "1|hello|Bob|Bobkey|v1:inventory,combat,building,mining,navigation");
        assertEquals(2, aw.frames.size()); // Same understood set, different wire ordering.
        bob.setLocalCapabilities(CapabilityDescriptor.of("navigation"));
        bob.advertise();
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO, bw.frames.get(2).body);
        assertEquals(3, aw.frames.size());
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO, "1|hello|Bob|Bobkey|v99:navigation");
        assertEquals(3, aw.frames.size());
    }

    // Ostinato: live 2026-11-12 - a relaunched coordinator never learned caps ("bot capabilities are unknown").
    @Test
    public void relaunchedPeerLearnsCapsWhenOurJoinHelloArrivedTooEarly() {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        Wire aw = new Wire();
        CommandProtocol alice = node("Alice", aw, clock);
        alice.learnPeer("Bob", "Bobkey", CapabilityDescriptor.gameplay());
        alice.noteOnlineMembers(Set.of("Alice", "Bob"));
        int sent = aw.frames.size();
        alice.noteOnlineMembers(Set.of("Alice")); // Bob's client dies.
        assertNull(alice.peerCapabilities("Bob"));
        alice.noteOnlineMembers(Set.of("Alice", "Bob")); // Bob rejoins; this hello is lost (no protocol yet).
        assertEquals(sent + 1, aw.frames.size());
        Wire bw = new Wire();
        CommandProtocol bob = node("Bob", bw, clock);
        bob.advertise(); // Bob's protocol comes up and says hello once.
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO, bw.frames.get(0).body);
        assertEquals(sent + 2, aw.frames.size());
        assertEquals(CommandProtocol.TYPE_HELLO, aw.frames.get(sent + 1).type);
        bob.receive("test", "Alice", CommandProtocol.TYPE_HELLO, aw.frames.get(sent + 1).body);
        assertEquals(CapabilityDescriptor.gameplay().kinds(), bob.peerCapabilities("Alice").kinds());
    }

    // Ostinato: 2026-11-12 follow-up - a relaunched coordinator asked nobody, and a peer that still held its caps
    // ignored the identical hello, so caps stayed unknown and every lease was refused for minutes.
    @Test
    public void relaunchedCoordinatorQueryIsAnsweredByPeerThatAlreadyKnowsIt() {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        Wire bw = new Wire();
        CommandProtocol bob = node("Bob", bw, clock);
        bob.learnPeer("Alice", "Alicekey", CapabilityDescriptor.gameplay()); // Bob kept Alice's caps across her relaunch.
        Wire aw = new Wire();
        CommandProtocol alice = node("Alice", aw, clock); // Relaunched coordinator: knows nobody.
        alice.noteOnlineMembers(Set.of("Alice", "Bob"));
        assertEquals(1, aw.frames.size());
        assertTrue(aw.frames.get(0).body.startsWith("1|helloq|Alice|"));
        bob.receive("test", "Alice", CommandProtocol.TYPE_HELLO, aw.frames.get(0).body);
        assertEquals(1, bw.frames.size());
        assertTrue(bw.frames.get(0).body.startsWith("1|hello|Bob|")); // A reply is never a query.
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO, bw.frames.get(0).body);
        assertEquals(CapabilityDescriptor.gameplay().kinds(), alice.peerCapabilities("Bob").kinds());
        int sent = aw.frames.size();
        assertTrue(aw.frames.get(sent - 1).body.startsWith("1|hello|Alice|")); // Nobody unknown: plain hello.
        bob.receive("test", "Alice", CommandProtocol.TYPE_HELLO, aw.frames.get(sent - 1).body);
        assertEquals(1, bw.frames.size()); // Identical plain hello: no reply, no loop.
    }

    // Ostinato: 2026-11-12 follow-up - a lost hello or reply was never retried while caps stayed unknown.
    @Test
    public void unknownPeerCapsAreReaskedWithBackoffUntilLearned() {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        Wire aw = new Wire();
        CommandProtocol alice = node("Alice", aw, clock);
        alice.noteOnlineMembers(Set.of("Alice", "Bob")); // Query at T0; Bob's reply is lost.
        assertEquals(1, aw.frames.size());
        clock.set(Phase1Support.T0 + CommandProtocol.CAPS_PROBE_MIN_MS - 1);
        alice.pump();
        assertEquals(1, aw.frames.size());
        clock.set(Phase1Support.T0 + CommandProtocol.CAPS_PROBE_MIN_MS);
        alice.pump();
        assertEquals(2, aw.frames.size()); // Re-asked 5 s after the relaunch.
        assertTrue(aw.frames.get(1).body.startsWith("1|helloq|Alice|"));
        clock.set(Phase1Support.T0 + 3 * CommandProtocol.CAPS_PROBE_MIN_MS - 1);
        alice.pump();
        assertEquals(2, aw.frames.size()); // Backoff: next ask 10 s later, not 5.
        clock.set(Phase1Support.T0 + 3 * CommandProtocol.CAPS_PROBE_MIN_MS);
        alice.pump();
        assertEquals(3, aw.frames.size()); // Third ask at 15 s.
        alice.onDisconnect();
        clock.set(Phase1Support.T0 + 60_000L);
        alice.pump();
        assertEquals(3, aw.frames.size()); // No asks while the link is down.
        alice.onReconnect(); // The reconnect hello is itself a query.
        int sent = aw.frames.size();
        assertTrue(aw.frames.get(sent - 1).body.startsWith("1|helloq|Alice|"));
        alice.receive("test", "Bob", CommandProtocol.TYPE_HELLO, "1|hello|Bob|Bobkey|" + CapabilityDescriptor.gameplay().encode());
        sent = aw.frames.size();
        clock.set(Phase1Support.T0 + 600_000L);
        alice.pump();
        assertEquals(sent, aw.frames.size()); // Learned: probing stops.
    }

    @Test
    public void reconnectAndRosterJoinEdgesAdvertise() {
        Wire wire = new Wire();
        CommandProtocol p = node("Alice", wire, new AtomicLong(Phase1Support.T0));
        p.onDisconnect();
        assertFalse(p.linkUp());
        p.onReconnect();
        assertTrue(p.linkUp());
        assertEquals(1, wire.frames.size());
        assertEquals(CommandProtocol.TYPE_HELLO, wire.frames.get(0).type);
        p.noteOnlineMembers(Set.of("Alice", "Stranger"));
        assertEquals(1, wire.frames.size());
        p.noteOnlineMembers(Set.of("Bob"));
        p.noteOnlineMembers(Set.of("Bob"));
        assertEquals(2, wire.frames.size());
        p.noteOnlineMembers(Set.of());
        p.noteOnlineMembers(Set.of("Bob"));
        assertEquals(3, wire.frames.size());
    }

    @Test
    public void queuedRetriesBackOffAndRunningAckStopsThem() {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        Wire wire = new Wire();
        CommandProtocol alice = node("Alice", wire, clock);
        alice.learnPeer("Bob", "Bobkey", CapabilityDescriptor.gameplay());
        UUID id = Phase1Support.idOf(alice.submit("Bob", "goto 1 64 1"));
        Frame first = wire.frames.get(0);
        assertEquals(SwarmPriority.NORMAL, first.priority);
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(1000);
            alice.pump();
            assertEquals(1, wire.frames.size());
        }
        clock.addAndGet(1000);
        alice.pump();
        assertEquals(2, wire.frames.size());
        assertEquals(first.body, wire.frames.get(1).body);
        assertEquals(SwarmPriority.LOW, wire.frames.get(1).priority);
        for (int i = 0; i < 7; i++) {
            clock.addAndGet(1000);
            alice.pump();
        }
        assertEquals(2, wire.frames.size());
        Wire bw = new Wire();
        CommandProtocol bob = node("Bob", bw, clock);
        bob.receive("test", "Alice", first.type, first.body);
        Frame ack = bw.frames.get(0);
        assertEquals(CommandProtocol.TYPE_ACK, ack.type);
        assertEquals(SwarmPriority.HIGH, ack.priority);
        alice.receive("test", "Bob", ack.type, ack.body);
        assertTrue(alice.lookupSent(id).acked);
        assertEquals(ExecutionState.RUNNING, alice.lookupSent(id).execution);
        for (int i = 0; i < 100; i++) {
            clock.addAndGet(1000);
            alice.pump();
        }
        assertEquals(2, wire.frames.size());
    }

    @Test
    public void cancelPreemptsOutstandingRetriesAndFailedQueueCanRetryImmediately() {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        Wire wire = new Wire();
        CommandProtocol p = node("Alice", wire, clock);
        p.learnPeer("Bob", "Bobkey", CapabilityDescriptor.gameplay());
        UUID id = Phase1Support.idOf(p.submit("Bob", "goto 1 64 1"));
        for (int i = 0; i < 30; i++) {
            clock.addAndGet(1000);
            p.pump();
        }
        assertTrue(wire.frames.size() > 1);
        for (int i = 1; i < wire.frames.size(); i++) {
            assertEquals(SwarmPriority.LOW, wire.frames.get(i).priority);
            assertEquals(wire.frames.get(0).body, wire.frames.get(i).body);
        }
        int before = wire.frames.size();
        wire.accept = false;
        UUID cancel = Phase1Support.idOf(p.submit("Bob", "cancel " + id));
        assertEquals(before + 1, wire.frames.size());
        assertEquals(SwarmPriority.HIGH, wire.frames.get(before).priority);
        wire.accept = true;
        p.pump();
        assertEquals(before + 2, wire.frames.size());
        assertEquals(p.lookupSent(cancel).body, wire.frames.get(before + 1).body);
        assertEquals(SwarmPriority.HIGH, wire.frames.get(before + 1).priority);
        for (String command : List.of("stop", "forcecancel")) {
            int count = wire.frames.size();
            assertTrue(p.submit("Bob", command).startsWith("swarm: sent "));
            assertEquals(count + 1, wire.frames.size());
            assertEquals(SwarmPriority.HIGH, wire.frames.get(count).priority);
        }
    }
}
