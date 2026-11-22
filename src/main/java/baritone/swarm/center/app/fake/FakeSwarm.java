package baritone.swarm.center.app.fake;

import baritone.api.command.ArgDecl;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.swarm.center.channel.HostInfo;
import baritone.swarm.center.feed.Alerts;
import baritone.swarm.center.feed.CatalogFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.FeedReader;
import baritone.swarm.center.feed.SwarmSource;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.center.feed.TerrainFeed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 3 test scaffolding, a {@link SwarmSource} with no Minecraft and no files. Tests build a swarm with
 * {@link #bot}, change it between snapshots, and get the same {@link SwarmView} (alerts included) that
 * {@link FeedReader} would build from real feeds. {@link #write} also writes it as real feed files, for tests of the
 * reader and for running the window against a directory.
 *
 * <p>{@link #CATALOG} mirrors the real tile and header forms ({@code GotoCommand}, {@code MineCommand},
 * {@code SwarmCommand}, {@code CenterCommand}, {@code ThemeCommand}); {@code FakeCatalogMatchesRegistryTest} in the
 * mod's tests fails if they drift.
 */
public final class FakeSwarm implements SwarmSource {

    public static final List<CatalogFeed.Button> CATALOG = List.of(
            new CatalogFeed.Button("goto", CommandPermission.SWARM,
                    FormDecl.of("goto", "Goto", "goto", FormPlacement.TILE, ArgDecl.rest("target", true))),
            new CatalogFeed.Button("mine", CommandPermission.SWARM,
                    FormDecl.of("mine", "Mine", "mine", FormPlacement.TILE, ArgDecl.integer("count", false),
                            ArgDecl.token("block", true))),
            new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                    FormDecl.of("despawn", "Despawn", "swarm despawn", FormPlacement.TILE, ArgDecl.token("name", true))),
            new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                    FormDecl.of("cancel", "Cancel", "swarm cancel", FormPlacement.TILE, ArgDecl.token("id", false))),
            new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                    FormDecl.of("spawn", "Spawn", "swarm spawn", FormPlacement.WINDOW, ArgDecl.token("name", true),
                            ArgDecl.integer("count", false), ArgDecl.token("server", false),
                            ArgDecl.token("account", false))),
            new CatalogFeed.Button("swarm", CommandPermission.OWNER,
                    FormDecl.of("stop-all", "Stop all", "swarm despawn all", FormPlacement.HEADER)),
            new CatalogFeed.Button("center", CommandPermission.OWNER,
                    FormDecl.of("layout-wall", "Wall", "center layout wall", FormPlacement.HEADER)),
            new CatalogFeed.Button("center", CommandPermission.OWNER,
                    FormDecl.of("layout-map", "War room", "center layout map", FormPlacement.HEADER)),
            new CatalogFeed.Button("theme", CommandPermission.OWNER,
                    FormDecl.of("theme-pick", "Theme", "theme", FormPlacement.HEADER, ArgDecl.token("name", true))));

    private final LongSupplier clock;
    private final Map<String, FakeBot> bots = new LinkedHashMap<>();
    private final Map<String, TerrainFeed> terrain = new LinkedHashMap<>();
    private final Alerts.Stuck stuck = new Alerts.Stuck();
    private CoordinatorFeed coordinator;
    private boolean catalog = true;
    private boolean hostUp = true;
    private long seq;

    public FakeSwarm(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Copper (host and coordinator), Azalea and Spelunker, as in the live gates. */
    public static FakeSwarm threeBots(LongSupplier clock) {
        FakeSwarm s = new FakeSwarm(clock);
        s.bot("Copper").host().coordinator().at(0.5, 64, 0.5);
        s.bot("Azalea").at(40.5, 64, -12.5);
        s.bot("Spelunker").at(-30.5, 40, 22.5).process("Mine").execution("mine", "running", 4_000);
        return s;
    }

    /** {@code n} bots with varied states, for layout and screenshot tests (1 to 11 in the doc). */
    public static FakeSwarm many(int n, LongSupplier clock) {
        String[] names = {"Copper", "Azalea", "Spelunker", "Oak", "Willow", "Granite", "Flint", "Ember", "Birch",
                "Slate", "Moss"};
        FakeSwarm s = new FakeSwarm(clock);
        for (int i = 0; i < n; i++) {
            FakeBot b = s.bot(i < names.length ? names[i] : "Bot" + i).at(i * 20.5, 64, -i * 7.5);
            if (i == 0) {
                b.host().coordinator();
            }
            switch (i % 5) {
                case 1 -> b.process("Goto").execution("goto", "running", 3_000);
                case 2 -> b.health(5).process("Mine").execution("mine", "running", 9_000);
                case 3 -> b.execution("mine", "failed", 5_000);
                case 4 -> b.age(6_000);
                default -> { }
            }
        }
        return s;
    }

    /** The bot with this name, created with defaults the first time. */
    public FakeBot bot(String name) {
        return bots.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new FakeBot(name));
    }

    public FakeSwarm remove(String name) {
        bots.remove(name.toLowerCase(Locale.ROOT));
        return this;
    }

    public FakeSwarm coordinator(CoordinatorFeed feed) {
        coordinator = feed;
        return this;
    }

    public FakeSwarm terrain(TerrainFeed feed) {
        terrain.put(feed.bot(), feed);
        return this;
    }

    /** Without a catalog the window has no buttons (no command host has published one). */
    public FakeSwarm catalog(boolean on) {
        catalog = on;
        return this;
    }

    public FakeSwarm hostUp(boolean on) {
        hostUp = on;
        return this;
    }

    public String hostName() {
        for (FakeBot b : bots.values()) {
            if (b.commandHost) {
                return b.name;
            }
        }
        return null;
    }

    @Override
    public SwarmView snapshot() {
        long now = clock.getAsLong();
        seq++;
        List<SwarmView.BotState> states = new ArrayList<>();
        for (FakeBot b : bots.values()) {
            states.add(new SwarmView.BotState(b.build(now, seq), b.ageMs, SwarmView.BotState.freshness(b.ageMs)));
        }
        states.sort(Comparator.comparing(s -> s.name().toLowerCase(Locale.ROOT)));
        String host = hostName();
        CatalogFeed cat = catalog && host != null ? new CatalogFeed(host, now, seq, CATALOG) : null;
        HostInfo info = hostUp && host != null ? new HostInfo(host, now, seq, 0, 0, now) : null;
        return new SwarmView(now, states, coordinator, cat, info, terrain,
                Alerts.derive(states, coordinator, stuck, now), List.of());
    }

    /** Writes this swarm as real feed files under {@code dir} (no host.json: there is no socket behind it). */
    public void write(java.nio.file.Path dir) throws java.io.IOException {
        SwarmView v = snapshot();
        for (SwarmView.BotState b : v.bots()) {
            baritone.swarm.center.feed.CenterFeed.writeAtomic(
                    baritone.swarm.center.feed.CenterFeed.botFile(dir, b.name()), b.feed().toJson());
        }
        for (TerrainFeed t : terrain.values()) {
            baritone.swarm.center.feed.CenterFeed.writeAtomic(
                    baritone.swarm.center.feed.CenterFeed.terrainFile(dir, t.bot()), t.toJson());
        }
        if (v.catalog() != null) {
            baritone.swarm.center.feed.CenterFeed.writeAtomic(
                    baritone.swarm.center.feed.CenterFeed.catalogFile(dir), v.catalog().toJson());
        }
        if (coordinator != null) {
            baritone.swarm.center.feed.CenterFeed.writeAtomic(
                    baritone.swarm.center.feed.CenterFeed.coordinatorFile(dir), coordinator.toJson());
        }
    }
}
