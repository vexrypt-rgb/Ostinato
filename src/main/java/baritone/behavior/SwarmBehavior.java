package baritone.behavior;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.event.events.TickEvent;
import baritone.api.process.IBaritoneProcess;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.schematic.partition.PartitionAxis;
import baritone.api.schematic.partition.PartitionPlan;
import baritone.api.schematic.partition.PartitionStrategy;
import baritone.api.schematic.partition.SchematicCells;
import baritone.utils.schematic.SchematicSystem;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.swarm.SwarmBuild;
import baritone.swarm.SwarmConfig;
import baritone.swarm.SwarmControl;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.SwarmKeys;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.ChatSwarmTransport;
import baritone.swarm.transport.SwarmChannel;
import baritone.swarm.transport.SwarmRateLimiter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Vec3i;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Runs the swarm link when swarmEnabled. */
public final class SwarmBehavior extends Behavior implements Helper {

    private static final class Link {
        final String self;
        final ChatSwarmTransport transport;
        final SwarmControl control;

        Link(String self, ChatSwarmTransport transport, SwarmControl control) {
            this.self = self;
            this.transport = transport;
            this.control = control;
        }
    }

    private Link link;
    private CompletableFuture<Link> loading;
    private String loadingFor;
    private String failure;
    /** swarm* setting values the link was started with; a change (e.g. from the GUI) restarts it. */
    private String startedWith;

