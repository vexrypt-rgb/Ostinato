package baritone.swarm.center.host;

import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileActions;
import baritone.swarm.center.UnifiedRegistry;
import baritone.swarm.center.channel.CenterClient;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.CatalogFeed;
import baritone.swarm.center.feed.CenterFeed;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the bot's command host runs only existing registry commands, through the same {@link TileActions} path as
 * an in-game window button, and the line it reports is the line that ran.
 */
public class CenterHostTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    final List<String> ran = new CopyOnWriteArrayList<>();
    final List<String> log = new CopyOnWriteArrayList<>();

    CenterHost host(Path dir, String owner) throws Exception {
        UnifiedRegistry reg = FakeCatalogMatchesRegistryTest.registry();
        return new CenterHost(dir, "Copper", () -> reg, line -> ran.add(line), () -> owner, log::add,
                System::currentTimeMillis);
    }

    CenterReply exec(CenterHost h, CenterRequest r) {
        return h.execute(r);
    }

    @Test public void startingWritesTheKeyHostAndCatalog() throws Exception {
        Path dir = tmp.getRoot().toPath();
        try (CenterHost h = host(dir, "")) {
            assertTrue(Files.isRegularFile(CenterFeed.keyFile(dir)));
            assertEquals(h.port(), baritone.swarm.center.channel.HostInfo.fromJson(
                    Files.readString(CenterFeed.hostFile(dir))).port());
            CatalogFeed cat = CatalogFeed.fromJson(Files.readString(CenterFeed.catalogFile(dir)));
            assertEquals("Copper", cat.host());
            assertEquals(4, cat.models(FormPlacement.TILE).size());
        }
        assertFalse("closing removes host.json", Files.exists(CenterFeed.hostFile(dir)));
        assertFalse(Files.exists(CenterFeed.keyFile(dir)));
    }

    @Test public void aTileGotoForAnotherBotRunsTheSwarmSendLine() throws Exception {
        try (CenterHost h = host(tmp.getRoot().toPath(), "")) {
            CenterReply r = exec(h, CenterRequest.press("goto", "goto", Map.of("target", "10 64 20"), "Azalea", now()));
            assertTrue(r.reason(), r.executed());
            assertEquals(List.of("swarm send Azalea goto 10 64 20"), ran);
            assertEquals("Copper: #swarm send Azalea goto 10 64 20 - accepted", r.summary());
        }
    }

    @Test public void theHostsOwnTileRunsLocally() throws Exception {
        try (CenterHost h = host(tmp.getRoot().toPath(), "")) {
            exec(h, CenterRequest.press("goto", "goto", Map.of("target", "1 2 3"), "copper", now()));
            assertEquals(List.of("goto 1 2 3"), ran);
        }
    }

    @Test public void everyTileFormRunsExactlyTheLineTheWindowPreviews() throws Exception {
        try (CenterHost h = host(tmp.getRoot().toPath(), "")) {
            for (ButtonModel b : FakeCatalogMatchesRegistryTest.registry().buttons()) {
                if (b.form().placement() != FormPlacement.TILE) {
                    continue;
                }
                Map<String, String> values = new java.util.HashMap<>();
                b.form().args().forEach(a -> values.put(a.name(), a.kind() == baritone.api.command.ArgKind.INT ? "3"
                        : "name".equals(a.name()) ? "Azalea" : "iron_ore"));
                ran.clear();
                CenterReply r = exec(h, CenterRequest.press(b.command(), b.form().id(), values, "Azalea", now()));
                String preview = baritone.swarm.center.app.Commands.preview(b, values, "Azalea", "Copper");
                assertEquals(b.form().id(), "#" + r.line(), preview);
                if (r.executed()) {
                    assertEquals(b.form().id(), List.of(r.line()), ran);
                }
            }
        }
    }

    @Test public void unknownFormsLinesAndDryRunsRunNothing() throws Exception {
        try (CenterHost h = host(tmp.getRoot().toPath(), "")) {
            assertEquals("unknown form", exec(h, CenterRequest.press("set", "x", Map.of(), "", now())).reason());
            assertEquals("unknown", exec(h, CenterRequest.line("notacommand 1", "", now())).reason());
            CenterReply dry = exec(h, CenterRequest.press("goto", "goto", Map.of("target", "1 2 3"), "Azalea", now()).dry());
            assertEquals("preview", dry.reason());
            assertEquals("swarm send Azalea goto 1 2 3", dry.line());
            assertEquals("invalid", exec(h, CenterRequest.press("mine", "mine", Map.of("count", "x", "block", "a"), "", now())).reason());
            assertTrue(ran.isEmpty());
        }
    }

    @Test public void aLineRunsThroughTheRegistry() throws Exception {
        try (CenterHost h = host(tmp.getRoot().toPath(), "")) {
            CenterReply r = exec(h, CenterRequest.line("#goto 5 6 7", "", now()));
            assertTrue(r.reason(), r.executed());
            assertEquals(List.of("goto 5 6 7"), ran);
        }
    }

    @Test public void withAnOwnerSetTheKeyHolderIsTheOwner() throws Exception {
        try (CenterHost h = host(tmp.getRoot().toPath(), "vexrypt")) {
            CenterReply r = exec(h, CenterRequest.press("swarm", "despawn", Map.of("name", "Azalea"), "Azalea", now()));
            assertTrue(r.reason(), r.admitted());
        }
    }

    /** End to end: window client, signed socket, queue, client-thread drain, registry, reply. */
    @Test public void overTheSocketWithAClientThreadDraining() throws Exception {
        Path dir = tmp.getRoot().toPath();
        try (CenterHost h = host(dir, "")) {
            AtomicBoolean stop = new AtomicBoolean();
            Thread clientThread = new Thread(() -> {
                while (!stop.get()) {
                    h.drain();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            clientThread.start();
            try {
                CenterReply r = new CenterClient(dir, System::currentTimeMillis).send(
                        CenterRequest.press("goto", "goto", Map.of("target", "7 64 7"), "Spelunker", now()));
                assertTrue(r.reason(), r.executed());
                assertEquals(List.of("swarm send Spelunker goto 7 64 7"), ran);
                assertTrue(log.toString(), log.stream().anyMatch(l -> l.startsWith("center-host: press admitted")));
            } finally {
                stop.set(true);
                clientThread.join();
            }
        }
    }

    @Test public void withNoClientThreadTheWindowIsToldTimeout() throws Exception {
        Path dir = tmp.getRoot().toPath();
        try (CenterHost h = host(dir, "")) {
            long t0 = System.currentTimeMillis();
            CenterReply r = new CenterClient(dir, System::currentTimeMillis).send(CenterRequest.line("goto 1 2 3", "", now()));
            assertEquals("timeout", r.reason());
            assertTrue(System.currentTimeMillis() - t0 >= CenterHost.WAIT_MS - 100);
            h.drain();
            assertTrue("a timed-out request never runs later", ran.isEmpty());
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
