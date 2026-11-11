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
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.swarm.SwarmConfig;
import baritone.swarm.SwarmControl;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.swarm.SwarmControlTest;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.TestCircles;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilS1C;
import baritone.swarm.crypto.SigilWire;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.protocol.Phase1Support.Pair;
import baritone.swarm.protocol.Phase1Support.RecordingExec;
import baritone.swarm.protocol.Phase1Support.Signed;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.InMemorySwarmBus;
import baritone.swarm.transport.SwarmTransport;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Gate: unexpected command execution = 0. */
public class Phase1UnexpectedExecutionTest {

    @Test
    public void unauthenticatedEnvelopeNeverExecutes() throws Exception {
        Signed world = new Signed(new InMemorySwarmBus());
        String frame = new SwarmFrame("crew", "Alice", "Bob", 1, 1, 0, 1, 1, 1, "SWC", "go").encode();
        assertNull(world.bobEp.receiveLine(SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), frame, 234)));
        assertEquals(SwarmReject.UNSEALED, world.bobEp.lastReject());
        assertNull(world.bobEp.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), frame, 234)));
        world.bobCtl.tick();
        assertEquals(0, world.bobExec.total());
    }

    @Test
    public void wrongSignerEnvelopeNeverExecutes() throws Exception {
        Signed world = new Signed(new InMemorySwarmBus());
        List<String> sealed = new ArrayList<>();
        SwarmTransport capture = new SwarmTransport() {
            @Override public String selfId() { return "Alice"; }
            @Override public void send(String recipient, String sealedLine) { sealed.add(sealedLine); }
            @Override public List<String> receive() { return List.of(); }
            @Override public void close() { }
        };
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        Map<String, SigilCircle> circles = new LinkedHashMap<>();
        circles.put("crew", TestCircles.alpha());
        SwarmEndpoint forger = new SwarmEndpoint("Alice", SwarmConfig.defaults(), circles, capture, clock::get);
        forger.setSigning(world.signets.get("Bob"), world.signets);
        forger.send("crew", "Bob", CommandProtocol.TYPE_CMD,
                Phase1Support.cmd(clock.get(), UUID.randomUUID(), "Alice", "Bob", "eta", ""));
        assertFalse(sealed.isEmpty());
        for (String line : sealed) {
            assertNull(world.bobEp.receiveLine(line));
        }
        assertEquals(SwarmReject.WRONG_SIGNER, world.bobEp.lastReject());
        world.bobCtl.tick();
        assertEquals(0, world.bobExec.total());
    }

    @Test
    public void replayedFrameNeverExecutesTwice() throws Exception {
        Signed world = new Signed(new InMemorySwarmBus());
        List<String> lines = new ArrayList<>();
        world.bus.setInterceptor((from, to, line) -> {
            lines.add(line);
            return List.of(line);
        });
        world.aliceP.submit("Bob", "eta");
        world.tick();
        assertEquals(1, world.bobExec.count("eta"));
        assertTrue(lines.size() >= 1);
        assertNull(world.bobEp.receiveLine(lines.get(0)));
        assertEquals(SwarmReject.REPLAY, world.bobEp.lastReject());
        world.bobCtl.tick();
        assertEquals(1, world.bobExec.count("eta"));
    }

    @Test
    public void tamperedEnvelopeNeverExecutes() throws Exception {
        Signed world = new Signed(new InMemorySwarmBus());
        List<String> lines = new ArrayList<>();
        world.bus.setInterceptor((from, to, line) -> {
            lines.add(line);
            return List.of();
        });
        world.aliceP.submit("Bob", "eta");
        assertFalse(lines.isEmpty());
        for (String sealed : lines) {
            char flip = sealed.charAt(sealed.length() / 2) == 'A' ? 'B' : 'A';
            String tampered = sealed.substring(0, sealed.length() / 2) + flip + sealed.substring(sealed.length() / 2 + 1);
            assertNull(world.bobEp.receiveLine(tampered));
        }
        world.bobCtl.tick();
        assertEquals(0, world.bobExec.total());
    }

    @Test
    public void notPermittedEnvelopeNeverExecutes() {
        Pair pair = new Pair();
        String body = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Bob", "Alice", "set", "allowBreak true");
        pair.alice.receive("mem", "Bob", CommandProtocol.TYPE_CMD, body);
        assertEquals(0, pair.aliceExec.count("set"));
        assertEquals(0, pair.aliceExec.total());
    }

    @Test
    public void capabilityMissingEnvelopeNeverExecutes() {
        Pair pair = new Pair();
        pair.bob.setLocalCapabilities(CapabilityDescriptor.of("navigation"));
        String body = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Alice", "Bob", "mine", "diamond_ore");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(0, pair.bobExec.count("mine"));
        pair.alice.learnPeer("Bob", "bobkey", CapabilityDescriptor.of("navigation"));
        String refused = pair.alice.submit("Bob", "mine diamond_ore");
        assertTrue(refused, refused.contains("capability"));
        assertEquals(0, pair.bobExec.total());
    }

    @Test
    public void flagOffEnvelopeNeverExecutes() throws Exception {
        AtomicLong clock = new AtomicLong(Phase1Support.T0);
        SwarmRoster roster = SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob lead=Alice\n");
        Map<String, SigilCircle> circles = new LinkedHashMap<>();
        circles.put("crew", TestCircles.alpha());
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmEndpoint alice = new SwarmEndpoint("Alice", SwarmControlTest.unsignedCfg(), circles, bus.register("Alice"), clock::get);
        SwarmEndpoint bob = new SwarmEndpoint("Bob", SwarmControlTest.unsignedCfg(), circles, bus.register("Bob"), clock::get);
        alice.setMemberCheck(roster::isMember);
        bob.setMemberCheck(roster::isMember);
        RecordingExec exec = new RecordingExec();
        SwarmControl bobCtl = new SwarmControl(roster, bob, status("Bob"), clock::get, s -> { }, null);
        alice.send("crew", "Bob", CommandProtocol.TYPE_CMD, "1|cmd|not-executed");
        bobCtl.tick();
        assertEquals(0, exec.total());
        assertTrue(bobCtl.ignoredCount() >= 1);
    }

    @Test
    public void malformedEnvelopeNeverExecutes() {
        Pair pair = new Pair();
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, "this is not a command envelope");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, "1|cmd|nope");
        assertEquals(0, pair.bobExec.total());
    }

    @Test
    public void issuerMismatchNeverExecutes() {
        Pair pair = new Pair();
        String body = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Bob", "Bob", "eta", "");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(0, pair.bobExec.total());
    }

    @Test
    public void aiToolNotOnTheWhitelistNeverExecutes() {
        Pair pair = new Pair();
        String body = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Alice", "Bob", "agent", "mine dirt");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(0, pair.bobExec.count("agent"));
    }

    @Test
    public void badArgumentsNeverExecute() {
        Pair pair = new Pair();
        String body = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Alice", "Bob", "mine", "");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(0, pair.bobExec.count("mine"));
    }

    @Test
    public void coordinatedMineOnAGroupNeverExecutes() {
        Pair pair = new Pair();
        String preview = pair.alice.submit("crew", "mine diamond_ore", pair.clock.get() + 60_000L, true, null);
        assertTrue(preview, preview.contains("preview"));
        assertEquals(0, pair.bobExec.total());
        String refused = pair.alice.submit("crew", "mine diamond_ore");
        assertTrue(refused, refused.contains("coordinated"));
        assertEquals(0, pair.bobExec.total());
        pair.bobExec.extra.put("dig", new CommandCatalog.Entry("dig", List.of(), CommandPermission.SWARM,
                Set.of("mining"), true, true, false, false, false, null));
        String body = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Alice", "crew", "dig", "");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(0, pair.bobExec.count("dig"));
    }

    @Test
    public void immediateSafetyOutranksSwarmCommand() {
        Pair pair = new Pair();
        pair.bob.setImmediateSafety(true);
        String body = Phase1Support.cmd(pair.clock.get(), UUID.randomUUID(), "Alice", "Bob", "goto", "1 2 3");
        pair.bob.receive("mem", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(0, pair.bobExec.count("goto"));
    }

    @Test
    public void ackFromNonTargetIsIgnored() {
        Pair pair = new Pair();
        String line = pair.alice.submit("Bob", "eta");
        UUID id = Phase1Support.idOf(line);
        CommandProtocol.Sent sent = pair.alice.lookupSent(id);
        assertNotNull(sent);
        assertTrue(sent.acked);
        assertTrue(sent.accepted);
        assertEquals(ExecutionState.SUCCEEDED, sent.execution);
        String fake = "1|ack|" + id + "|rejected|spoofed|failed (verified)|confirmed";
        pair.alice.receive("mem", "Alice", CommandProtocol.TYPE_ACK, fake);
        sent = pair.alice.lookupSent(id);
        assertTrue(sent.accepted);
        assertEquals(ExecutionState.SUCCEEDED, sent.execution);
    }

    @Test
    public void laterRejectedAckDoesNotUnwindConfirmedSuccess() {
        Pair pair = new Pair();
        String line = pair.alice.submit("Bob", "eta");
        UUID id = Phase1Support.idOf(line);
        CommandProtocol.Sent sent = pair.alice.lookupSent(id);
        assertNotNull(sent);
        assertTrue(sent.acked);
        assertTrue(sent.accepted);
        assertEquals(ExecutionState.SUCCEEDED, sent.execution);
        String later = "1|ack|" + id + "|rejected|rewind|failed (verified)|confirmed";
        pair.alice.receive("mem", "Bob", CommandProtocol.TYPE_ACK, later);
        sent = pair.alice.lookupSent(id);
        assertTrue(sent.accepted);
        assertEquals(ExecutionState.SUCCEEDED, sent.execution);
    }

    private static SwarmControl.LocalStatus status(String name) {
        return new SwarmControl.LocalStatus() {
            @Override public String name() { return name; }
            @Override public String position() { return "0,64,0"; }
            @Override public String process() { return "idle"; }
        };
    }
}
