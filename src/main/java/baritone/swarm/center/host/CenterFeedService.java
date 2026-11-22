package baritone.swarm.center.host;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.cache.WorldData;
import baritone.swarm.center.UnifiedChat;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.TerrainFeed;
import baritone.swarm.lease.LeaseCoordinator;
import baritone.swarm.world.WorldKnowledge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ostinato: Phase 3 command center, bot side. Ticked from {@code SwarmBehavior} on the client thread. With
 * {@code swarmCenterFeed} on it builds this bot's feed once a second (a few getters, cost reported as
 * {@code gatherUs}) and hands the write to one daemon thread; the coordinator feed rides along while this bot runs
 * the lease coordinator; terrain is read from the cache on that thread every 10 s. With {@code swarmCenterHost} on it
 * also runs the {@link CenterHost}. Both off: no thread, no socket, no file, and {@link #tick} returns at once.
 *
 * <p>Nothing here feeds back into the bot. A full or failing writer drops a write (the window shows the feed as
 * stale); it never blocks the tick.
 */
public final class CenterFeedService {

    /** What the swarm link side contributes (SwarmBehavior's own fields), read on the client thread. */
    public record SwarmPart(boolean commandHost, boolean coordinator, java.util.List<BotFeed.Execution> executions,
                            java.util.List<BotFeed.HeldLease> leases, baritone.swarm.SwarmSample sample,
                            BotFeed.Link link) {}

    /** What SwarmBehavior lends the service each tick. */
    public interface Inputs {
        SwarmPart swarm(boolean commandHost);

        LeaseCoordinator coordinator();

        WorldKnowledge knowledge();
    }

    private final Baritone baritone;
    private final Inputs inputs;
    private final Consumer<String> log;
    private ExecutorService writer;
    private CenterHost host;
    private Path dir;
    private long nextFeedMs, nextTerrainMs, nextCatalogMs, nextHostTryMs;
    private long seq;
    private long dropped;
    private String lastError;

    public CenterFeedService(Baritone baritone, Inputs inputs, Consumer<String> log) {
        this.baritone = baritone;
        this.inputs = inputs;
        this.log = log;
    }

    /** The shared directory for these settings and this game directory. */
    public static Path dir(Settings s, Path gameDir) {
        String d = s.swarmCenterDir.value == null ? "" : s.swarmCenterDir.value.trim();
        if (!d.isEmpty()) {
            return Path.of(d);
        }
        String spool = s.swarmLocalSpoolDir.value == null ? "" : s.swarmLocalSpoolDir.value.trim();
        if (!spool.isEmpty()) {
            Path p = Path.of(spool).toAbsolutePath();
            return p.getParent() == null ? p.resolve("center") : p.getParent().resolve("center");
        }
        return gameDir.resolve("ostinato").resolve("center");
    }

    public void tick(Settings s, Path gameDir) {
        boolean feed = s.swarmCenterFeed.value;
        boolean hostOn = s.swarmCenterHost.value;
        if (!feed && !hostOn) {
            if (writer != null || host != null) {
                close();
            }
            return;
        }
        if (baritone.getPlayerContext().player() == null) {
            return;
        }
        String name = baritone.getPlayerContext().player().getName().getString();
        if (!CenterFeed.safeName(name)) {
            return;
        }
        Path want = dir(s, gameDir);
        if (dir != null && !dir.equals(want)) {
            close();
        }
        dir = want;
        long wall = System.currentTimeMillis();
        if (hostOn) {
            hostTick(name, wall);
        } else if (host != null) {
            host.close();
            host = null;
        }
        if (!feed) {
            return;
        }
        if (writer == null) {
            writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(4), r -> {
                Thread t = new Thread(r, "ostinato-center-feed");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());
        }
        if (wall >= nextFeedMs) {
            nextFeedMs = wall + CenterFeed.BOT_PERIOD_MS;
            BotFeed bot = BotFeedGather.gather(baritone, name, wall, ++seq, inputs.swarm(host != null));
            Path file = CenterFeed.botFile(dir, name);
            submit(() -> CenterFeed.writeAtomic(file, bot.toJson()));
            LeaseCoordinator c = inputs.coordinator();
            if (c != null) {
                WorldKnowledge wk = inputs.knowledge();
                CoordinatorFeed cf = new CoordinatorFeed(name, wall, seq, c.feedMode(), wk == null ? "" : wk.world(),
                        c.feedLeases(), wk == null ? java.util.List.of() : wk.feedObservations(CoordinatorFeed.MAX_OBSERVATIONS),
                        wk == null ? 0 : wk.feedSize());
                Path cfile = CenterFeed.coordinatorFile(dir);
                submit(() -> CenterFeed.writeAtomic(cfile, cf.toJson()));
            }
        }
        if (wall >= nextTerrainMs && bot(name) != null) {
            nextTerrainMs = wall + CenterFeed.TERRAIN_PERIOD_MS;
            WorldData world = (WorldData) baritone.getWorldProvider().getCurrentWorld();
            BotFeed.Pos p = bot(name);
            if (world != null) {
                long tseq = seq;
                Path tfile = CenterFeed.terrainFile(dir, name);
                submit(() -> {
                    TerrainFeed t = BotFeedGather.terrain(name, wall, tseq, p.dim(), p.blockX(), p.blockZ(), world);
                    CenterFeed.writeAtomic(tfile, t.toJson());
                });
            }
        }
    }

    private BotFeed.Pos bot(String name) {
        var p = baritone.getPlayerContext().player();
        return p == null ? null : new BotFeed.Pos(BotFeedGather.dim(p.level()), p.getX(), p.getY(), p.getZ(), 0);
    }

    private void hostTick(String name, long wall) {
        if (host != null && !host.self().equals(name)) {
            host.close();
            host = null;
        }
        if (host == null) {
            if (wall < nextHostTryMs) {
                return;
            }
            nextHostTryMs = wall + 30_000; // a failed start is retried, not every tick
            try {
                host = new CenterHost(dir, name, UnifiedChat::registry,
                        line -> baritone.getCommandManager().execute(line),
                        () -> Baritone.settings().agentOwner.value, log, System::currentTimeMillis);
                nextCatalogMs = wall + 10_000;
            } catch (IOException | RuntimeException e) {
                log.accept("center-host: not started: " + e.getMessage());
                return;
            }
        }
        host.drain();
        if (wall >= nextCatalogMs) {
            nextCatalogMs = wall + 10_000;
            CenterHost h = host;
            submitOrRun(() -> h.writeCatalog());
        }
    }

    private interface IoTask {
        void run() throws IOException;
    }

    private void submit(IoTask task) {
        if (writer == null) {
            return;
        }
        writer.execute(() -> {
            try {
                task.run();
            } catch (IOException | RuntimeException e) {
                dropped++;
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (!msg.equals(lastError)) {
                    lastError = msg;
                    log.accept("center-feed: write failed: " + msg);
                }
            }
        });
    }

    private void submitOrRun(IoTask task) {
        if (writer != null) {
            submit(task);
            return;
        }
        try {
            task.run();
        } catch (IOException | RuntimeException ignored) {
            // the catalog is rewritten in 10 s
        }
    }

    /** Writes that failed or were dropped (for tests and #swarm status). */
    public long dropped() {
        return dropped;
    }

    public CenterHost host() {
        return host;
    }

    public Path directory() {
        return dir;
    }

    /** Stops the writer thread and the host. Feed files stay; the window sees them go stale, then offline. */
    public void close() {
        if (host != null) {
            host.close();
            host = null;
        }
        if (writer != null) {
            writer.shutdown();
            writer = null;
        }
    }
}
