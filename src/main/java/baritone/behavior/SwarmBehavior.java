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
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.protocol.AtCatalogBridge;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.protocol.CommandProtocol;
import baritone.swarm.protocol.RegistryExecutor;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.ChatSwarmTransport;
import baritone.swarm.transport.LocalRoutingTransport;
import baritone.swarm.transport.SwarmChannel;
import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmRateLimiter;
import baritone.api.swarm.protocol.ExternalCommandHook;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Vec3i;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Runs the swarm link when swarmEnabled. */
public final class SwarmBehavior extends Behavior implements Helper {

    private static final class Link {
        final String self;
        final LocalRoutingTransport transport;
        final SwarmControl control;
        /** Ostinato: null when swarmCommandProtocol is off. */
        final CommandProtocol protocol;

        // Ostinato: immutable loaded roster; restart the link when the file contents change.
        final Path rosterPath;
        final byte[] rosterBytes;
        long nextRosterCheck;

        Link(String self, LocalRoutingTransport transport, SwarmControl control, CommandProtocol protocol,
             Path rosterPath, byte[] rosterBytes) {
            this.self = self;
            this.transport = transport;
            this.control = control;
            this.protocol = protocol;
            this.rosterPath = rosterPath; // Ostinato:
            this.rosterBytes = rosterBytes; // Ostinato:
        }
    }

