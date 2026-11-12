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


package baritone.swarm;

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.ChatSwarmTransport;
import baritone.swarm.transport.InMemorySwarmBus;
import baritone.swarm.transport.SwarmChannel;
import baritone.swarm.transport.SwarmRateLimiter;
import baritone.swarm.transport.SwarmTransport;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwarmControlTest {

    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);
    private final List<String> log = new ArrayList<>();
    private SwarmRoster roster;
    private Map<String, SigilCircle> circles;

    @Before
    public void setUp() throws Exception {
        roster = SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob,Carol lead=Alice\n");
        circles = new LinkedHashMap<>();
        circles.put("crew", TestCircles.alpha());
    }

    /** These fixtures carry no signets, so they run the unsigned circle wire (S2S is covered by the S2S tests). */
    public static SwarmConfig unsignedCfg() {
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.requireSignedSender = false;
        return b.build();
    }

    private static SwarmControl.LocalStatus status(String name, String pos, String proc) {
        return new SwarmControl.LocalStatus() {
            @Override
            public String name() { return name; }
            @Override
            public String position() { return pos; }
            @Override
            public String process() { return proc; }
        };
    }

    private SwarmControl control(String name, SwarmTransport t, String proc) throws Exception {
        SwarmEndpoint e = new SwarmEndpoint(name, unsignedCfg(), circles, t, clock::get);
        e.setMemberCheck(roster::isMember);
        return new SwarmControl(roster, e, status(name, "10,64,-20", proc), clock::get, log::add, null);
    }

    @Test
    public void pingPongRoundTripInMemory() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmControl alice = control("Alice", bus.register("Alice"), "idle");
        SwarmControl bob = control("Bob", bus.register("Bob"), "Builder;x=1");
        SwarmControl carol = control("Carol", bus.register("Carol"), "Mine diamond_ore");
        assertEquals(1, alice.ping("crew"));
        clock.addAndGet(700);
        bob.tick();
        carol.tick();
        clock.addAndGet(800);
        alice.tick();
        SwarmControl.Peer b = alice.peer("Bob");
        assertNotNull(b);
        assertEquals("10,64,-20", b.pos());
        assertEquals("Builder_x_1", b.proc()); // ';' and '=' cannot forge fields
        assertEquals(1500, b.rttMs());
        assertEquals("Mine diamond_ore", alice.peer("Carol").proc());
        assertEquals(clock.get(), alice.peer("Carol").lastSeenMs());
        // Bob and Carol saw Alice's ping (last-seen) but got no pong from her
        assertEquals(-1, bob.peer("Alice").pongAtMs());
        List<String> st = alice.statusLines();
        String all = String.join("\n", st);
        assertTrue(all, all.contains("group crew (circle swarm-test-alpha, lead Alice)"));
        assertTrue(all, all.contains("Bob: seen 0s ago, pos 10,64,-20, Builder_x_1, rtt 1500 ms"));
        assertTrue(all, all.contains("Carol: seen 0s ago"));
        assertTrue(String.join("\n", bob.statusLines()).contains("Carol: never seen"));
        assertEquals(0, alice.endpoint().rejectCount(SwarmReject.NOT_MEMBER));
    }

    @Test
    public void pingNullPingsAllMyGroupsAndRefusesForeignGroup() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmControl alice = control("Alice", bus.register("Alice"), "idle");
        assertEquals(1, alice.ping(null));
        try {
            alice.ping("builders");
            fail("pinged a group we are not in");
        } catch (IllegalArgumentException expected) {
            // not a member
        }
    }

    @Test
    public void nonMemberWithTheKeyIsRejected() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmControl alice = control("Alice", bus.register("Alice"), "idle");
        // Mallory holds the circle key but is not in the roster
        SwarmEndpoint mallory = new SwarmEndpoint("Mallory", unsignedCfg(), circles,
                bus.register("Mallory"), clock::get);
        mallory.send("crew", SwarmTransport.BROADCAST, SwarmControl.PING, "ping");
        alice.tick();
        assertEquals(1, alice.endpoint().rejectCount(SwarmReject.NOT_MEMBER));
        assertNull(alice.peer("Mallory"));
        assertEquals(0, mallory.poll().size()); // no pong went out
    }

    /** A chat server that speaks vanilla 1.16.1 formats and routes by the command text only. */
    private static final class ChatServer {
        final Map<String, ChatSwarmTransport> online = new LinkedHashMap<>();
        int lines;

        void deliver(String sender, String command) {
            lines++;
            if (command.startsWith("/msg ")) {
                String rest = command.substring(5);
                int sp = rest.indexOf(' ');
                String to = rest.substring(0, sp);
                String msg = rest.substring(sp + 1);
                ChatSwarmTransport t = online.get(to);
                if (t != null) {
                    t.onChat(sender + " whispers to you: " + msg);
                }
                online.get(sender).onChat("You whisper to " + to + ": " + msg);
            } else {
                for (ChatSwarmTransport t : online.values()) {
                    t.onChat("<" + sender + "> " + command);
                }
            }
        }

        void flushAll(long nowMs) {
            for (Map.Entry<String, ChatSwarmTransport> e : online.entrySet()) {
                String who = e.getKey();
                List<String> out = new ArrayList<>();
                e.getValue().flush(nowMs, out::add);
                out.forEach(c -> deliver(who, c));
            }
        }
    }

    private ChatSwarmTransport chat(ChatServer server, String name, SwarmChannel ch) {
        ChatSwarmTransport t = new ChatSwarmTransport(name, ch, "", roster,
                new SwarmRateLimiter(0.5, 5, false, 64, 0), 256);
        server.online.put(name, t);
        return t;
    }

    @Test
    public void pingPongOverVanillaWhispersWithRateLimits() throws Exception {
        ChatServer server = new ChatServer();
        SwarmControl alice = control("Alice", chat(server, "Alice", SwarmChannel.WHISPER), "idle");
        SwarmControl bob = control("Bob", chat(server, "Bob", SwarmChannel.WHISPER), "Builder");
        SwarmControl carol = control("Carol", chat(server, "Carol", SwarmChannel.WHISPER), "Farm");
        alice.ping("crew");
        for (int i = 0; i < 20; i++) { // 20 ticks of 50 ms
            server.flushAll(i * 50L);
            clock.addAndGet(50);
            alice.tick();
            bob.tick();
            carol.tick();
        }
        server.flushAll(1000);
        alice.tick();
        assertEquals("Builder", alice.peer("Bob").proc());
        assertEquals("Farm", alice.peer("Carol").proc());
        assertEquals(4, server.lines); // 2 pings (fan-out) + 2 pongs, nothing else
        // the "You whisper to ..." echoes carry Alice's own frames: dropped as SELF, not errors
        assertTrue(alice.endpoint().rejectCount(SwarmReject.SELF) >= 2);
        assertEquals(0, alice.endpoint().rejectCount(SwarmReject.UNSEALED));
    }

    @Test
    public void pingPongOverGlobalChat() throws Exception {
        ChatServer server = new ChatServer();
        SwarmControl alice = control("Alice", chat(server, "Alice", SwarmChannel.GLOBAL), "idle");
        SwarmControl bob = control("Bob", chat(server, "Bob", SwarmChannel.GLOBAL), "Builder");
        alice.ping("crew");
        server.flushAll(0);
        bob.tick();
        server.flushAll(0);
        alice.tick();
        assertEquals("Builder", alice.peer("Bob").proc());
        assertEquals(2, server.lines); // one public ping, one public pong
    }

    @Test
    public void outOfRangeOrUnknownSenderNeverThrows() throws Exception {
        ChatSwarmTransport bobChat = new ChatSwarmTransport("Bob", SwarmChannel.WHISPER, "", roster,
                new SwarmRateLimiter(0.5, 5, false, 64, 0), 256);
        SwarmControl bob = control("Bob", bobChat, "idle");
        // Alice is nowhere near Bob (not in any world player list); only her sealed line arrives
        List<String> sent = new ArrayList<>();
        ChatSwarmTransport aliceChat = new ChatSwarmTransport("Alice", SwarmChannel.WHISPER, "", roster,
                new SwarmRateLimiter(0.5, 5, false, 64, 0), 256);
        SwarmEndpoint alice = new SwarmEndpoint("Alice", unsignedCfg(), circles, aliceChat, clock::get);
        alice.send("crew", "Bob", SwarmControl.PING, "ping");
        aliceChat.flush(0, sent::add);
        assertEquals(1, sent.size());
        String token = sent.get(0).substring("/msg Bob ".length());
        // the wrapper names a nick that matches nobody; a proxy may even show a display name
        bobChat.onChat("[\u00a7dxX_Gh0st_Xx\u00a7r -> me] " + token);
        bobChat.onChat("Nobody whispers to you: S2C.zzzz." + token.substring(9)); // garbled slug
        bobChat.onChat("\u00a7k" + "whatever S1C.");
        bobChat.onChat(null);
        bob.tick();
        SwarmControl.Peer a = bob.peer("Alice");
        assertNotNull("sender comes from the sealed envelope", a);
        assertNull(bob.peer("xX_Gh0st_Xx"));
        assertEquals(1, bob.endpoint().rejectCount(SwarmReject.UNSEALED));
        List<String> pong = new ArrayList<>();
        bobChat.flush(0, pong::add);
        assertEquals(Collections.singletonList("/msg Alice " + pong.get(0).substring("/msg Alice ".length())), pong);
    }

    // Ostinato: STS sample for the performance strip and link monitor.
    private static final SwarmSample ALICE_SAMPLE = new SwarmSample(12, 199, 35, 812, 2048, 3, 35, 1, 2, "/msg");

    private SwarmControl sampler(String name, SwarmTransport t, SwarmSample sample) throws Exception {
        SwarmEndpoint e = new SwarmEndpoint(name, unsignedCfg(), circles, t, clock::get);
        e.setMemberCheck(roster::isMember);
        SwarmControl.LocalStatus st = new SwarmControl.LocalStatus() {
            @Override
            public String name() { return name; }
            @Override
            public String position() { return "0,64,0"; }
            @Override
            public String process() { return "idle"; }
            @Override
            public SwarmSample sample() { return sample; }
        };
        return new SwarmControl(roster, e, st, clock::get, log::add, null);
    }

    private static SwarmControl.PeerLink link(SwarmControl c, String name) {
        for (SwarmControl.PeerLink l : c.peerLinks()) {
            if (l.name().equals(name)) {
                return l;
            }
        }
        return null;
    }

    @Test
    public void sampleRoundTripWithoutProtocol() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmControl alice = sampler("Alice", bus.register("Alice"), ALICE_SAMPLE);
        SwarmControl bob = sampler("Bob", bus.register("Bob"), SwarmSample.UNKNOWN);
        alice.setSampling(true, () -> true);
        alice.tick();
        assertEquals(1, alice.samplesSent());
        clock.addAndGet(500);
        bob.tick();
        SwarmControl.PeerLink a = link(bob, "Alice");
        assertNotNull(a);
        assertEquals(ALICE_SAMPLE, a.sample());
        assertEquals(clock.get(), a.sampleAtMs());
        assertEquals(1, a.receivedInWindow());
        assertEquals(1, a.received());
        // no second sample until the interval passes
        clock.addAndGet(1_000);
        alice.tick();
        assertEquals(1, alice.samplesSent());
        clock.addAndGet(SwarmControl.SAMPLE_INTERVAL_MS);
        alice.tick();
        assertEquals(2, alice.samplesSent());
    }

    @Test
    public void sampleWaitsForAQuietLink() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmControl alice = sampler("Alice", bus.register("Alice"), ALICE_SAMPLE);
        bus.register("Bob");
        AtomicLong busy = new AtomicLong(1);
        alice.setSampling(true, () -> busy.get() == 0);
        alice.tick();
        assertEquals(0, alice.samplesSent());
        busy.set(0);
        clock.addAndGet(1_000);
        alice.tick();
        assertEquals(0, alice.samplesSent()); // retries 5 s after the busy check
        clock.addAndGet(4_000);
        alice.tick();
        assertEquals(1, alice.samplesSent());
    }

    @Test
    public void samplingIsOffByDefault() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmControl alice = sampler("Alice", bus.register("Alice"), ALICE_SAMPLE);
        bus.register("Bob");
        alice.tick();
        clock.addAndGet(SwarmControl.SAMPLE_INTERVAL_MS * 3);
        alice.tick();
        assertEquals(0, alice.samplesSent());
    }

    @Test
    public void olderPeerThatOnlyPingsHasNoSample() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        SwarmControl alice = control("Alice", bus.register("Alice"), "idle");
        SwarmControl bob = control("Bob", bus.register("Bob"), "idle");
        alice.ping("crew");
        clock.addAndGet(100);
        bob.tick();
        SwarmControl.PeerLink a = link(bob, "Alice");
        assertNotNull(a);
        assertEquals(SwarmSample.UNKNOWN, a.sample());
        assertEquals(-1, a.sampleAtMs());
        assertEquals(1, a.receivedInWindow());
        assertEquals(clock.get(), a.lastSeenMs());
    }

    @Test
    public void fullSampleBodyIsShort() {
        assertTrue(ALICE_SAMPLE.encode().length() < 100);
        SwarmSample worst = new SwarmSample(99_999_999, 99_999_999, 99_999_999, 99_999_999, 99_999_999,
                99_999_999, 99_999_999, 99_999_999, 99_999_999, "/abcdefghijklmno");
        assertTrue(worst.encode(), worst.encode().length() < 120);
    }

    @Test
    public void bodyFieldsAreSanitised() {
        assertEquals("a_b_c_d", SwarmControl.clean("a;b=c\u00a7d"));
        assertEquals("?", SwarmControl.clean(""));
        assertEquals(SwarmControl.MAX_FIELD, SwarmControl.clean(new String(new char[100]).replace('\0', 'x')).length());
        Map<String, String> kv = SwarmControl.parseBody("name=Bob;pos=1,2,3;proc=x;re=zz;junk");
        assertEquals("1,2,3", kv.get("pos"));
        assertEquals(4, kv.size());
    }

    @Test
    public void ageFormatting() {
        assertEquals("5s", SwarmControl.age(5_000));
        assertEquals("3m", SwarmControl.age(180_000));
        assertEquals("2h", SwarmControl.age(7_200_000));
        assertEquals("0s", SwarmControl.age(-5));
    }
}
