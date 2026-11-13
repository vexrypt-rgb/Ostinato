/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.swarm;

import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.protocol.CommandProtocol;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.WindowCounter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The minimal control surface over a {@link SwarmEndpoint}: ping/pong and link status. No game
 * types here; the game side supplies {@link LocalStatus} and drives {@link #tick()}.
 *
 * <p>Peers are identified by the sender inside the sealed, authenticated envelope
 * ({@link SwarmMessage#from()}), never by the chat wrapper or a world lookup.
 */
public final class SwarmControl {

    public static final String PING = "PING";
    public static final String PONG = "PONG";
    /** Ostinato: a {@link SwarmSample} broadcast. No reply; members that do not know the type ignore it. */
    public static final String STS = "STS";
    /** Ostinato: sample period when the command protocol is off. With it on, every third heartbeat is the sample. */
    public static final long SAMPLE_INTERVAL_MS = 30_000L;
    /** Ostinato: window the per-member traffic count covers. */
    public static final long TRAFFIC_WINDOW_MS = 60_000L;
    /** Pings older than this no longer match a pong for RTT. */
    public static final long PING_TTL_MS = 120_000L;
    static final int MAX_FIELD = 40;

    /** What this member reports in a pong. */
    public interface LocalStatus {
        String name();

        /** e.g. {@code "12,64,-3"}, or {@code "?"} when not in a world. */
        String position();

        /** Current Baritone process, or {@code "idle"}. */
        String process();

        /** Ostinato: figures for the performance strip and link monitor; unknown unless the host supplies them. */
        default SwarmSample sample() {
            return SwarmSample.UNKNOWN;
        }
    }

    /** Ostinato: a copy of one member's last sample and traffic, safe to hand to another thread. */
    public record PeerLink(String name, long lastSeenMs, SwarmSample sample, long sampleAtMs, int receivedInWindow,
                           long received) {
    }

    /** What we know about one other member. */
    public static final class Peer {
        final String name;
        long lastSeenMs = -1;
        String lastGroup;
        String pos;
        String proc;
        long rttMs = -1;
        long pongAtMs = -1;
        SwarmSample sample = SwarmSample.UNKNOWN; // Ostinato:
        long sampleAtMs = -1; // Ostinato:
        final WindowCounter traffic = new WindowCounter(TRAFFIC_WINDOW_MS, 1024); // Ostinato:

        Peer(String name) {
            this.name = name;
        }

        public String name() { return name; }
        /** Last authenticated message from this member (clock ms), or -1. */
        public long lastSeenMs() { return lastSeenMs; }
        public String pos() { return pos; }
        public String proc() { return proc; }
        public long rttMs() { return rttMs; }
        public long pongAtMs() { return pongAtMs; }
        /** Ostinato: last {@code STS} sample, or {@link SwarmSample#UNKNOWN} (an older bot, or none yet). */
        public SwarmSample sample() { return sample; }
        public long sampleAtMs() { return sampleAtMs; }
    }

    private final SwarmRoster roster;
    private final SwarmEndpoint endpoint;
    private final LocalStatus local;
    private final LongSupplier clockMs;
    private final Consumer<String> log;
    private final Supplier<String> linkSummary;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<Long, Long> pingsSent = new HashMap<>();
    private SwarmBuild build;
    /** Ostinato: Phase 1 command protocol. Null when {@code swarmCommandProtocol} is off. */
    private CommandProtocol commandProtocol;
    public static final long PEER_TIMEOUT_MS = 30_000L; // Ostinato: bounded crash detection.
    private long nextHeartbeatMs; // Ostinato:
    /** Ostinato: heartbeat period while the send queue is empty. */
    public static final long HEARTBEAT_MS = 10_000L;
    /** Ostinato: longest gap between heartbeats while the queue is busy; under PEER_TIMEOUT_MS with room for one loss. */
    public static final long BUSY_HEARTBEAT_MS = 20_000L;
    // Ostinato: whether any line left for a peer inside a window; such a peer needs no heartbeat.
    private BiPredicate<String, Long> recentlySent = (peer, windowMs) -> false;
    // Ostinato: when a heartbeat was last queued for each peer (lower case), so a slow queue gets no second one.
    private final Map<String, Long> lastHbQueued = new HashMap<>();
    // Ostinato: when each peer last came due under the plain schedule, for the sent/skipped counts only.
    private final Map<String, Long> lastHbDue = new HashMap<>();
    private long heartbeatsSent; // Ostinato: heartbeat lines queued (a broadcast counts once)
    private long heartbeatsSkipped; // Ostinato: peer heartbeats left out because the peer had a recent frame
    private long nextSampleMs; // Ostinato: sample timer, its own since heartbeats no longer carry it.
    private boolean sampling; // Ostinato: off until the host turns it on (setSampling).
    private BooleanSupplier linkQuiet = () -> true; // Ostinato:
    private long samplesSent; // Ostinato:
    private long ignored;
    private long sendFailures;
    private final Map<String, Consumer<SwarmMessage>> extraHandlers = new HashMap<>();

    /**
     * @param linkSummary one line describing the transport (queue, drops), or {@code null}
     */
    public SwarmControl(SwarmRoster roster, SwarmEndpoint endpoint, LocalStatus local, LongSupplier clockMs,
                        Consumer<String> log, Supplier<String> linkSummary) {
        this.roster = roster;
        this.endpoint = endpoint;
        this.local = local;
        this.clockMs = clockMs;
        this.log = log;
        this.linkSummary = linkSummary;
    }

    public SwarmEndpoint endpoint() { return endpoint; }

    public SwarmRoster roster() { return roster; }

    /**
     * Route messages of {@code type} to {@code handler} after authentication and replay checks. Lets a host
     * (for example TenorClef objectives) add its own sealed message types without a second transport. Built-in
     * types ({@code PING}, {@code PONG}, build messages) cannot be overridden.
     */
    public synchronized void registerHandler(String type, Consumer<SwarmMessage> handler) {
        if (PING.equals(type) || PONG.equals(type) || SwarmBuild.BUILD.equals(type)
                || SwarmBuild.STAT.equals(type) || SwarmBuild.STOP.equals(type)
                || CommandProtocol.TYPE_CMD.equals(type) || CommandProtocol.TYPE_ACK.equals(type)
                || CommandProtocol.TYPE_HELLO.equals(type) || STS.equals(type)) {
            throw new IllegalArgumentException("reserved swarm message type " + type);
        }
        extraHandlers.put(type, handler);
    }

    /** Enable coordinated region builds, handing orders to {@code builder}. */
    public synchronized SwarmBuild enableBuild(SwarmBuild.RegionBuilder builder) {
        build = new SwarmBuild(roster, endpoint, builder, clockMs, log);
        return build;
    }

    /** Region build coordinator, or {@code null} if not enabled. */
    public SwarmBuild build() { return build; }

    /** Ostinato: attach the Phase 1 command protocol. Null detaches it. */
    public synchronized void setCommandProtocol(CommandProtocol protocol) {
        this.commandProtocol = protocol;
    }

    /** Ostinato: the command protocol, or null when the setting is off. */
    public CommandProtocol commandProtocol() { return commandProtocol; }

    /** Frames of a type this bot does not handle. Phase 1 frames count here while the protocol is off. */
    public synchronized long ignoredCount() { return ignored; }

    /** Number of roster groups this member is in. */
    public int groupCount() {
        return roster.groupsOf(endpoint.selfId()).size();
    }

    /** Ids of the roster groups this member is in (for tab completion). */
    public List<String> myGroups() {
        List<String> out = new ArrayList<>();
        for (SwarmRoster.Group g : roster.groupsOf(endpoint.selfId())) {
            out.add(g.id());
        }
        return out;
    }

    /**
     * Ping every other member of {@code group}, or of each of our groups if {@code null}.
     *
     * @return number of groups pinged
     * @throws IllegalArgumentException if we are not a member of {@code group}
     */
    public synchronized int ping(String group) {
        List<String> targets = new ArrayList<>();
        if (group == null) {
            for (SwarmRoster.Group g : roster.groupsOf(endpoint.selfId())) {
                targets.add(g.id());
            }
        } else {
            if (!roster.isMember(group, endpoint.selfId())) {
                throw new IllegalArgumentException("you are not in group " + group);
            }
            targets.add(group);
        }
        long now = clockMs.getAsLong();
        prunePings(now);
        int n = 0;
        for (String g : targets) {
            try {
                long id = endpoint.send(g, SwarmFrame.BROADCAST, PING, "ping", SwarmPriority.NORMAL);
                pingsSent.put(id, now);
                n++;
            } catch (Exception e) {
                sendFailures++;
                log.accept("swarm: ping to " + g + " failed: " + e.getMessage());
            }
        }
        return n;
    }

    /** Poll the endpoint and handle what arrived. Never throws. */
    public synchronized void tick() {
        List<SwarmMessage> msgs;
        try {
            msgs = endpoint.poll();
        } catch (Exception e) {
            log.accept("swarm: receive failed: " + e.getMessage());
            return;
        }
        for (SwarmMessage m : msgs) {
            handle(m);
        }
        if (build != null) {
            build.tick();
        }
        // Ostinato: retry unacked commands and refresh verified outcomes. No effect when the protocol is off.
        CommandProtocol protocol = commandProtocol;
        if (protocol != null) {
            try {
                long now = clockMs.getAsLong(); // Ostinato:
                if (protocol.linkUp() && now >= nextHeartbeatMs) { // Ostinato:
                    nextHeartbeatMs = now + 1_000L; // Ostinato: each peer is checked once a second
                    heartbeat(now);
                }
                if (sampling && protocol.linkUp()) {
                    sample(now); // Ostinato: own timer; no longer every third heartbeat
                }
                for (Peer peer : peers.values()) { // Ostinato:
                    if (peer.lastSeenMs >= 0 && now - peer.lastSeenMs >= PEER_TIMEOUT_MS) { // Ostinato:
                        protocol.onPeerDisconnect(peer.name); // Ostinato:
                    } // Ostinato:
                } // Ostinato:
                protocol.pump();
                protocol.pollExecutions();
            } catch (RuntimeException e) {
                log.accept("swarm: command protocol tick failed: " + e.getMessage());
            }
        } else if (sampling) {
            sample(clockMs.getAsLong());
        }
    }

    /**
     * Ostinato: any frame a peer gets is proof we are alive, so a heartbeat goes only to a peer that got nothing from
     * us in the last interval (HEARTBEAT_MS; BUSY_HEARTBEAT_MS while the link is busy, which is still inside
     * PEER_TIMEOUT_MS with room for one loss). All due: one broadcast. Some: one directed heartbeat each. None:
     * nothing. Before this, an idle link carried a heartbeat every 10 s on top of every lease message.
     */
    private void heartbeat(long now) {
        long need = linkQuiet.getAsBoolean() ? HEARTBEAT_MS : BUSY_HEARTBEAT_MS;
        String self = endpoint.selfId();
        Map<String, Boolean> seen = new HashMap<>();
        for (SwarmRoster.Group group : roster.groupsOf(self)) {
            List<String> due = new ArrayList<>();
            int others = 0;
            for (String member : group.members()) {
                if (member.equalsIgnoreCase(self)) {
                    continue;
                }
                others++;
                String k = member.toLowerCase(Locale.ROOT);
                if (seen.containsKey(k)) { // in an earlier group too: that group's heartbeat covers it
                    continue;
                }
                boolean recent = recentlySent.test(member, need);
                Long queuedAt = lastHbQueued.get(k);
                boolean waiting = queuedAt != null && now - queuedAt < need;
                Long dueAt = lastHbDue.get(k);
                if (dueAt == null || now - dueAt >= need) { // the plain schedule would have sent one now
                    lastHbDue.put(k, now);
                    if (recent) heartbeatsSkipped++;
                }
                boolean send = !recent && !waiting;
                seen.put(k, send);
                if (send) {
                    due.add(member);
                }
            }
            if (due.isEmpty()) {
                continue;
            }
            List<String> to = due.size() == others ? List.of(SwarmFrame.BROADCAST) : due;
            for (String dest : to) {
                try {
                    endpoint.send(group.id(), dest, PING, "hb", SwarmPriority.NORMAL);
                    heartbeatsSent++;
                } catch (Exception e) {
                    sendFailures++;
                }
            }
            for (String member : due) {
                lastHbQueued.put(member.toLowerCase(Locale.ROOT), now);
            }
        }
    }

    /**
     * Ostinato: the status sample goes out on its own timer at TELEMETRY, the lowest priority. It is not even queued
     * while anything URGENT or HIGH waits or the link is past half its cap ({@code linkQuiet}), and the rate limiter
     * takes back a queued one the moment either becomes true, so it never delays a real message.
     */
    private void sample(long now) {
        if (now < nextSampleMs) {
            return;
        }
        if (!linkQuiet.getAsBoolean()) {
            nextSampleMs = now + 5_000L;
            return;
        }
        nextSampleMs = now + SAMPLE_INTERVAL_MS;
        String body = null;
        for (SwarmRoster.Group group : roster.groupsOf(endpoint.selfId())) {
            if (group.members().size() < 2) {
                continue;
            }
            try {
                if (body == null) {
                    body = sampleBody();
                }
                endpoint.send(group.id(), SwarmFrame.BROADCAST, STS, body, SwarmPriority.TELEMETRY);
                samplesSent++;
            } catch (Exception e) {
                sendFailures++;
            }
        }
    }

    /** Ostinato: the local sample, encoded; a host failure sends an unknown sample rather than nothing. */
    private String sampleBody() {
        try {
            SwarmSample s = local.sample();
            String body = s == null ? "" : s.encode();
            return body.isEmpty() ? "-" : body; // never an empty frame body
        } catch (RuntimeException e) {
            return "-";
        }
    }

    /**
     * Ostinato: turn the {@code STS} sample on or off. {@code quiet} says whether telemetry may use the link (nothing
     * URGENT or HIGH waits and it is under half its cap); while false, no sample is queued and heartbeats stretch to
     * BUSY_HEARTBEAT_MS.
     */
    public synchronized void setSampling(boolean enabled, BooleanSupplier quiet) {
        this.sampling = enabled;
        this.linkQuiet = quiet == null ? () -> true : quiet;
    }

    /**
     * Ostinato: {@code sent.test(peer, windowMs)} says whether any line left for {@code peer} in the last
     * {@code windowMs}; such a peer gets no heartbeat. Null keeps every heartbeat.
     */
    public synchronized void setRecentlySent(BiPredicate<String, Long> sent) {
        this.recentlySent = sent == null ? (peer, windowMs) -> false : sent;
    }

    /** Ostinato: {@code STS} frames sent. */
    public synchronized long samplesSent() { return samplesSent; }
    /** Ostinato: heartbeat lines queued; a broadcast counts once. */
    public synchronized long heartbeatsSent() { return heartbeatsSent; }
    /** Ostinato: heartbeats a peer did not need because it had just got another frame from us. */
    public synchronized long heartbeatsSkipped() { return heartbeatsSkipped; }

    /** Ostinato: a copy of every known member's sample and traffic, for the command center. */
    public synchronized List<PeerLink> peerLinks() {
        long now = clockMs.getAsLong();
        List<PeerLink> out = new ArrayList<>(peers.size());
        for (Peer p : peers.values()) {
            out.add(new PeerLink(p.name, p.lastSeenMs, p.sample, p.sampleAtMs, p.traffic.count(now),
                    p.traffic.total()));
        }
        return out;
    }

    private void handle(SwarmMessage m) {
        long now = clockMs.getAsLong();
        Peer p = peers.computeIfAbsent(m.from(), Peer::new);
        p.lastSeenMs = now;
        p.lastGroup = m.group();
        p.traffic.add(now); // Ostinato: link monitor traffic per member.
        switch (m.type()) {
            case STS: // Ostinato: performance sample; no reply.
                p.sample = SwarmSample.parse(m.body());
                p.sampleAtMs = now;
                break;
            case PING:
                if ("hb".equals(m.body())) {
                    // Ostinato: a heartbeat needs no reply; each member's own heartbeat keeps it seen. A pong
                    // here doubled the Phase 1 chat load for nothing.
                    break;
                }
                try {
                    endpoint.send(m.group(), m.from(), PONG, "hb".equals(m.body()) ? "hb" : pongBody(m.msgId()), SwarmPriority.HIGH); // Ostinato: compact heartbeat reply.
                } catch (Exception e) {
                    sendFailures++;
                    log.accept("swarm: pong to " + m.from() + " failed: " + e.getMessage());
                }
                break;
            case CommandProtocol.TYPE_CMD:
            case CommandProtocol.TYPE_ACK:
            case CommandProtocol.TYPE_HELLO:
                // Ostinato: already sealed and roster-checked by the endpoint. Off, these are ignored
                // the same way as any other unknown type.
                if (commandProtocol != null) {
                    try {
                        commandProtocol.receive("link", m.from(), m.type(), m.body());
                    } catch (RuntimeException e) {
                        log.accept("swarm: command protocol rejected " + m.type() + ": " + e.getMessage());
                    }
                } else {
                    ignored++;
                }
                break;
            case PONG:
                if ("hb".equals(m.body())) break; // Ostinato: lastSeen already refreshed.
                Map<String, String> kv = parseBody(m.body());
                p.pos = kv.getOrDefault("pos", "?");
                p.proc = kv.getOrDefault("proc", "?");
                p.pongAtMs = now;
                Long sent = null;
                try {
                    sent = pingsSent.get(Long.parseLong(kv.getOrDefault("re", ""), 36));
                } catch (NumberFormatException ignoredBadRe) {
                    // a pong that does not name one of our pings still counts as seen
                }
                if (sent != null) {
                    p.rttMs = now - sent;
                }
                log.accept("swarm: pong from " + m.from() + " [" + m.group() + "] pos " + p.pos + ", " + p.proc
                        + (p.rttMs >= 0 && sent != null ? ", " + p.rttMs + " ms" : ""));
                break;
            default:
                Consumer<SwarmMessage> extra = extraHandlers.get(m.type());
                if (extra != null) {
                    try {
                        extra.accept(m);
                    } catch (RuntimeException e) {
                        log.accept("swarm: handler for " + m.type() + " failed: " + e.getMessage());
                    }
                } else if (build == null || !build.handle(m)) {
                    ignored++;
                }
        }
    }

    String pongBody(long pingMsgId) {
        return "name=" + clean(local.name()) + ";pos=" + clean(local.position()) + ";proc=" + clean(local.process())
                + ";re=" + Long.toString(pingMsgId, 36);
    }

    static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return "?";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_FIELD; i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 || c == ';' || c == '=' || c == '\u00a7' || c == 0x7f ? '_' : c);
        }
        return b.toString();
    }

    static Map<String, String> parseBody(String body) {
        Map<String, String> kv = new HashMap<>();
        for (String part : body.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                kv.put(part.substring(0, eq), clean(part.substring(eq + 1)));
            }
        }
        return kv;
    }

    private void prunePings(long now) {
        pingsSent.values().removeIf(t -> now - t > PING_TTL_MS);
    }

    /** Other members across our groups. */
    public synchronized int peerCount() {
        java.util.Set<String> all = new java.util.HashSet<>();
        for (SwarmRoster.Group g : roster.groupsOf(endpoint.selfId())) {
            all.addAll(g.members());
        }
        all.remove(endpoint.selfId());
        return all.size();
    }

    /** Other members we have had an authenticated message from. */
    public synchronized int seenCount() {
        int n = 0;
        for (Peer p : peers.values()) {
            if (p.lastSeenMs >= 0 && roster.groupsOf(p.name).stream().anyMatch(g -> g.has(endpoint.selfId()))) {
                n++;
            }
        }
        return n;
    }

    public synchronized Peer peer(String name) {
        return peers.get(name);
    }

    /** Human-readable status: roster, last-seen ages, link health. */
    public synchronized List<String> statusLines() {
        long now = clockMs.getAsLong();
        List<String> out = new ArrayList<>();
        out.add("swarm: " + endpoint.selfId() + ", wire " + endpoint.config().wire() + ", "
                + roster.groupsOf(endpoint.selfId()).size() + " group(s)");
        for (SwarmRoster.Group g : roster.groups()) {
            if (!g.has(endpoint.selfId())) {
                continue;
            }
            out.add("group " + g.id() + " (circle " + g.circle() + (g.lead() != null ? ", lead " + g.lead() : "")
                    + ")");
            for (String member : g.members()) {
                if (member.equals(endpoint.selfId())) {
                    continue;
                }
                Peer p = peers.get(member);
                if (p == null || p.lastSeenMs < 0) {
                    out.add("  " + member + ": never seen");
                    continue;
                }
                StringBuilder b = new StringBuilder("  " + member + ": seen " + age(now - p.lastSeenMs) + " ago");
                if (p.pongAtMs >= 0) {
                    b.append(", pos ").append(p.pos).append(", ").append(p.proc);
                    if (p.rttMs >= 0) {
                        b.append(", rtt ").append(p.rttMs).append(" ms");
                    }
                }
                out.add(b.toString());
            }
        }
        if (build != null) {
            out.addAll(build.statusLines());
        }
        StringBuilder r = new StringBuilder("link: pending " + endpoint.pendingMessages());
        if (sendFailures > 0) {
            r.append(", send failures ").append(sendFailures);
        }
        if (ignored > 0) {
            r.append(", ignored ").append(ignored);
        }
        if (linkSummary != null) {
            r.append(", ").append(linkSummary.get());
        }
        out.add(r.toString());
        Map<SwarmReject, Long> rejects = endpoint.rejectCounts();
        if (!rejects.isEmpty()) {
            StringBuilder b = new StringBuilder("rejected:");
            for (Map.Entry<SwarmReject, Long> e : rejects.entrySet()) {
                b.append(' ').append(e.getKey().name().toLowerCase(Locale.ROOT)).append('=').append(e.getValue());
            }
            out.add(b.toString());
        }
        return out;
    }

    static String age(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 120) {
            return s + "s";
        }
        if (s < 7200) {
            return (s / 60) + "m";
        }
        return (s / 3600) + "h";
    }
}
