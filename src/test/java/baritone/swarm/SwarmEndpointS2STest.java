package baritone.swarm;

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilS1C;
import baritone.swarm.crypto.SigilS2S;
import baritone.swarm.crypto.SigilWire;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.lease.LeaseMessage;
import baritone.swarm.transport.InMemorySwarmBus;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwarmEndpointS2STest {

    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);
    private InMemorySwarmBus bus;
    private Map<String, SigilCircle> groups;
    private SigilEd25519 aKey;
    private SigilEd25519 bKey;

    @Before
    public void setUp() throws Exception {
        bus = new InMemorySwarmBus();
        groups = new LinkedHashMap<String, SigilCircle>();
        groups.put("alpha", TestCircles.alpha());
        aKey = SigilEd25519.testSignet("botA-DO-NOT-USE");
        bKey = SigilEd25519.testSignet("botB-DO-NOT-USE");
    }

    private SwarmEndpoint signed(String id, SigilEd25519 local) throws Exception {
        SwarmEndpoint e = new SwarmEndpoint(id, SwarmConfig.defaults(), groups, bus.register(id), clock::get);
        e.setSigning(local, Arrays.asList(aKey, bKey));
        return e;
    }

    @Test
    public void signedRoundTrip() throws Exception {
        SwarmEndpoint a = signed("botA", aKey);
        SwarmEndpoint b = signed("botB", bKey);
        a.send("alpha", "botB", "task", "go");
        assertEquals("go", b.poll().get(0).body());
        assertTrue(b.rejectCounts().isEmpty());
    }

    @Test
    public void unsignedRejectedWhenRequired() throws Exception {
        SwarmEndpoint b = signed("botB", bKey);
        String frame = new SwarmFrame("alpha", "botA", "botB", 1, 1, 0, 1, 1, 1, "cmd", "stop").encode();
        assertEquals(null, b.receiveLine(SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), frame, 234)));
        assertEquals(SwarmReject.UNSEALED, b.lastReject());
        assertEquals(null, b.receiveLine(SigilS1C.sealSingle(TestCircles.alpha(), frame, 234)));
        assertEquals(SwarmReject.UNSEALED, b.lastReject());
    }

    @Test
    public void unsignedRejectedOnS2SWireEvenWhenNotRequired() throws Exception {
        SwarmConfig.Builder cfg = SwarmConfig.builder();
        cfg.wireVersion = "S2S";
        cfg.requireSignedSender = false;
        SwarmEndpoint b = new SwarmEndpoint("botB", cfg.build(), groups, bus.register("botB"), clock::get);
        b.setSigning(bKey, Arrays.asList(aKey, bKey));
        String frame = new SwarmFrame("alpha", "botA", "botB", 1, 1, 0, 1, 1, 1, "cmd", "stop").encode();
        assertEquals(null, b.receiveLine(SigilCodec.sealSingle(SigilWire.S2, TestCircles.alpha(), frame, 234)));
        assertEquals(SwarmReject.UNSEALED, b.lastReject());
    }

    // Ostinato: a signed frame may take up to frameLines lines; record what goes on the wire.
    private List<String> wire() {
        final List<String> seen = new ArrayList<String>();
        bus.setInterceptor(new InMemorySwarmBus.Interceptor() {
            @Override
            public List<String> deliver(String from, String to, String line) {
                seen.add(line);
                return Collections.singletonList(line);
            }
        });
        return seen;
    }

    @Test
    public void framesTakeAtMostFrameLinesLines() throws Exception {
        List<String> seen = wire();
        SwarmEndpoint a = signed("botA", aKey);
        SwarmEndpoint b = signed("botB", bKey);
        String body = TestBodies.make(150); // more than one signed line holds
        a.send("alpha", "botB", "task", body);
        assertTrue(seen.size() >= 2);
        for (String line : seen) {
            assertTrue(line.length() <= SwarmConfig.defaults().sealLineBudget());
        }
        List<SwarmMessage> got = b.poll();
        assertEquals(1, got.size());
        assertEquals(body, got.get(0).body());
        assertTrue(b.rejectCounts().toString(), b.rejectCounts().isEmpty());
        // Each frame is one signature over at most 2 lines: 2-line sends are the common case.
        int frames = 0;
        for (String line : seen) {
            if (SigilS2S.open(line, Collections.singletonList(TestCircles.alpha()), Arrays.asList(aKey, bKey))
                    .sig != null) {
                frames++;
            }
        }
        assertTrue(seen.size() + " lines for " + frames + " frames", seen.size() <= 2 * frames);
    }

    /** Ostinato: every lease op, with both ids and a long resource, is one signed frame of at most 2 chat lines. */
    @Test
    public void everyLeaseOpFitsOneTwoLineFrame() throws Exception {
        List<String> seen = wire();
        SwarmEndpoint a = signed("botA", aKey);
        SwarmEndpoint b = signed("botB", bKey);
        for (LeaseMessage.Op op : LeaseMessage.Op.values()) {
            seen.clear();
            LeaseMessage m = new LeaseMessage(op, UUID.randomUUID(), "tunnel:-29999984,-64,29999984", 123456789L,
                    UUID.randomUUID(), System.nanoTime() / 1_000_000L, 60_000L, "end");
            a.send("alpha", "botB", LeaseMessage.TYPE, m.encode());
            assertTrue(op + ": " + seen.size() + " lines", seen.size() <= 2);
            List<SwarmMessage> got = b.poll();
            assertEquals(op.toString(), 1, got.size());
            LeaseMessage back = LeaseMessage.parse(got.get(0).body());
            assertEquals(m.encode(), back.encode());
        }
    }

    /** Ostinato: Phase 2b. The longest reservation key (region, 8-digit coords) still fits a 2-line frame. */
    @Test
    public void longestReservationKeyFitsTwoLines() throws Exception {
        List<String> seen = wire();
        SwarmEndpoint a = signed("botA", aKey);
        SwarmEndpoint b = signed("botB", bKey);
        String key = baritone.swarm.lease.ReservationKey.region('o', -29999984, 256, -29999984,
                -29999921, 319, -29999921).toString();
        assertTrue(key, LeaseMessage.validResource(key));
        for (LeaseMessage.Op op : LeaseMessage.Op.values()) {
            seen.clear();
            LeaseMessage m = new LeaseMessage(op, UUID.randomUUID(), key, 123456789L,
                    UUID.randomUUID(), System.nanoTime() / 1_000_000L, 60_000L, "end");
            a.send("alpha", "botB", LeaseMessage.TYPE, m.encode());
            assertTrue(op + ": " + seen.size() + " lines", seen.size() <= 2);
            assertEquals(m.encode(), LeaseMessage.parse(b.poll().get(0).body()).encode());
        }
    }

    @Test
    public void echoedLinesCountAsReplayAndDeliverOnce() throws Exception {
        bus.setInterceptor(new InMemorySwarmBus.Interceptor() {
            @Override
            public List<String> deliver(String from, String to, String line) {
                return Arrays.asList(line, line); // chat echo: every line arrives twice
            }
        });
        SwarmEndpoint a = signed("botA", aKey);
        SwarmEndpoint b = signed("botB", bKey);
        a.send("alpha", "botB", "task", TestBodies.make(150));
        assertEquals(1, b.poll().size());
        assertTrue(b.rejectCount(SwarmReject.REPLAY) > 0);
        assertEquals(0L, b.rejectCount(SwarmReject.UNSEALED));
    }

    @Test
    public void halfAFrameExpires() throws Exception {
        bus.setInterceptor(new InMemorySwarmBus.Interceptor() {
            int n;
            @Override
            public List<String> deliver(String from, String to, String line) {
                return ++n == 2 ? Collections.<String>emptyList() : Collections.singletonList(line);
            }
        });
        SwarmEndpoint a = signed("botA", aKey);
        SwarmEndpoint b = signed("botB", bKey);
        a.send("alpha", "botB", "task", TestBodies.make(100));
        assertTrue(b.poll().isEmpty());
        clock.addAndGet(SwarmConfig.defaults().reassemblyTimeoutMs());
        assertTrue(b.poll().isEmpty());
        assertEquals(1L, b.rejectCount(SwarmReject.EXPIRED));
    }

    @Test
    public void sendWithoutSignetFailsClosed() throws Exception {
        SwarmEndpoint a = new SwarmEndpoint("botA", SwarmConfig.defaults(), groups, bus.register("botA"), clock::get);
        try {
            a.send("alpha", "botB", "t", "x");
            fail();
        } catch (Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("signet"));
        }
    }
}
