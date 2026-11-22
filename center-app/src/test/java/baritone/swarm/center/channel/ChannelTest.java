package baritone.swarm.center.channel;

import baritone.swarm.center.feed.CenterFeed;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the authenticated loopback command channel. Nothing reaches the handler unless the MAC, id and time are
 * good; the real socket path works end to end through {@link CenterClient}.
 */
public class ChannelTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final byte[] key = CenterAuth.newKey();
    private final AtomicLong now = new AtomicLong(5_000_000);
    private final List<CenterRequest> handled = new CopyOnWriteArrayList<>();

    private CenterHostCore host() throws Exception {
        return new CenterHostCore(key, r -> {
            handled.add(r);
            return new CenterReply(r.id(), now.get(), true, true, "accepted", "goto 1 2 3", "Copper");
        }, now::get);
    }

    @Test public void aSignedRequestReachesTheHandler() throws Exception {
        try (CenterHostCore h = host()) {
            CenterRequest r = CenterRequest.press("goto", "goto", Map.of("target", "1 2 3"), "Azalea", now.get());
            CenterReply reply = h.answer(r.toWire(key));
            assertTrue(reply.admitted());
            assertEquals(1, handled.size());
            assertEquals(Map.of("target", "1 2 3"), handled.get(0).values());
            assertEquals("Azalea", handled.get(0).bot());
        }
    }

    @Test public void aBadMacReplayStaleOrMalformedRequestNeverRuns() throws Exception {
        try (CenterHostCore h = host()) {
            CenterRequest r = CenterRequest.line("goto 1 2 3", "", now.get());
            String forged = r.toWire(CenterAuth.newKey());
            assertEquals("bad mac", h.answer(forged).reason());
            String tampered = r.toWire(key).replace("goto 1 2 3", "goto 9 9 9");
            assertEquals("bad mac", h.answer(tampered).reason());
            assertEquals("accepted", h.answer(r.toWire(key)).reason());
            assertEquals("replay", h.answer(r.toWire(key)).reason());
            CenterRequest old = CenterRequest.line("goto 1 2 3", "", now.get() - CenterAuth.SKEW_MS - 1);
            assertEquals("stale", h.answer(old.toWire(key)).reason());
            assertEquals("malformed", h.answer("not json").reason());
            assertEquals("too long", h.answer(null).reason());
            assertEquals(1, handled.size());
            assertEquals(6, h.refused());
        }
    }

    @Test public void pingNeedsTheKeyButRunsNothing() throws Exception {
        try (CenterHostCore h = host()) {
            CenterReply r = h.answer(CenterRequest.ping(now.get()).toWire(key));
            assertTrue(r.admitted());
            assertFalse(r.executed());
            assertEquals("pong", r.reason());
            assertTrue(handled.isEmpty());
        }
    }

    @Test public void theHandlerThrowingIsAFailedReplyNotACrash() throws Exception {
        try (CenterHostCore h = new CenterHostCore(key, r -> {
            throw new IllegalStateException("boom");
        }, now::get)) {
            CenterReply r = h.answer(CenterRequest.line("goto 1 2 3", "", now.get()).toWire(key));
            assertFalse(r.admitted());
            assertEquals("failed", r.reason());
        }
    }

    @Test public void endToEndOverTheLoopbackSocket() throws Exception {
        Path dir = tmp.getRoot().toPath();
        try (CenterHostCore h = host()) {
            CenterFeed.writeAtomic(CenterFeed.hostFile(dir), new HostInfo("Copper", now.get(), 1, h.port(), 1, 1).toJson());
            Files.writeString(CenterFeed.keyFile(dir), CenterAuth.encodeKey(key));
            CenterClient client = new CenterClient(dir, now::get);
            CenterReply r = client.send(CenterRequest.press("goto", "goto", Map.of("target", "1 2 3"), "Azalea", now.get()));
            assertTrue(r.reason(), r.admitted());
            assertEquals("Copper: #goto 1 2 3 - accepted", r.summary());
            assertEquals(1, h.served());
        }
    }

    @Test public void theClientSaysWhyItCannotSend() throws Exception {
        Path dir = tmp.getRoot().toPath();
        CenterClient client = new CenterClient(dir, now::get);
        assertEquals("no command host (host.json missing)", client.send(CenterRequest.ping(now.get())).reason());
        CenterFeed.writeAtomic(CenterFeed.hostFile(dir), new HostInfo("Copper", 1, 1, 1, 1, 1).toJson());
        assertEquals("no host key", client.send(CenterRequest.ping(now.get())).reason());
        Files.writeString(CenterFeed.keyFile(dir), CenterAuth.encodeKey(key));
        assertTrue(client.send(CenterRequest.ping(now.get())).reason().startsWith("host unreachable"));
    }

    @Test public void aReplySignedWithAnotherKeyIsRefused() {
        CenterReply r = new CenterReply("id", 1, true, true, "accepted", "goto 1 2 3", "Copper");
        assertEquals("bad reply mac", CenterReply.fromWire(r.toWire(CenterAuth.newKey()), key, 1).reason());
        assertTrue(CenterReply.fromWire(r.toWire(key), key, 1).admitted());
    }

    @Test public void anOversizedLineOnTheSocketIsRefused() throws Exception {
        try (CenterHostCore h = host(); Socket s = new Socket(InetAddress.getLoopbackAddress(), h.port())) {
            OutputStream out = s.getOutputStream();
            out.write(("x".repeat(CenterRequest.MAX_CHARS + 10) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
                    StandardCharsets.UTF_8)).readLine();
            assertTrue(line, line.contains("\"too long\""));
            assertTrue(handled.isEmpty());
        }
    }

    @Test public void theHostListensOnLoopbackOnly() throws Exception {
        try (CenterHostCore h = host()) {
            assertTrue(h.port() > 0);
            // Ostinato: connecting through a non-loopback interface address must fail to connect at all
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || !(a instanceof java.net.Inet4Address)) {
                        continue;
                    }
                    try (Socket s = new Socket()) {
                        s.connect(new java.net.InetSocketAddress(a, h.port()), 300);
                        throw new AssertionError("reachable on " + a);
                    } catch (java.io.IOException expected) {
                        // refused: bound to 127.0.0.1 only
                    }
                }
            }
        }
    }
}
