package baritone.swarm;

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilS1C;
import baritone.swarm.crypto.SigilWire;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.transport.InMemorySwarmBus;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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
