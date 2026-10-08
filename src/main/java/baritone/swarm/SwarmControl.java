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
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.SwarmPriority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                || SwarmBuild.STAT.equals(type) || SwarmBuild.STOP.equals(type)) {
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
    }

    private void handle(SwarmMessage m) {
        long now = clockMs.getAsLong();
        Peer p = peers.computeIfAbsent(m.from(), Peer::new);
        p.lastSeenMs = now;
        p.lastGroup = m.group();
        switch (m.type()) {
            case PING:
                try {
                    endpoint.send(m.group(), m.from(), PONG, pongBody(m.msgId()), SwarmPriority.HIGH);
                } catch (Exception e) {
                    sendFailures++;
                    log.accept("swarm: pong to " + m.from() + " failed: " + e.getMessage());
                }
                break;
            case PONG:
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
