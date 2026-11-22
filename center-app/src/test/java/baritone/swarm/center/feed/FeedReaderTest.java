package baritone.swarm.center.feed;

import baritone.swarm.center.app.fake.FakeSwarm;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Ostinato: the window's reader over a real feed directory. */
public class FeedReaderTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void readsWhatTheFakeSwarmWrites() throws Exception {
        Path dir = tmp.getRoot().toPath();
        AtomicLong now = new AtomicLong(100_000);
        FakeSwarm.threeBots(now::get).write(dir);
        SwarmView v = new FeedReader(dir, now::get).snapshot();
        assertEquals(3, v.bots().size());
        assertEquals("Azalea", v.bots().get(0).name()); // name order
        assertEquals(SwarmView.Freshness.FRESH, v.bot("copper").freshness());
        assertNotNull(v.catalog());
        assertEquals("Copper", v.catalog().host());
        assertNull(v.host()); // no host.json: no channel behind a fake
        assertEquals("Copper", v.hostBot()); // still known from Copper's own feed
        assertTrue(v.problems().isEmpty());
    }

    @Test public void freshnessFollowsTheWriterClock() throws Exception {
        Path dir = tmp.getRoot().toPath();
        AtomicLong now = new AtomicLong(100_000);
        FakeSwarm.threeBots(now::get).write(dir);
        FeedReader r = new FeedReader(dir, now::get);
        now.addAndGet(CenterFeed.FRESH_MS + 1);
        assertEquals(SwarmView.Freshness.STALE, r.snapshot().bot("Azalea").freshness());
        now.addAndGet(CenterFeed.OFFLINE_MS);
        SwarmView v = r.snapshot();
        assertEquals(SwarmView.Freshness.OFFLINE, v.bot("Azalea").freshness());
        assertTrue(v.alertsFor("Azalea").stream().anyMatch(a -> a.code() == Alert.Code.OFFLINE));
    }

    @Test public void aNewerVersionIsDroppedAndNamed() throws Exception {
        Path dir = tmp.getRoot().toPath();
        AtomicLong now = new AtomicLong(100_000);
        FakeSwarm.threeBots(now::get).write(dir);
        Path f = CenterFeed.botFile(dir, "Azalea");
        Files.writeString(f, Files.readString(f).replace("\"v\":1", "\"v\":2"));
        SwarmView v = new FeedReader(dir, now::get).snapshot();
        assertNull(v.bot("Azalea"));
        assertEquals(1, v.problems().size());
        assertTrue(v.problems().get(0), v.problems().get(0).contains("bots/Azalea.json: feed v2 is newer"));
    }

    @Test public void aCorruptFileKeepsTheLastGoodCopy() throws Exception {
        Path dir = tmp.getRoot().toPath();
        AtomicLong now = new AtomicLong(100_000);
        FakeSwarm.threeBots(now::get).write(dir);
        FeedReader r = new FeedReader(dir, now::get);
        assertNotNull(r.snapshot().bot("Spelunker"));
        Path f = CenterFeed.botFile(dir, "Spelunker");
        Files.writeString(f, "{\"schema\":");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        SwarmView v = r.snapshot();
        assertNotNull(v.bot("Spelunker"));
        assertFalse(v.problems().isEmpty());
    }

    @Test public void aDeletedBotLeavesTheView() throws Exception {
        Path dir = tmp.getRoot().toPath();
        AtomicLong now = new AtomicLong(100_000);
        FakeSwarm.threeBots(now::get).write(dir);
        FeedReader r = new FeedReader(dir, now::get);
        r.snapshot();
        Files.delete(CenterFeed.botFile(dir, "Azalea"));
        assertNull(r.snapshot().bot("Azalea"));
    }

    @Test public void anEmptyDirectoryIsAnEmptyView() {
        SwarmView v = new FeedReader(tmp.getRoot().toPath().resolve("nothing"), () -> 0).snapshot();
        assertTrue(v.bots().isEmpty());
        assertNull(v.catalog());
        assertEquals("no server", v.server());
    }

    /**
     * The writer rewrites the feed every 20 ms (50 times the bots' rate) while the reader reads every 5 ms (100 times
     * the window's). The reader must never see a torn file. On Windows a rename onto a file that someone holds open
     * (the reader, or a virus scanner looking at the new file) is refused; the writer retries, and a write that still
     * fails is dropped (the bot logs it, the window keeps the last good copy). At most 1 in 10 may drop at this rate.
     */
    @Test public void concurrentRewritesAreNeverTorn() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Path f = CenterFeed.botFile(dir, "Azalea");
        CenterFeed.writeAtomic(f, FeedSchemaTest.sample().toJson());
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong attempts = new AtomicLong(), dropped = new AtomicLong();
        AtomicReference<Throwable> fail = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            long seq = 0;
            while (!stop.get()) {
                BotFeed b = FeedSchemaTest.sample();
                BotFeed next = new BotFeed(b.name(), b.writtenAtMs(), ++seq, b.server(), b.runMode(),
                        b.commandHost(), b.coordinator(), b.pos(), b.health(), b.maxHealth(), b.hunger(), b.dead(),
                        b.process(), b.path(), b.inventory(), b.executions(), b.leases(), b.perf(), b.link(),
                        b.gatherUs());
                attempts.incrementAndGet();
                try {
                    CenterFeed.writeAtomic(f, next.toJson());
                } catch (java.nio.file.AccessDeniedException e) {
                    dropped.incrementAndGet();
                } catch (Throwable t) {
                    fail.compareAndSet(null, t);
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        writer.start();
        int good = 0;
        for (int i = 0; i < 400; i++) {
            String text = CenterFeed.read(f);
            if (text != null) {
                assertEquals("Azalea", BotFeed.fromJson(text).name()); // throws on a torn file
                good++;
            }
            Thread.sleep(5);
        }
        stop.set(true);
        writer.join();
        if (fail.get() != null) {
            throw new AssertionError("writer failed", fail.get());
        }
        assertTrue("reads that saw a file: " + good, good > 380);
        assertTrue("dropped " + dropped + " of " + attempts, dropped.get() * 10 <= attempts.get());
    }
}
