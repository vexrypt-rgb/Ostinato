package baritone.swarm.transport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Ostinato: same-PC members use the signed local spool; whispers are only for remote or a down local channel.
public class LocalRoutingTransportTest {

    private static final String SEALED = "S2S.abcd." + "A".repeat(38);
    private static final String OTHER = "S2S.abcd." + "B".repeat(38);

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);

    @Test
    public void livePeerUsesLocalSpoolNotWhispers() throws Exception {
        Path root = temporary.newFolder("spool").toPath();
        RecordingTransport whispers = new RecordingTransport("Alice");
        LocalSpoolTransport spool = new LocalSpoolTransport(root, "Alice");
        LocalRoutingTransport alice = routing("Alice", whispers, spool, root, true);
        markAlive(root, "Bob", clock.get());
        alice.sendTo("spawn", "Bob", SEALED, SwarmPriority.HIGH);
        assertTrue(whispers.sent.isEmpty());
        LocalSpoolTransport bob = new LocalSpoolTransport(root, "Bob");
        assertEquals(List.of(SEALED), bob.receive());
        assertTrue(alice.isLocal("Bob"));
    }

    @Test
    public void missingOrStaleAliveFallsBackToWhispers() throws Exception {
        Path root = temporary.newFolder("spool-stale").toPath();
        RecordingTransport whispers = new RecordingTransport("Alice");
        LocalSpoolTransport spool = new LocalSpoolTransport(root, "Alice");
        LocalRoutingTransport alice = routing("Alice", whispers, spool, root, true);
        Files.createDirectories(root.resolve("Bob"));
        alice.send("Bob", SEALED);
        assertEquals(List.of("Bob|" + SEALED), whispers.sent);
        assertTrue(new LocalSpoolTransport(root, "Bob").receive().isEmpty());

        whispers.sent.clear();
        markAlive(root, "Bob", clock.get() - 30_001L);
        alice.send("Bob", OTHER);
        assertEquals(List.of("Bob|" + OTHER), whispers.sent);
        assertFalse(alice.isLocal("Bob"));
    }

    @Test
    public void localSendFailureFallsBackToWhispers() throws Exception {
        Path root = temporary.newFolder("spool-fail").toPath();
        RecordingTransport whispers = new RecordingTransport("Alice");
        markAlive(root, "Bob", clock.get());
        LocalRoutingTransport alice = routing("Alice", whispers, new FailingTransport("Alice"), root, true);
        alice.send("Bob", SEALED);
        assertEquals(List.of("Bob|" + SEALED), whispers.sent);
    }

    @Test
    public void flagOffUsesWhispersEvenWhenAlive() throws Exception {
        Path root = temporary.newFolder("spool-off").toPath();
        RecordingTransport whispers = new RecordingTransport("Alice");
        LocalSpoolTransport spool = new LocalSpoolTransport(root, "Alice");
        LocalRoutingTransport alice = routing("Alice", whispers, spool, root, false);
        markAlive(root, "Bob", clock.get());
        alice.send("Bob", SEALED);
        assertEquals(List.of("Bob|" + SEALED), whispers.sent);
        assertFalse(alice.isLocal("Bob"));
        assertTrue(new LocalSpoolTransport(root, "Bob").receive().isEmpty());
    }

    @Test
    public void receiveMergesLocalAndWhisperInboxes() throws Exception {
        Path root = temporary.newFolder("spool-recv").toPath();
        RecordingTransport whispers = new RecordingTransport("Alice");
        whispers.inbound.add(OTHER);
        LocalSpoolTransport spool = new LocalSpoolTransport(root, "Alice");
        new LocalSpoolTransport(root, "Bob").send("Alice", SEALED);
        LocalRoutingTransport alice = routing("Alice", whispers, spool, root, true);
        assertEquals(List.of(SEALED, OTHER), alice.receive());
        assertTrue(whispers.inbound.isEmpty());
    }

    @Test
    public void broadcastSplitsLocalAndRemoteMembers() throws Exception {
        Path root = temporary.newFolder("spool-bcast").toPath();
        RecordingTransport whispers = new RecordingTransport("Alice");
        LocalSpoolTransport spool = new LocalSpoolTransport(root, "Alice");
        Function<String, Set<String>> targets = group -> Set.of("Bob", "Carol");
        LocalRoutingTransport alice = new LocalRoutingTransport("Alice", whispers, spool, root, true,
                clock::get, LocalRoutingTransport.ALIVE_TTL_MS, targets);
        markAlive(root, "Bob", clock.get());
        alice.send(SwarmTransport.BROADCAST, SEALED);
        assertEquals(List.of("Carol|" + SEALED), whispers.sent);
        assertEquals(List.of(SEALED), new LocalSpoolTransport(root, "Bob").receive());
        assertTrue(new LocalSpoolTransport(root, "Carol").receive().isEmpty());
    }

    @Test
    public void inboxDirectoryAloneIsNotTreatedAsLocal() throws Exception {
        Path root = temporary.newFolder("spool-dir").toPath();
        RecordingTransport whispers = new RecordingTransport("Alice");
        LocalSpoolTransport spool = new LocalSpoolTransport(root, "Alice");
        LocalRoutingTransport alice = routing("Alice", whispers, spool, root, true);
        // send() on the spool creates the recipient directory; that must not flip routing.
        spool.send("Bob", SEALED);
        assertFalse(alice.isLocal("Bob"));
        alice.send("Bob", OTHER);
        assertEquals(List.of("Bob|" + OTHER), whispers.sent);
    }

    private LocalRoutingTransport routing(String self, SwarmTransport fallback, SwarmTransport local,
            Path root, boolean enabled) {
        LocalRoutingTransport transport = new LocalRoutingTransport(self, fallback, local, root, enabled,
                clock::get, LocalRoutingTransport.ALIVE_TTL_MS, null);
        transport.touchAlive();
        return transport;
    }

    private static void markAlive(Path root, String name, long atMs) throws IOException {
        Path inbox = root.resolve(name);
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve(LocalRoutingTransport.ALIVE_FILE), Long.toString(atMs));
    }

    private static final class RecordingTransport implements SwarmTransport {
        final String self;
        final List<String> sent = new ArrayList<>();
        final List<String> inbound = new ArrayList<>();

        RecordingTransport(String self) {
            this.self = self;
        }

        @Override
        public String selfId() {
            return self;
        }

        @Override
        public void send(String recipient, String sealedLine) {
            sent.add(recipient + "|" + sealedLine);
        }

        @Override
        public List<String> receive() {
            List<String> out = new ArrayList<>(inbound);
            inbound.clear();
            return out;
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingTransport implements SwarmTransport {
        final String self;

        FailingTransport(String self) {
            this.self = self;
        }

        @Override
        public String selfId() {
            return self;
        }

        @Override
        public void send(String recipient, String sealedLine) throws IOException {
            throw new IOException("local channel down");
        }

        @Override
        public List<String> receive() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
