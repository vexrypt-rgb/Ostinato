package baritone.swarm.center.feed;

import baritone.swarm.center.channel.HostInfo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 3 read API v1, the window's {@link SwarmSource} over the shared feed directory. Re-parses a file only
 * when its modification time or size changed, so a refresh of 11 bots costs a directory listing and a few stats. A
 * file that fails to parse keeps the last good copy for that file and adds a problem line; a refused version drops it
 * and says why. Not thread-safe: one refresh thread owns a reader.
 */
public final class FeedReader implements SwarmSource {

    private final Path dir;
    private final LongSupplier clock;
    private final Alerts.Stuck stuck = new Alerts.Stuck();
    private final Map<Path, Cached<?>> cache = new HashMap<>();

    private record Cached<T>(long mtime, long size, T value, String problem) {}

    public FeedReader(Path dir, LongSupplier clock) {
        this.dir = dir;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public Path dir() {
        return dir;
    }

    @Override
    public SwarmView snapshot() {
        long now = clock.getAsLong();
        List<String> problems = new ArrayList<>();
        List<SwarmView.BotState> bots = new ArrayList<>();
        Map<String, TerrainFeed> terrain = new LinkedHashMap<>();
        for (Path f : list(dir.resolve("bots"))) {
            BotFeed b = load(f, BotFeed::fromJson, problems);
            if (b != null) {
                long age = Math.max(0, now - b.writtenAtMs());
                bots.add(new SwarmView.BotState(b, age, SwarmView.BotState.freshness(age)));
            }
        }
        bots.sort(Comparator.comparing(b -> b.name().toLowerCase(Locale.ROOT)));
        for (Path f : list(dir.resolve("terrain"))) {
            TerrainFeed t = load(f, TerrainFeed::fromJson, problems);
            if (t != null) {
                terrain.put(t.bot(), t);
            }
        }
        CoordinatorFeed coordinator = load(CenterFeed.coordinatorFile(dir), CoordinatorFeed::fromJson, problems);
        CatalogFeed catalog = load(CenterFeed.catalogFile(dir), CatalogFeed::fromJson, problems);
        HostInfo host = load(CenterFeed.hostFile(dir), HostInfo::fromJson, problems);
        List<Alert> alerts = Alerts.derive(bots, coordinator, stuck, now);
        return new SwarmView(now, bots, coordinator, catalog, host, terrain, alerts, problems);
    }

    private static List<Path> list(Path sub) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(sub)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sub, "*.json")) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // a vanished directory reads as empty
        }
        out.sort(null);
        return out;
    }

    @SuppressWarnings("unchecked")
    private <T> T load(Path file, Function<String, T> parse, List<String> problems) {
        long mtime, size;
        try {
            if (!Files.isRegularFile(file)) {
                cache.remove(file);
                return null;
            }
            mtime = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException | RuntimeException e) {
            Cached<T> old = (Cached<T>) cache.get(file);
            return old == null ? null : old.value();
        }
        Cached<T> old = (Cached<T>) cache.get(file);
        if (old != null && old.mtime() == mtime && old.size() == size) {
            if (old.problem() != null) {
                problems.add(old.problem());
            }
            return old.value();
        }
        String text = CenterFeed.read(file);
        String name = dir.relativize(file).toString().replace('\\', '/');
        if (text == null) {
            String p = name + ": unreadable or over " + CenterFeed.MAX_BYTES / 1024 + " KB";
            problems.add(p);
            return old == null ? null : old.value();
        }
        try {
            T value = parse.apply(text);
            cache.put(file, new Cached<>(mtime, size, value, null));
            return value;
        } catch (IllegalArgumentException e) {
            String p = name + ": " + e.getMessage();
            problems.add(p);
            boolean versionRefusal = e.getMessage() != null && e.getMessage().startsWith("feed v");
            T keep = versionRefusal || old == null ? null : old.value();
            cache.put(file, new Cached<>(mtime, size, keep, p));
            return keep;
        }
    }
}