    private Link link;
    private CompletableFuture<Link> loading;
    private String loadingFor;
    private String failure;
    /** Ostinato: kept after a drop so status can still show running + unknown. Cleared when the protocol is off. */
    private CommandProtocol retainedProtocol;
    private volatile ExternalCommandHook externalHook;
    private volatile boolean ownerEmergency;
    private volatile boolean immediateSafety;
    private volatile boolean localSurvival;
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
            // Ostinato: retain the link, but mark its first world exit exactly once.
            if (link != null && link.protocol != null && link.protocol.linkUp()) {
                link.protocol.onDisconnect();
            }
            return;
        }
        String self = ctx.player().getName().getString();
        String settings = settingsKey(s);
        if (startedWith != null && !startedWith.equals(settings)) {
            stop(true); // Ostinato: planned settings restart.
            failure = null;
            startedWith = null;
            logDirect("swarm: settings changed, restarting the link");
        }
        if (link != null && !link.self.equals(self)) {
            stop();
        }
        // Ostinato: poll the small roster file once a second, including same-mtime edits.
        if (link != null && nowMs() >= link.nextRosterCheck) {
            link.nextRosterCheck = nowMs() + 1000L;
            try {
                if (!Files.isRegularFile(link.rosterPath)
                        || !java.util.Arrays.equals(link.rosterBytes, Files.readAllBytes(link.rosterPath))) {
                    stop(true); // Ostinato: planned roster restart.
                    failure = null;
                    logDirect("swarm: roster changed, restarting the link");
                }
            } catch (java.io.IOException e) {
                // Ostinato: a transient read must not sticky-fail the live link.
                logDirect("swarm: could not reread roster (" + e.getMessage() + "); will try again");
            }
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
        // Ostinato: create already advertises; only a disconnected existing protocol reconnects.
        if (link.protocol != null) {
            if (!link.protocol.linkUp()) {
                link.protocol.onReconnect();
            }
            link.protocol.noteOnlineMembers(onlinePlayerNames());
        }
        link.control.tick();
        LocalPlayer player = ctx.player();
        link.transport.flush(nowMs(), line -> send(player, line));
    }

    // Ostinato: read the tab roster on the client tick thread, never in the async loader.
    private Set<String> onlinePlayerNames() {
        Set<String> online = new HashSet<>();
        try {
            var connection = ctx.player().connection;
            if (connection == null) {
                return online;
            }
            for (var info : connection.getOnlinePlayers()) {
                if (info == null || info.getProfile() == null || info.getProfile().name() == null) {
                    continue;
                }
                online.add(info.getProfile().name());
            }
        } catch (RuntimeException ignored) {
            // Ostinato: tab list can be mid-update; try again next tick.
        }
        return online;
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
        // Ostinato: @ catalog text, read on this thread. Null when the protocol is off or TenorClef is absent.
        final boolean protocolOn = s.swarmCommandProtocol.value;
        final Set<String> online = protocolOn ? onlinePlayerNames() : Set.of(); // Ostinato: initial snapshot.
        final String atCatalog = protocolOn ? AtCatalogBridge.exportInstalled() : null;
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                Path rp = Paths.get(rosterFile);
                if (!rp.isAbsolute()) {
                    rp = dir.resolve(rosterFile);
                }
                if (!Files.isRegularFile(rp)) {
                    throw new IllegalStateException("no roster at " + rp);
                }
                byte[] rosterBytes = Files.readAllBytes(rp); // Ostinato: retain precisely what was parsed.
                SwarmRoster roster = SwarmRoster.parse(new String(rosterBytes, StandardCharsets.UTF_8));
                Path sigilHome = SwarmKeys.sigilHome(home, env);
                Map<String, SigilCircle> circles = SwarmKeys.circlesFor(roster, self, sigilHome);
                SwarmRateLimiter limiter = new SwarmRateLimiter(rate, burst, unsafe, queueMax, nowMs());
                ChatSwarmTransport chat = new ChatSwarmTransport(self, channel, template, roster, limiter,
                        cfg.maxLineChars);
                for (SwarmRoster.Group g : roster.groupsOf(self)) {
                    int overhead = ChatSwarmTransport.templateOverhead(chat.templateFor(chat.channelFor(g.id())));
                    cfg.lineReserveChars = Math.max(cfg.lineReserveChars, overhead);
                }
                // Ostinato: same-PC members prefer the local spool; whispers stay the fallback.
                LocalRoutingTransport transport = LocalRoutingTransport.open(self, chat, s.swarmLocalRouting.value,
                        s.swarmLocalSpoolDir.value, dir, this::logDirect);
                SwarmEndpoint endpoint = new SwarmEndpoint(self, cfg.build(), circles, transport,
                        System::currentTimeMillis);
                endpoint.setMemberCheck(roster::isMember);
                SwarmKeys.bindSigning(endpoint, self, sigilHome);
                SwarmControl control = new SwarmControl(roster, endpoint, new Status(), System::currentTimeMillis,
                        this::logDirect, () -> "queued " + limiter.queued() + ", sent " + limiter.sent() + ", dropped "
                        + limiter.dropped() + ", too long " + transport.tooLong() + String.format(", rate %.2f/s burst %d",
                        limiter.rate(), limiter.burst()));
                control.enableBuild(new Regions());
                // Ostinato: command protocol rides the same sealed link. Off, this stays null.
                CommandProtocol protocol = null;
                if (protocolOn) {
                    Set<String> members = new HashSet<>();
                    Set<String> groupIds = new HashSet<>();
                    members.add(self);
                    for (SwarmRoster.Group g : roster.groupsOf(self)) {
                        members.addAll(g.members());
                        groupIds.add(g.id());
                    }
                    CommandProtocol created = new CommandProtocol(
                            new CommandProtocol.BotIdentity(self, endpoint.localSignetKeyId()),
                            members,
                            new CommandProtocol.Wire() { // Ostinato: carry priority through sealing.
                                @Override
                                public boolean send(String to, String type, String body) {
                                    return send(to, type, body, SwarmPriority.NORMAL);
                                }

                                @Override
                                public boolean send(String to, String type, String body, SwarmPriority priority) {
                                    return sendSealed(endpoint, roster, self, to, type, body, priority);
                                }
                            },
                            new RegistryExecutor(baritone, () -> externalHook, atCatalog),
                            new CommandProtocol.Policy(s.agentOwner.value, s.agentOwnerOnly.value,
                                    s.agentDryRun.value, s.agentTools.value),
                            System::currentTimeMillis, this::logDirect, 2000L);
                    created.setOwnerEmergency(ownerEmergency);
                    created.setImmediateSafety(immediateSafety);
                    created.setLocalSurvival(localSurvival);
                    created.setGroups(groupIds);
                    created.setLocalCapabilities(CapabilityDescriptor.gameplay());
                    try {
                        Map<String, SigilEd25519> pins = SwarmKeys.signets(sigilHome);
                        for (Map.Entry<String, SigilEd25519> e : pins.entrySet()) {
                            SigilEd25519 key = e.getValue();
                            if (key != null && key.keyid() != null) {
                                created.pinSignet(e.getKey(), CapabilityDescriptor.hex(key.keyid()));
                            }
                        }
                    } catch (Exception ignoredPins) {
                        // No pins: peer capabilities stay unknown and cap-requiring commands are refused.
                    }
                    control.setCommandProtocol(created);
                    // Ostinato: seed join tracking and advertise exactly once on creation.
                    if (!created.noteOnlineMembers(online)) {
                        created.advertise();
                    }
                    protocol = created;
                }
                return new Link(self, transport, control, protocol, rp, rosterBytes); // Ostinato:
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
        stop(false); // Ostinato: failures and disabling still announce.
    }

    // Ostinato: distinguish planned restarts from real link loss.
    private void stop(boolean plannedRestart) {
        if (loading != null) {
            loading.cancel(false);
        }
        loading = null;
        loadingFor = null;
        if (link != null && link.protocol != null) {
            // Ostinato: a drop is running + unknown, not a silent success.
            link.protocol.onDisconnect(!plannedRestart); // Ostinato:
            retainedProtocol = link.protocol;
        }
        link = null;
        startedWith = null;
        if (!Baritone.settings().swarmCommandProtocol.value) {
            retainedProtocol = null;
        }
    }

    private static boolean sendSealed(SwarmEndpoint endpoint, SwarmRoster roster, String self, String to, String type,
                                      String body, SwarmPriority priority) { // Ostinato:
        try {
            String group = null;
            for (SwarmRoster.Group g : roster.groupsOf(self)) {
                if ("*".equals(to) || "all".equalsIgnoreCase(to) || g.has(to)) {
                    group = g.id();
                    break;
                }
            }
            if (group == null) {
                return false;
            }
            String dest = ("*".equals(to) || "all".equalsIgnoreCase(to)) ? SwarmFrame.BROADCAST : to;
            endpoint.send(group, dest, type, body, priority); // Ostinato: cancels overtake retries.
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private CommandProtocol liveProtocol() {
        return link == null ? null : link.protocol;
    }

    private CommandProtocol anyProtocol() {
        CommandProtocol live = liveProtocol();
        return live != null ? live : retainedProtocol;
    }

    /** Ostinato: owner pause. No effect when the command protocol is not attached. */
    public void noteOwnerPause(boolean paused) {
        noteOwnerEmergency(paused);
    }

    /** Ostinato: safety hierarchy level 2. */
    public void noteOwnerEmergency(boolean on) {
        ownerEmergency = on;
        CommandProtocol p = anyProtocol();
        if (p != null) {
            p.setOwnerEmergency(on);
        }
    }

    /** Ostinato: safety hierarchy level 1. */
    public void noteImmediateSafety(boolean on) {
        immediateSafety = on;
        CommandProtocol p = anyProtocol();
        if (p != null) {
            p.setImmediateSafety(on);
        }
    }

    /** Ostinato: safety hierarchy level 3. */
    public void noteLocalSurvival(boolean on) {
        localSurvival = on;
        CommandProtocol p = anyProtocol();
        if (p != null) {
            p.setLocalSurvival(on);
        }
    }

    /** Ostinato: {@code @} command hook. Ignored until the protocol is on. */
    public void setExternalCommandHook(ExternalCommandHook hook) {
        this.externalHook = hook;
    }

    /** Ostinato: send one registry command. Does nothing useful when the protocol is off. */
    public String submitCommand(String target, String commandLine) {
        if (!Baritone.settings().swarmCommandProtocol.value) {
            return "swarm: swarmCommandProtocol is false";
        }
        CommandProtocol p = liveProtocol();
        if (p == null) {
            return "swarm: link is not running (" + state() + ")";
        }
        return p.submit(target, commandLine);
    }

    /** Ostinato: cancel a command this bot sent. The cancel carries that command id. */
    public String cancelCommand(String commandId) {
        if (!Baritone.settings().swarmCommandProtocol.value) {
            return "swarm: swarmCommandProtocol is false";
        }
        CommandProtocol p = liveProtocol();
        if (p == null) {
            return "swarm: link is not running (" + state() + ")";
        }
        UUID id;
        try {
            id = UUID.fromString(commandId == null ? "" : commandId.trim());
        } catch (IllegalArgumentException e) {
            return "swarm: not a command id";
        }
        CommandProtocol.Sent sent = p.lookupSent(id);
        if (sent == null) {
            return "swarm: no outgoing command " + id;
        }
        return p.submit(sent.target, "cancel " + id);
    }

    /** Ostinato: ack and outcome for one id, including after the link drops. */
    public String commandOutcome(String commandId) {
        CommandProtocol p = anyProtocol();
        if (p == null) {
            return Baritone.settings().swarmCommandProtocol.value
                    ? "swarm: link is not running (" + state() + ")"
                    : "swarm: swarmCommandProtocol is false";
        }
        return p.outcomeLine(commandId);
    }

    public void reload() {
        stop(true); // Ostinato: spawn/explicit reload is a planned restart.
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
        List<String> base = l == null ? Collections.singletonList("swarm: " + state()) : l.control.statusLines();
        CommandProtocol p = anyProtocol();
        if (p == null) {
            return base;
        }
        // Ostinato: read-only protocol lines. Building this list does not touch execution.
        List<String> all = new ArrayList<>(base);
        all.addAll(p.statusLines());
        return all;
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
