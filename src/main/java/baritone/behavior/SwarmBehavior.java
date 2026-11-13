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
import baritone.swarm.SwarmSample;
import baritone.swarm.TickRateMeter;
import baritone.utils.PlanStats;
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.protocol.AtCatalogBridge;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.lease.BotLeaseGuard;
import baritone.swarm.lease.CoordinatorStore;
import baritone.swarm.lease.LeaseCoordinator;
import baritone.swarm.lease.LeaseFence;
import baritone.swarm.lease.LeaseMessage;
import baritone.swarm.lease.LeaseRate;
import baritone.swarm.lease.ReservationResolver;
import baritone.swarm.protocol.CommandProtocol;
import baritone.swarm.protocol.RegistryExecutor;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.ChatSwarmTransport;
import baritone.swarm.transport.LocalRoutingTransport;
import baritone.swarm.transport.SwarmChannel;
import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmRateLimiter;
import baritone.swarm.world.ObservationMessage; // Ostinato: Phase 2c
import baritone.swarm.world.ObservationRecord;
import baritone.swarm.world.ObservationReporter;
import baritone.swarm.world.ObservationVerifier;
import baritone.swarm.world.WorldKnowledge;
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
        /** Ostinato: builds this bot's status sample (perf strip and link monitor). */
        final Status status;

        // Ostinato: immutable loaded roster; restart the link when the file contents change.
        final Path rosterPath;
        final byte[] rosterBytes;
        long nextRosterCheck;
        /**
         * Ostinato: sends one SWL body sealed at HIGH priority, or URGENT for reconcile traffic; null when leases and
         * coordinator are off.
         */
        LeaseSend leaseSend;
        /** Ostinato: Phase 2c - sends one SWO body sealed at the given priority; null when leases and coordinator are off. */
        ObsSend obsSend;
        /** Ostinato: roster members of this bot's groups, without self. */
        Set<String> peers = Collections.emptySet();

        Link(String self, LocalRoutingTransport transport, SwarmControl control, CommandProtocol protocol,
             Status status, Path rosterPath, byte[] rosterBytes) {
            this.self = self;
            this.transport = transport;
            this.control = control;
            this.protocol = protocol;
            this.status = status; // Ostinato:
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
    // Ostinato: Phase 2a leases. Kept across link restarts so a lease outlives a reconnect; null while off.
    private BotLeaseGuard leaseGuard;
    private LeaseCoordinator leaseCoordinator;
    private final LeaseRate leaseRate = new LeaseRate(SwarmBehavior::nowMs);
    // Ostinato: Phase 2c shared world observations. The store lives on the coordinator (memory only, rebuilt from
    // bot reports after a restart); every bot with leases or coordinator on reports what it sees.
    private WorldKnowledge worldKnowledge;
    private final ObservationReporter obsReporter = new ObservationReporter();
    private long nextObsScanAt;
    /** Set by {@code #swarm obs world <tag>}: overrides the server-derived world id (test stand-in for a world change). */
    private volatile String obsWorldOverride;
    private long nextFenceLogAt;
    /** swarm* setting values the link was started with; a change (e.g. from the GUI) restarts it. */
    private String startedWith;
    // Ostinato: perf strip and link monitor. Written on the client thread, read by the command center.
    private final TickRateMeter tickRate = new TickRateMeter();
    private volatile SwarmSample localSample = SwarmSample.UNKNOWN;
    private volatile List<PeerView> peerViews = List.of();
    private long nextLocalSampleMs;

    /** Ostinato: one swarm peer as the link monitor sees it; {@code local} means it is reached over the same-PC spool. */
    public record PeerView(SwarmControl.PeerLink link, boolean local) {}

    public SwarmBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            tickRate.tick(nowMs()); // Ostinato: perf strip tick rate.
        }
        try {
            tick(event);
        } catch (Throwable t) {
            failure = "tick failed: " + t;
            String was = startedWith;
            stop();
            startedWith = was; // so a settings change still restarts the link
        }
        refreshSamples();
    }

    /** Ostinato: once a second, cache this client's figures and the peer list for the command center. */
    private void refreshSamples() {
        long now = nowMs();
        if (now < nextLocalSampleMs) {
            return;
        }
        nextLocalSampleMs = now + 1000L;
        try {
            Link l = link;
            localSample = l == null ? baseSample() : l.status.sample();
            if (l == null) {
                peerViews = List.of();
            } else {
                List<PeerView> views = new ArrayList<>();
                for (SwarmControl.PeerLink p : l.control.peerLinks()) {
                    views.add(new PeerView(p, l.transport.isLocal(p.name())));
                }
                peerViews = List.copyOf(views);
            }
        } catch (RuntimeException e) {
            localSample = SwarmSample.UNKNOWN; // Ostinato: the strip shows n/a rather than a stale figure.
        }
    }

    /** Ostinato: path time, tick rate, ping and RAM of this client; link fields unknown. Client thread only. */
    private SwarmSample baseSample() {
        int ping = -1;
        try {
            LocalPlayer p = ctx.player();
            if (p != null && p.connection != null) {
                net.minecraft.client.multiplayer.PlayerInfo info = p.connection.getPlayerInfo(p.getUUID());
                ping = info == null ? -1 : info.getLatency();
            }
        } catch (RuntimeException ignored) {
            ping = -1;
        }
        Runtime rt = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        long max = rt.maxMemory();
        return new SwarmSample(PlanStats.lastSearchMs.get(), tickRate.tpsX10(nowMs()), ping,
                (int) ((rt.totalMemory() - rt.freeMemory()) / mb), max == Long.MAX_VALUE ? -1 : (int) (max / mb),
                -1, -1, -1, -1, "");
    }

    /** Ostinato: this client's latest figures for the perf strip and link monitor (n/a until the first tick). */
    public SwarmSample localSample() {
        return localSample;
    }

    /** Ostinato: swarm peers with their latest sample and traffic, empty while the link is down. */
    public List<PeerView> peerViews() {
        return peerViews;
    }

    private void tick(TickEvent event) {
        Settings s = Baritone.settings();
        if (event.getType() == TickEvent.Type.IN) {
            leaseTick(s); // Ostinato: the guard ticks with or without a link, so expiry still stops the bot.
        }
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
        logLinkRate(link); // Ostinato:
        coordinatorTick(s); // Ostinato:
        obsTick(s); // Ostinato: Phase 2c
        LocalPlayer player = ctx.player();
        link.transport.flush(nowMs(), line -> send(player, line));
    }

    // ---- Ostinato: Phase 2a leases -------------------------------------------------------------------------

    private CommandProtocol leaseBoundTo;
    private boolean coordinatorNeedsProtocolLogged;

    /** Create, bind and tick the bot-side guard. swarmLeases=false drops it and clears the fence. */
    private void leaseTick(Settings s) {
        if (!s.swarmLeases.value) {
            if (leaseGuard != null) {
                if (leaseBoundTo != null) {
                    leaseBoundTo.setLeaseHooks(null);
                }
                leaseGuard = null;
                leaseBoundTo = null;
                LeaseFence.clear();
            }
            return;
        }
        if (leaseGuard == null) {
            BotLeaseGuard g = new BotLeaseGuard(leaseSender, SwarmBehavior::nowMs, this::logDirect,
                    SwarmBehavior::allowedCoordinator, this::stopLeasedProcess, BotLeaseGuard.LEASED_COMMANDS);
            leaseGuard = g;
            LeaseFence.install(() -> g.invalidActive() && leasedInControl());
            // Ostinato: Phase 2b - actions outside the active leased command's reservation box are denied.
            LeaseFence.installGeometry((kind, x, y, z) -> {
                boolean out = leasedInControl() && g.outsideReservation(kind, dimChar(), x, y, z, isOre(x, y, z));
                if (out) {
                    logFenceDenied(kind, x, y, z); // Ostinato: Phase 2c - the fence is visible in the log
                }
                return out;
            });
            // Ostinato: Phase 2c - the mine heads for the verified vein box the lease holds, even where its own
            // seen-only scan knows no ore there yet. Only the mine asks, and only an active (started) lease answers,
            // so this needs no in-control check (on its first tick the mine is not in control yet).
            LeaseFence.installVein(() -> g.activeVein(dimChar()));
            // Ostinato: Phase 2c - a lease chosen from a shared observation is checked against this bot's own world
            // before it acts; a stale record is reported back as a correction.
            g.setObsCheck(this::checkObservation);
        }
        CommandProtocol p = liveProtocol();
        if (p != null && p != leaseBoundTo) {
            leaseGuard.attach(p);
            p.setLeaseHooks(leaseGuard);
            leaseBoundTo = p;
        }
        leaseGuard.tick();
        logLeaseRate();
    }

    private long nextRateLogAt = Long.MIN_VALUE;
    private long nextLinkLogAt = Long.MIN_VALUE;
    private long[] linkLast; // Ostinato: counters at the previous swarm-link-rate line
    private Link linkLastFor; // a new link starts its counters at zero, so it takes a new baseline

    /**
     * Ostinato: this link's own counters over the last 60 s, once a minute: lines sent, heartbeats sent and skipped
     * (peer had a recent frame), samples sent and withdrawn, lines that expired still queued, and the queue now.
     */
    private void logLinkRate(Link l) {
        long now = nowMs();
        if (nextLinkLogAt != Long.MIN_VALUE && now < nextLinkLogAt) {
            return;
        }
        nextLinkLogAt = now + 60_000L;
        SwarmRateLimiter lim = l.status.limiter;
        long[] c = {lim.sent(), l.control.heartbeatsSent(), l.control.heartbeatsSkipped(), l.control.samplesSent(),
                lim.withdrawn(), lim.expiredQueued()};
        long[] prev = linkLastFor == l ? linkLast : null;
        linkLast = c;
        linkLastFor = l;
        if (prev == null) {
            return; // first call only takes the baseline
        }
        logDirect("swarm-link-rate sent=" + (c[0] - prev[0]) + "/min hb-sent=" + (c[1] - prev[1]) + " hb-skipped="
                + (c[2] - prev[2]) + " samples=" + (c[3] - prev[3]) + " withdrawn=" + (c[4] - prev[4]) + " expired="
                + (c[5] - prev[5]) + " queued=" + lim.queued() + " high=" + lim.queued(SwarmPriority.HIGH));
    }

    /** Ostinato: the live SWL rate, once a minute while any lease traffic is in the window. */
    private void logLeaseRate() {
        long now = nowMs();
        if (nextRateLogAt != Long.MIN_VALUE && now < nextRateLogAt) {
            return;
        }
        nextRateLogAt = now + LeaseRate.WINDOW_MS;
        for (String line : leaseRate.describe()) {
            if (!line.contains(" out=0/min in=0/min")) {
                logDirect(line);
            }
        }
    }

    /** Start (once the link is up) and tick the coordinator. Not the designated one: it refuses and says why. */
    private void coordinatorTick(Settings s) {
        if (!s.swarmCoordinator.value) {
            leaseCoordinator = null;
            worldKnowledge = null; // Ostinato: Phase 2c
            return;
        }
        Link l = link;
        if (l == null) {
            return;
        }
        if (l.protocol == null) {
            if (!coordinatorNeedsProtocolLogged) {
                coordinatorNeedsProtocolLogged = true;
                logDirect("swarm-lease: refused coordinator=" + l.self + " reason=swarmCommandProtocol is false");
            }
            return;
        }
        if (leaseCoordinator == null || !leaseCoordinator.self().equalsIgnoreCase(l.self)) {
            Path state = ctx.minecraft().gameDirectory.toPath().resolve("ostinato").resolve("swarm")
                    .resolve("coordinator.state");
            leaseCoordinator = new LeaseCoordinator(l.self, SwarmBehavior::allowedCoordinator,
                    new CoordinatorStore(state), leaseSender,
                    new LeaseCoordinator.Commands() {
                        @Override
                        public String submitLeased(String bot, String line, UUID id) {
                            CommandProtocol p = liveProtocol();
                            return p == null ? "swarm: link is not running" : p.submitLeased(bot, line, id);
                        }

                        @Override
                        public String refusal(String bot, String line) {
                            CommandProtocol p = liveProtocol();
                            return p == null ? "link is not running" : p.leaseRefusal(bot, line);
                        }

                        @Override
                        public void fenced(UUID id) {
                            // Ostinato: the old holder's authorization ended, so its unknown mine stops blocking.
                            CommandProtocol p = liveProtocol();
                            if (p != null) {
                                p.fenced(id);
                            }
                        }
                    },
                    SwarmBehavior::nowMs, System::currentTimeMillis, this::logDirect,
                    () -> Baritone.settings().swarmLeaseSec.value * 1000L,
                    Baritone.settings().swarmLeaseMarginMs.value, LeaseCoordinator.DEFAULT_SKEW_MS,
                    name -> {
                        Link cur = link;
                        return cur == null || !cur.transport.isLocal(name);
                    },
                    () -> {
                        Link cur = link;
                        return cur == null ? Collections.<String>emptySet() : cur.peers;
                    });
            // Ostinato: Phase 2c - a new coordinator starts with an empty store that rebuilds from bot reports.
            WorldKnowledge wk = new WorldKnowledge(SwarmBehavior::nowMs, this::logDirect);
            worldKnowledge = wk;
            leaseCoordinator.setObsReportListener((res, reason, bot) -> wk.leaseReport(res, reason, bot));
        }
        if (leaseCoordinator.mode() == LeaseCoordinator.Mode.IDLE) {
            if (!l.protocol.linkUp()) {
                return;
            }
            leaseCoordinator.start();
            obsRebuild(l); // Ostinato: Phase 2c
        }
        leaseCoordinator.tick();
        WorldKnowledge wk = worldKnowledge;
        if (wk != null) {
            wk.tick(); // Ostinato: Phase 2c rebuild timeout and entity expiry
        }
        logLeaseRate();
    }

    /** swarmCoordinatorId, else agentOwner. Bots take coordinator ops only from this member. */
    private static String allowedCoordinator() {
        Settings s = Baritone.settings();
        String c = s.swarmCoordinatorId.value == null ? "" : s.swarmCoordinatorId.value.trim();
        if (!c.isEmpty()) {
            return c;
        }
        return s.agentOwner.value == null ? "" : s.agentOwner.value.trim();
    }

    /** Ostinato: one sealed SWL send; {@code urgent} picks {@link SwarmPriority#URGENT} over HIGH. */
    interface LeaseSend {
        boolean send(String to, String body, boolean urgent);
    }

    // ---- Ostinato: Phase 2c shared world observations ------------------------------------------------------

    /** Ostinato: Phase 2c. One sealed SWO send at the given priority. */
    interface ObsSend {
        boolean send(String to, String body, SwarmPriority priority);
    }

    /**
     * Ostinato: Phase 2c. The id observations are bound to: the {@code #swarm obs world} override, else "sp" in
     * single player, else a short hash of the server address (the address itself never leaves the bot).
     */
    private String obsWorldId() {
        String o = obsWorldOverride;
        if (o != null) {
            return o;
        }
        if (ctx.minecraft().hasSingleplayerServer()) {
            return "sp";
        }
        net.minecraft.client.multiplayer.ServerData sd = ctx.minecraft().getCurrentServer();
        String ip = sd == null || sd.ip == null ? "" : sd.ip.trim().toLowerCase(java.util.Locale.ROOT);
        if (ip.isEmpty()) {
            return "unknown";
        }
        long h = 1125899906842597L; // stable across runs and PCs, unlike String.hashCode salt-free but short
        for (int i = 0; i < ip.length(); i++) {
            h = 31 * h + ip.charAt(i);
        }
        String b = Long.toString(h & 0x7fffffffffffffffL, 36);
        return "s" + b.substring(0, Math.min(6, b.length()));
    }

    /** True when this bot is the designated coordinator and runs one. */
    private boolean selfIsCoordinator(Link l) {
        return worldKnowledge != null && l.self.equalsIgnoreCase(allowedCoordinator());
    }

    /**
     * Ostinato: Phase 2c. The coordinator (re)binds its store to the current world, asks every bot for its
     * observations (one Q broadcast) and refuses picks until each live peer answered with E or 25 s passed.
     */
    private void obsRebuild(Link l) {
        WorldKnowledge wk = worldKnowledge;
        LeaseCoordinator c = leaseCoordinator;
        if (wk == null || l.obsSend == null || (c != null && c.mode() == LeaseCoordinator.Mode.REFUSED)) {
            return;
        }
        String world = obsWorldId();
        wk.setWorld(world);
        Set<String> online = new HashSet<>();
        for (String n : onlinePlayerNames()) {
            online.add(n.toLowerCase(java.util.Locale.ROOT));
        }
        List<String> live = new ArrayList<>();
        for (String p : l.peers) {
            if (online.contains(p.toLowerCase(java.util.Locale.ROOT))) {
                live.add(p);
            }
        }
        l.obsSend.send("*", new ObservationMessage(ObservationMessage.Op.Q, world, new ArrayList<>()).encode(),
                SwarmPriority.HIGH);
        wk.startRebuild(live);
        obsReporter.onQuery(true, nowMs()); // the coordinator's own records go straight into the store
    }

    /** Ostinato: Phase 2c. Scan, report and (on the coordinator) follow world changes. */
    private void obsTick(Settings s) {
        Link l = link;
        if (l == null || l.obsSend == null || ctx.player() == null || ctx.minecraft().level == null) {
            return;
        }
        long now = nowMs();
        String world = obsWorldId();
        if (obsReporter.setWorld(world)) {
            logDirect("swarm-obs: reporting for world " + world);
            nextObsScanAt = now;
        }
        boolean coordinator = selfIsCoordinator(l);
        WorldKnowledge wk = worldKnowledge;
        if (coordinator && wk.rebuildAsked() && !world.equals(wk.world())) {
            obsRebuild(l); // world or session changed under the coordinator: drop everything and ask again
        }
        if (now >= nextObsScanAt) {
            nextObsScanAt = now + 5_000L;
            net.minecraft.core.BlockPos p = ctx.player().blockPosition();
            ObservationReporter.Scan scan = ObservationReporter.scan(reservationWorld(), dimChar(), p.getX(),
                    p.getY(), p.getZ(), 16, 8);
            obsReporter.observe(scan.seen, scan.box, now);
        }
        if (coordinator) {
            if (!wk.rebuildAsked()) {
                return; // not started yet: keep the reports until the store is bound
            }
            obsReporter.tick(now, true, m -> {
                wk.apply(l.self, m);
                return true;
            });
            return;
        }
        String to = allowedCoordinator();
        if (to.isEmpty()) {
            return;
        }
        boolean spool = l.transport.isLocal(to);
        obsReporter.tick(now, spool, m -> l.obsSend.send(to, m.encode(),
                m.op == ObservationMessage.Op.G ? SwarmPriority.HIGH : SwarmPriority.NORMAL));
    }

    /** Ostinato: Phase 2c. SWO inbound, on the tick thread. */
    private void onObsMessage(SwarmMessage msg) {
        ObservationMessage m = ObservationMessage.decode(msg.body());
        if (m == null) {
            return;
        }
        if (m.op == ObservationMessage.Op.Q) {
            if (!msg.from().equalsIgnoreCase(allowedCoordinator())) {
                return; // only the designated coordinator may ask for a dump
            }
            String mine = obsReporter.world();
            if (mine == null || !mine.equals(m.worldId)) {
                logDirect("swarm-obs: rebuild from " + msg.from() + " for world " + m.worldId + " but this bot is in "
                        + mine + "; not answering");
                return;
            }
            Link l = link;
            obsReporter.onQuery(l != null && l.transport.isLocal(msg.from()), nowMs());
            logDirect("swarm-obs: rebuild asked by " + msg.from() + ", dumping " + obsReporter.reportedSize()
                    + " records");
            return;
        }
        WorldKnowledge wk = worldKnowledge;
        if (wk != null) {
            wk.apply(msg.from(), m);
        }
    }

    /** Ostinato: Phase 2c. The bot-side check before acting on a lease chosen from a shared observation. */
    private String checkObservation(String resource, String expect) {
        ObservationVerifier.Result r = ObservationVerifier.verify(resource, expect, dimChar(), reservationWorld());
        if (r.state == ObservationVerifier.State.CONFIRMED) {
            logDirect("swarm-obs: verified " + resource + " (" + r.why + ")");
            return null;
        }
        if (r.state == ObservationVerifier.State.STALE) {
            obsReporter.correction(resource, r.key, r.detail, nowMs());
            logDirect("swarm-obs: stale " + resource + " (" + r.why + "); reporting the correction");
            return BotLeaseGuard.OBS_STALE;
        }
        logDirect("swarm-obs: cannot verify " + resource + " (" + r.why + ")");
        return BotLeaseGuard.OBS_UNKNOWN;
    }

    /** Ostinato: Phase 2c. One log line per 2 s when the reservation fence denies an action. */
    private void logFenceDenied(LeaseFence.Kind kind, int x, int y, int z) {
        long now = nowMs();
        if (now < nextFenceLogAt) {
            return;
        }
        nextFenceLogAt = now + 2_000L;
        logDirect("swarm-lease: fence denied " + kind + " at " + x + "," + y + "," + z + " outside reservation"
                + " (fenceDenials=" + (LeaseFence.denials() + 1) + ")");
    }

    // Ostinato: one Sender for guard and coordinator, so reconcile replies reach the URGENT queue.
    private final BotLeaseGuard.Sender leaseSender = new BotLeaseGuard.Sender() {
        @Override
        public boolean send(String to, String body) {
            return sendLease(to, body, false);
        }

        @Override
        public boolean send(String to, String body, boolean urgent) {
            return sendLease(to, body, urgent);
        }
    };

    private boolean sendLease(String to, String body, boolean urgent) {
        Link l = link;
        if (l == null || l.leaseSend == null) {
            return false;
        }
        boolean ok = l.leaseSend.send(to, body, urgent);
        if (ok) {
            LeaseMessage sentMsg = LeaseMessage.parse(body); // Ostinato: per-op counts, log-only
            leaseRate.sent(to, sentMsg == null ? null : sentMsg.op);
        }
        return ok;
    }

    /** SWL inbound, on the tick thread (SwarmControl.tick). */
    private void onLeaseMessage(SwarmMessage msg) {
        LeaseMessage m = LeaseMessage.parse(msg.body());
        if (m == null) {
            return;
        }
        leaseRate.received(msg.from(), m.op); // Ostinato: per-op counts
        BotLeaseGuard g = leaseGuard;
        if (g != null && m.op.fromCoordinator()) {
            g.onMessage(msg.from(), m);
        }
        LeaseCoordinator c = leaseCoordinator;
        if (c != null) {
            c.onMessage(msg.from(), m); // Bot ops; a coordinator op from a rival is counted there.
        }
    }

    private List<IBaritoneProcess> leasedProcesses() {
        return List.of(baritone.getMineProcess(), baritone.getTunnelProcess(), baritone.getBuilderProcess(),
                baritone.getCustomGoalProcess());
    }

    /** True while one of the leased processes (mine, tunnel, builder, custom goal) is the one in control. */
    /** Ostinato: Phase 2b reservation dimension letter: o, n or e. */
    private char dimChar() {
        net.minecraft.client.multiplayer.ClientLevel w = ctx.minecraft().level;
        if (w == null) {
            return '?';
        }
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> d = w.dimension();
        return d == net.minecraft.world.level.Level.NETHER ? 'n' : d == net.minecraft.world.level.Level.END ? 'e' : 'o';
    }

    /** Ostinato: Phase 2b. Ores (and ancient debris) are what a vein reservation limits. */
    private boolean isOre(int x, int y, int z) {
        net.minecraft.client.multiplayer.ClientLevel w = ctx.minecraft().level;
        if (w == null) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState st = w.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
        return st.is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)
                || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("_ore");
    }

    private boolean leasedInControl() {
        try {
            Optional<IBaritoneProcess> p = baritone.getPathingControlManager().mostRecentInControl();
            return p.isPresent() && leasedProcesses().contains(p.get());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Stop only the leased processes, never survival and never cancelEverything. True once none of them is active
     * or in control; the guard calls again each tick until then.
     */
    private boolean stopLeasedProcess(UUID commandId) {
        try {
            for (IBaritoneProcess p : leasedProcesses()) {
                if (p.isActive()) {
                    p.onLostControl();
                }
            }
            for (IBaritoneProcess p : leasedProcesses()) {
                if (p.isActive()) {
                    return false;
                }
            }
            return !leasedInControl();
        } catch (RuntimeException e) {
            return false;
        }
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
        final boolean leasesOn = s.swarmLeases.value; // Ostinato:
        final boolean coordinatorOn = s.swarmCoordinator.value; // Ostinato:
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
                limiter.setLog(this::logDirect); // Ostinato: "expired still queued" lines
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
                String command = "";
                for (SwarmRoster.Group g : roster.groupsOf(self)) {
                    command = SwarmSample.commandOf(chat.templateFor(chat.channelFor(g.id())));
                    break; // Ostinato: the monitor shows the first group's command.
                }
                Status status = new Status(limiter, chat, command);
                SwarmControl control = new SwarmControl(roster, endpoint, status, System::currentTimeMillis,
                        this::logDirect, () -> "queued " + limiter.queued() + ", sent " + limiter.sent() + ", dropped "
                        + limiter.dropped() + ", too long " + transport.tooLong() + String.format(", rate %.2f/s burst %d",
                        limiter.rate(), limiter.burst()));
                control.enableBuild(new Regions());
                // Ostinato: status samples go at TELEMETRY, only while nothing URGENT/HIGH waits and the link is under
                // half its cap; the limiter also takes queued ones back the moment that stops holding.
                control.setSampling(s.swarmStatusSample.value, () -> limiter.telemetryRoom(nowMs()));
                // Ostinato: a peer that got any line inside the heartbeat interval needs no heartbeat.
                control.setRecentlySent((peer, window) -> transport.sentWithin(peer, nowMs(), window));
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
                                    return sendSealed(endpoint, roster, self, to, type, body, priority, null);
                                }

                                @Override
                                public boolean send(String to, String type, String body, SwarmPriority priority,
                                                    SwarmRateLimiter.Deadline deadline) {
                                    return sendSealed(endpoint, roster, self, to, type, body, priority, deadline);
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
                    // Ostinato: advertise the lease cap only while swarmLeases is on.
                    created.setLocalCapabilities(leasesOn ? CapabilityDescriptor.of("navigation", "mining",
                            "building", "combat", "inventory", "lease") : CapabilityDescriptor.gameplay());
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
                    status.protocol = created; // Ostinato: retried count for the link monitor.
                }
                Link made = new Link(self, transport, control, protocol, status, rp, rosterBytes); // Ostinato:
                // Ostinato: Phase 2a SWL rides the same sealed link at HIGH priority. Off, nothing registers.
                if (leasesOn || coordinatorOn) {
                    Set<String> peers = new HashSet<>();
                    for (SwarmRoster.Group g : roster.groupsOf(self)) {
                        peers.addAll(g.members());
                    }
                    peers.removeIf(m -> m.equalsIgnoreCase(self));
                    made.peers = Collections.unmodifiableSet(peers);
                    made.leaseSend = (to, body, urgent) -> sendSealed(endpoint, roster, self, to, LeaseMessage.TYPE,
                            body, urgent ? SwarmPriority.URGENT : SwarmPriority.HIGH);
                    control.registerHandler(LeaseMessage.TYPE, this::onLeaseMessage);
                    // Ostinato: Phase 2c - SWO observation reports (spool when the coordinator is local).
                    made.obsSend = (to, body, priority) -> sendSealed(endpoint, roster, self, to,
                            ObservationMessage.TYPE, body, priority);
                    control.registerHandler(ObservationMessage.TYPE, this::onObsMessage);
                }
                return made;
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
        return sendSealed(endpoint, roster, self, to, type, body, priority, null);
    }

    private static boolean sendSealed(SwarmEndpoint endpoint, SwarmRoster roster, String self, String to, String type,
                                      String body, SwarmPriority priority,
                                      SwarmRateLimiter.Deadline deadline) { // Ostinato:
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
            endpoint.send(group, dest, type, body, priority, deadline); // Ostinato: cancels overtake retries.
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
        // Ostinato: read-only protocol lines. Building this list does not touch execution.
        List<String> all = new ArrayList<>(base);
        if (p != null) {
            all.addAll(p.statusLines());
        }
        all.addAll(leaseStatusLines());
        return all;
    }

    /** Ostinato: lease guard, coordinator and SWL rate lines; empty while both settings are off. */
    public List<String> leaseStatusLines() {
        List<String> out = new ArrayList<>();
        BotLeaseGuard g = leaseGuard;
        if (g != null) {
            out.add("swarm-lease guard: coordinator=" + allowedCoordinator() + " ignored=" + g.ignoredCount()
                    + " grantRejects=" + g.grantRejectCount() + " fenceDenials=" + LeaseFence.denials()
                    + " geometryDenials=" + LeaseFence.geometryDenials()); // Ostinato: Phase 2c
            for (String line : g.describe()) {
                out.add("  " + line);
            }
        }
        LeaseCoordinator c = leaseCoordinator;
        if (c != null) {
            out.addAll(c.describe());
        }
        if (g != null || c != null) {
            out.addAll(leaseRate.describe());
        }
        return out;
    }

    /** Ostinato: {@code #swarm lease <bot> <resource> <command...>}. Only on the designated coordinator. */
    public String leaseOffer(String bot, String resource, String commandLine) {
        LeaseCoordinator c = leaseCoordinator;
        if (c == null) {
            return "swarm: swarmCoordinator is false (or the link is not up yet)";
        }
        WorldKnowledge wk = worldKnowledge;
        // Ostinato: Phase 2c - obs:<ore> picks the best shared vein observation; the bot verifies it before acting.
        if (resource.startsWith("obs:")) {
            String ore = resource.substring(4).trim().toLowerCase(java.util.Locale.ROOT);
            if (wk == null || ore.isEmpty()) {
                return "swarm-lease: not offering " + resource + ": no observation store";
            }
            WorldKnowledge.Pick p = wk.pick(ore, dimChar(), key -> c.busyReason(key) != null);
            if (p.record == null) {
                return "swarm-lease: not offering " + resource + ": " + p.reason;
            }
            logDirect("swarm-obs: " + resource + " picked " + p.record);
            return c.lease(bot, p.record.key, commandLine, p.record.detail);
        }
        // Ostinato: Phase 2b - vein:/chest:/region: shorthand becomes a canonical reservation key here.
        ReservationResolver.Result r =
                ReservationResolver.resolve(resource, dimChar(), reservationWorld());
        if (r.error != null) {
            // Ostinato: Phase 2c - the coordinator cannot see the vein, but a bot reported one covering it.
            int[] at = r.error.contains("unloaded") ? veinSeed(resource) : null;
            ObservationRecord rec = at == null || wk == null ? null : wk.covering(dimChar(), at[0], at[1], at[2]);
            if (rec != null) {
                logDirect("swarm-obs: " + resource + " is not loaded here; using " + rec);
                return c.lease(bot, rec.key, commandLine, rec.detail);
            }
            return "swarm-lease: not offering " + resource + ": " + r.error;
        }
        return c.lease(bot, r.key, commandLine);
    }

    /** Ostinato: Phase 2c. The x,y,z of a {@code vein:x,y,z} shorthand, else null. */
    private static int[] veinSeed(String resource) {
        if (!resource.startsWith("vein:")) {
            return null;
        }
        String[] f = resource.substring(5).split(",", -1);
        if (f.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Ostinato: Phase 2c. {@code #swarm obs status}. */
    public List<String> obsStatusLines() {
        List<String> out = new ArrayList<>();
        ObservationReporter rep = obsReporter;
        out.add("swarm-obs reporter: world=" + rep.world() + " reported=" + rep.reportedSize() + " pending="
                + rep.pending() + " sent=" + rep.sentMessages() + "msg/" + rep.sentEntries() + "entries corrections="
                + rep.corrections() + " queries=" + rep.queries() + " fenceDenials=" + LeaseFence.denials()
                + " geometryDenials=" + LeaseFence.geometryDenials());
        WorldKnowledge wk = worldKnowledge;
        if (wk != null) {
            out.addAll(wk.status(8));
        }
        return out;
    }

    /** Ostinato: Phase 2c. {@code #swarm obs see|waypoint|structure}: report one observation by hand. */
    public String obsSee(String key, String detail, int confidence, long ageMs) {
        if (!ObservationMessage.validKey(key)) {
            return "swarm-obs: bad key " + key;
        }
        if (detail == null || !detail.matches("[A-Za-z0-9_:.,@-]{1,48}")) {
            return "swarm-obs: bad detail " + detail;
        }
        if (obsReporter.world() == null) {
            return "swarm-obs: not reporting yet (link down or not in a world)";
        }
        obsReporter.manual(key, detail, confidence, ageMs, nowMs());
        return "swarm-obs: queued " + key + " " + detail + " conf=" + confidence + " age=" + ageMs + "ms";
    }

    /** Ostinato: Phase 2c. {@code #swarm obs world <tag|auto>}: test equivalent of a world/session change. */
    public String obsWorld(String tag) {
        if ("auto".equalsIgnoreCase(tag)) {
            obsWorldOverride = null;
            return "swarm-obs: world id back to auto";
        }
        if (!ObservationMessage.validWorld(tag)) {
            return "swarm-obs: bad world tag " + tag;
        }
        obsWorldOverride = tag;
        return "swarm-obs: world id set to " + tag;
    }

    /** Ostinato: the coordinator's loaded world, as the reservation resolver sees it. */
    private ReservationResolver.World reservationWorld() {
        return new ReservationResolver.World() {
            @Override
            public String block(int x, int y, int z) {
                net.minecraft.client.multiplayer.ClientLevel w = ctx.minecraft().level;
                net.minecraft.core.BlockPos p = new net.minecraft.core.BlockPos(x, y, z);
                if (w == null || !w.hasChunkAt(p)) {
                    return null;
                }
                return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(w.getBlockState(p).getBlock())
                        .toString();
            }

            @Override
            public int[] otherChestHalf(int x, int y, int z) {
                net.minecraft.client.multiplayer.ClientLevel w = ctx.minecraft().level;
                if (w == null) {
                    return null;
                }
                net.minecraft.core.BlockPos p = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState st = w.getBlockState(p);
                if (!(st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
                        || st.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                        == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                    return null;
                }
                net.minecraft.core.BlockPos o = p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(st));
                return new int[]{o.getX(), o.getY(), o.getZ()};
            }
        };
    }

    /** Ostinato: {@code #swarm lease revoke <resource|leaseId>}. */
    public String leaseRevoke(String resourceOrId) {
        LeaseCoordinator c = leaseCoordinator;
        if (c == null) {
            return "swarm: swarmCoordinator is false (or the link is not up yet)";
        }
        return c.revoke(resourceOrId);
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
        private final SwarmRateLimiter limiter;
        private final ChatSwarmTransport chat;
        private final String command;
        volatile CommandProtocol protocol;

        Status(SwarmRateLimiter limiter, ChatSwarmTransport chat, String command) {
            this.limiter = limiter;
            this.chat = chat;
            this.command = command;
        }

        /** Ostinato: client figures plus this link's chat budget, losses and retries. Client thread. */
        @Override
        public SwarmSample sample() {
            CommandProtocol p = protocol;
            return baseSample().withLink(limiter.sentInWindow(nowMs()), limiter.windowCap(),
                    limiter.dropped() + chat.tooLong() + chat.inboundDropped(), p == null ? -1 : p.retried(), command);
        }

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