    public SwarmBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        try {
            tick(event);
        } catch (Throwable t) {
            failure = "tick failed: " + t;
            String was = startedWith;
            stop();
            startedWith = was; // so a settings change still restarts the link
        }
    }

    private void tick(TickEvent event) {
        Settings s = Baritone.settings();
        if (!s.swarmEnabled.value) {
            if (link != null || loading != null) {
                stop();
            }
            failure = null;
            return;
        }
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        String self = ctx.player().getName().getString();
        String settings = settingsKey(s);
        if (startedWith != null && !startedWith.equals(settings)) {
            stop();
            failure = null;
            startedWith = null;
            logDirect("swarm: settings changed, restarting the link");
        }
        if (link != null && !link.self.equals(self)) {
            stop();
        }
        if (loading != null) {
            if (!loading.isDone()) {
                return;
            }
            try {
                link = loading.join();
                failure = null;
                logDirect("swarm: online as " + self + " (" + link.control.groupCount() + " group(s), "
                        + link.control.endpoint().config().wire() + "); see #swarm status");
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                failure = c.getMessage() == null ? c.toString() : c.getMessage();
                logDirect("swarm: not started: " + failure);
            }
            loading = null;
            loadingFor = null;
        }
        if (link == null) {
            if (failure == null) {
                startedWith = settings;
                start(self, s);
            }
            return;
        }
        link.control.tick();
        LocalPlayer player = ctx.player();
        link.transport.flush(nowMs(), line -> send(player, line));
    }

    /** Since 1.19 commands go out as command packets; a {@code /msg} typed as chat would be sent as text. */
    private static void send(LocalPlayer player, String line) {
        if (line.startsWith("/")) {
            player.connection.sendCommand(line.substring(1));
        } else {
            player.connection.sendChat(line);
        }
    }

    private void start(String self, Settings s) {
        Path dir = baritone.getDirectory();
        String rosterFile = s.swarmRosterFile.value;
        String home = s.swarmSigilHome.value;
        String env = System.getenv("SIGIL_HOME");
        SwarmConfig.Builder cfg = SwarmConfig.builderFromSettings(s);
        SwarmChannel channel = SwarmChannel.parse(s.swarmChannel.value);
        String template = s.swarmCommandTemplate.value;
        double rate = s.swarmSendRatePerSec.value;
        int burst = s.swarmSendBurst.value;
        boolean unsafe = s.swarmAllowUnsafeRate.value;
        int queueMax = s.swarmSendQueueMax.value;
        loadingFor = self;
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                Path rp = Paths.get(rosterFile);
                if (!rp.isAbsolute()) {
                    rp = dir.resolve(rosterFile);
                }
                if (!Files.isRegularFile(rp)) {
                    throw new IllegalStateException("no roster at " + rp);
                }
                SwarmRoster roster = SwarmRoster.parse(new String(Files.readAllBytes(rp), StandardCharsets.UTF_8));
                Path sigilHome = SwarmKeys.sigilHome(home, env);
                Map<String, SigilCircle> circles = SwarmKeys.circlesFor(roster, self, sigilHome);
                SwarmRateLimiter limiter = new SwarmRateLimiter(rate, burst, unsafe, queueMax, nowMs());
                ChatSwarmTransport transport = new ChatSwarmTransport(self, channel, template, roster, limiter,
                        cfg.maxLineChars);
                for (SwarmRoster.Group g : roster.groupsOf(self)) {
                    int overhead = ChatSwarmTransport.templateOverhead(transport.templateFor(transport.channelFor(g.id())));
                    cfg.lineReserveChars = Math.max(cfg.lineReserveChars, overhead);
                }
                SwarmEndpoint endpoint = new SwarmEndpoint(self, cfg.build(), circles, transport,
                        System::currentTimeMillis);
                endpoint.setMemberCheck(roster::isMember);
                SwarmKeys.bindSigning(endpoint, self, sigilHome);
                SwarmControl control = new SwarmControl(roster, endpoint, new Status(), System::currentTimeMillis,
                        this::logDirect, () -> "queued " + limiter.queued() + ", sent " + limiter.sent() + ", dropped "
                        + limiter.dropped() + ", too long " + transport.tooLong() + String.format(", rate %.2f/s burst %d",
                        limiter.rate(), limiter.burst()));
                control.enableBuild(new Regions());
                return new Link(self, transport, control);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    static String settingsKey(Settings s) {
        StringBuilder b = new StringBuilder();
        for (Settings.Setting<?> setting : s.allSettings) {
            if (setting.getName().startsWith("swarm") && setting != s.swarmEnabled) {
                b.append(setting.getName()).append('=').append(setting.value).append('\n');
            }
        }
        return b.toString();
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private void stop() {
        if (loading != null) {
            loading.cancel(false);
        }
        loading = null;
        loadingFor = null;
        link = null;
        startedWith = null;
    }

    public void reload() {
        stop();
        failure = null;
    }

    public void onIncomingChat(String text) {
        try {
            Link l = link;
            if (l != null && text != null) {
                l.transport.onChat(text);
            }
        } catch (Throwable t) {
        }
    }

    public SwarmControl control() {
        return link == null ? null : link.control;
    }

    public String state() {
        if (link != null) {
            return null;
        }
        if (!Baritone.settings().swarmEnabled.value) {
            return "swarmEnabled is false";
        }
        if (loading != null) {
            return "loading roster and keyring for " + loadingFor;
        }
        return failure != null ? "not started: " + failure : "waiting for a world";
    }

    /** One short line for the settings screen: {@code online, 2/3 seen} or why the link is down. */
    public String summary() {
        Link l = link;
        return l == null ? state() : "online as " + l.self + ", " + l.control.seenCount() + "/"
                + l.control.peerCount() + " seen";
    }

    public List<String> statusLines() {
        Link l = link;
        return l == null ? Collections.singletonList("swarm: " + state()) : l.control.statusLines();
    }

    /** Builds one region of a file in the schematics folder, as ordered over the link. */
    private final class Regions implements SwarmBuild.RegionBuilder {
        @Override
        public void start(SwarmBuild.Order o) throws Exception {
            File file = new File(new File(ctx.minecraft().gameDirectory, "schematics"), o.file);
            if (!file.isFile()) {
                throw new IllegalStateException("no schematic " + o.file);
            }
            Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(file);
            if (!format.isPresent()) {
                throw new IllegalStateException("unknown schematic format " + o.file);
            }
            ISchematic schematic;
            try (InputStream in = new FileInputStream(file)) {
                schematic = format.get().parse(in);
            }
            PartitionPlan plan = SchematicCells.partition(schematic, o.count, PartitionStrategy.parse(o.strategy),
                    o.seam, PartitionAxis.parse(o.axis), o.columns);
            // commands and chat handlers may run off the game thread; the builder is driven from ticks
            pendingStart = true;
            ctx.minecraft().execute(() -> {
                try {
                    baritone.getBuilderProcess().buildRegion(o.file + "#" + o.index, schematic,
                            new Vec3i(o.x, o.y, o.z), o.index, plan);
                } catch (RuntimeException e) {
                    logDirect("swarm: region build did not start: " + e.getMessage());
                } finally {
                    pendingStart = false;
                }
            });
        }

        @Override
        public boolean busy() {
            return pendingStart || baritone.getBuilderProcess().isActive();
        }

        @Override
        public void cancel() {
            ctx.minecraft().execute(() -> baritone.getBuilderProcess().onLostControl());
        }
    }

    private volatile boolean pendingStart;

    private final class Status implements SwarmControl.LocalStatus {
        @Override
        public String name() {
            return ctx.player() == null ? "?" : ctx.player().getName().getString();
        }

        @Override
        public String position() {
            if (ctx.player() == null) {
                return "?";
            }
            BetterBlockPos p = ctx.playerFeet();
            return p.x + "," + p.y + "," + p.z;
        }

        @Override
        public String process() {
            return baritone.getPathingControlManager().mostRecentInControl()
                    .map(IBaritoneProcess::displayName).orElse("idle");
        }
    }
}
