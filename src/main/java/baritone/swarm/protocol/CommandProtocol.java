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

package baritone.swarm.protocol;

import baritone.agent.AgentPolicy;
import baritone.swarm.transport.SwarmPriority; // Ostinato: preserve transport priority.
import baritone.api.command.CommandPermission;
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ExternalCommandHook;
import baritone.api.swarm.protocol.ISwarmStatusView;
import baritone.api.swarm.protocol.ObservationState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 1 swarm command semantics. Authentication is the caller's job (SIGIL);
 * this class does not trust a transport receipt as an ack, and it does not write disk.
 *
 * <p>Layering is semantics (this class), then SIGIL, then transport. {@link Wire#send}
 * must not call back into this protocol.
 */
public final class CommandProtocol {

    public static final String TYPE_CMD = "SWC";
    public static final String TYPE_ACK = "SWA";
    public static final String TYPE_HELLO = "SWH";
    public static final int LEDGER_CAP = 256;
    public static final long DEFAULT_LEDGER_TTL_MS = 600_000L;
    /** How long {@code #swarm send} waits for an ack before it gives up, unless the caller passes one. */
    public static final long DEFAULT_DEADLINE_MS = 60_000L;

    private final Object lock = new Object();
    private final BotIdentity self;
    private final Set<String> members;
    private final Wire wire;
    private final Executor executor;
    private final Policy policy;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final long retryMs;
    private final Map<UUID, Tracked> ledger = new LinkedHashMap<>();
    private final Map<UUID, Sent> outbound = new LinkedHashMap<>();
    private final List<StatusView> views = new ArrayList<>();
    private boolean linkUp = true;
    private final Set<String> onlineMembers = new java.util.HashSet<>(); // Ostinato: join edges.
    private boolean immediateSafety;
    private boolean ownerEmergency;
    private boolean localSurvival;
    private int starts;
    private final Set<String> groups = new java.util.HashSet<>();
    private final Map<String, String> pinnedSignets = new LinkedHashMap<>();
    private final Map<String, CapabilityDescriptor> peerCaps = new LinkedHashMap<>();
    private CapabilityDescriptor localCaps = CapabilityDescriptor.gameplay();
    private long ledgerTtlMs = DEFAULT_LEDGER_TTL_MS;

    public CommandProtocol(BotIdentity self, Set<String> members, Wire wire, Executor executor, Policy policy,
                           LongSupplier clock, Consumer<String> log, long retryMs) {
        this.self = Objects.requireNonNull(self);
        this.members = Set.copyOf(members);
        this.wire = Objects.requireNonNull(wire);
        this.executor = Objects.requireNonNull(executor);
        this.policy = policy == null ? Policy.defaults() : policy;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.log = log == null ? s -> { } : log;
        this.retryMs = Math.max(1L, retryMs);
    }

    /** Roster member id this process speaks as. The signet id is the one SIGIL pinned for that member. */
    public BotIdentity identity() {
        return self;
    }

    public void setImmediateSafety(boolean on) {
        immediateSafety = on;
    }

    public void setOwnerEmergency(boolean on) {
        ownerEmergency = on;
    }

    public void setLocalSurvival(boolean on) {
        localSurvival = on;
    }

    /** Roster group ids this bot belongs to. A command aimed at one of them is multi-bot. */
    public void setGroups(Set<String> groupIds) {
        synchronized (lock) {
            groups.clear();
            if (groupIds != null) {
                groups.addAll(groupIds);
            }
        }
    }

    public void setLocalCapabilities(CapabilityDescriptor caps) {
        synchronized (lock) {
            localCaps = caps;
        }
    }

    public void setLedgerTtlMs(long ttlMs) {
        synchronized (lock) {
            ledgerTtlMs = Math.max(1L, ttlMs);
        }
    }

    /** Pinned signet key id for {@code member}. Hellos that do not match are ignored. */
    public void pinSignet(String member, String signetKeyId) {
        if (member == null || signetKeyId == null || signetKeyId.isBlank()) {
            return;
        }
        synchronized (lock) {
            pinnedSignets.put(member, signetKeyId.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** Test and hello path. An unknown descriptor version is not stored. */
    public void learnPeer(String member, String signetKeyId, CapabilityDescriptor caps) {
        if (member == null || caps == null || !caps.understood()) {
            return;
        }
        synchronized (lock) {
            if (signetKeyId != null && !signetKeyId.isBlank()) {
                pinnedSignets.put(member, signetKeyId.trim().toLowerCase(Locale.ROOT));
            }
            peerCaps.put(member, caps);
        }
    }

    public CapabilityDescriptor peerCapabilities(String member) {
        synchronized (lock) {
            return peerCaps.get(member);
        }
    }

    /** Tell peers what this bot can do. The body is sealed by {@link Wire}; this method does not sign. */
    public void advertise() {
        CapabilityDescriptor caps;
        synchronized (lock) {
            caps = localCaps;
        }
        if (caps == null || !caps.understood()) {
            return;
        }
        wire.send("*", TYPE_HELLO, "1|hello|" + self.memberId() + "|" + self.signetKeyId() + "|" + caps.encode());
    }

    // Ostinato: reconnect the existing protocol without discarding its command ledger.
    public void onReconnect() {
        synchronized (lock) {
            linkUp = true;
        }
        advertise();
    }

    // Ostinato: one hello per online roster change, including peers returning after leaving.
    public boolean noteOnlineMembers(Set<String> online) {
        boolean joined;
        synchronized (lock) {
            Set<String> peers = new java.util.HashSet<>(online);
            peers.retainAll(members);
            peers.remove(self.memberId());
            joined = !onlineMembers.containsAll(peers);
            onlineMembers.clear();
            onlineMembers.addAll(peers);
        }
        if (joined) {
            advertise();
        }
        return joined;
    }

    public boolean linkUp() {
        synchronized (lock) {
            return linkUp;
        }
    }

    /** How many times this bot actually called {@link Executor#start}. */
    public int startCount() {
        synchronized (lock) {
            return starts;
        }
    }

    public String submit(String target, String commandLine) {
        return submit(target, commandLine, clock.getAsLong() + DEFAULT_DEADLINE_MS, false, null);
    }

    /**
     * Send one command. Retries use the same id and the same body.
     *
     * @param previousId a command whose observation is unknown; mine and place are not retried
     */
    public String submit(String target, String commandLine, long deadlineMs, boolean dryRun, UUID previousId) {
        String parsedName = commandName(commandLine);
        synchronized (lock) {
            if (previousId != null && !mayRetry(previousId, parsedName)) {
                String line = "swarm: not retrying " + parsedName + " " + previousId + " while observation is unknown";
                log.accept(line);
                return line;
            }
            if (nonIdempotent(parsedName) && mutationUnknownLocked(parsedName)) {
                String line = "swarm: not retrying " + parsedName + " while observation is unknown";
                log.accept(line);
                return line;
            }
        }
        if (target == null || target.isBlank() || commandLine == null || commandLine.isBlank()) {
            return "swarm: send needs a target and a command";
        }
        target = target.trim();
        String name = commandName(commandLine);
        String args = commandArgs(commandLine);
        if (!validToken(name) || !validToken(target)) {
            return "swarm: bad target or command name";
        }
        String refused = allocatorRefuse(target, name, args, dryRun);
        if (refused != null) {
            return refused;
        }
        long now = clock.getAsLong();
        if (deadlineMs <= now) {
            return "swarm: deadline is already past";
        }
        UUID id = UUID.randomUUID();
        String body = encodeCmd(id, self.memberId(), target.trim(), name, args, now, deadlineMs, dryRun);
        Sent sent = new Sent(id, target.trim(), body, deadlineMs);
        synchronized (lock) {
            outbound.put(id, sent);
        }
        boolean queued = wire.send(sent.target, TYPE_CMD, body, commandPriority(name));
        synchronized (lock) {
            if (queued) {
                sent.sends = 1;
                sent.nextRetry = now + retryDelay(sent.sends); // Ostinato: queued is not yet delivered.
            } else {
                sent.nextRetry = now;
            }
        }
        // Ostinato: callers display the queued line; it is not an ack.
        String line = "swarm: sent " + id + " to " + sent.target + " (awaiting ack)";
        return line;
    }

    /** False for mine/place (and other non-idempotent acts) when the previous observation is unknown. */
    public boolean mayRetry(UUID previousId, String commandName) {
        if (previousId == null || commandName == null) {
            return true;
        }
        if (!nonIdempotent(commandName)) {
            return true;
        }
        synchronized (lock) {
            Tracked t = ledger.get(previousId);
            Sent s = outbound.get(previousId);
            ObservationState obs = t != null ? t.observation : (s != null ? s.observation : null);
            if (obs == ObservationState.UNKNOWN) {
                return false;
            }
        }
        return true;
    }

    /** True when some earlier mine or place is still observation-unknown. Caller holds {@link #lock}. */
    private boolean mutationUnknownLocked(String name) {
        if (!nonIdempotent(name)) {
            return false;
        }
        for (Sent s : outbound.values()) {
            if (s.observation == ObservationState.UNKNOWN && nonIdempotent(nameOfBody(s.body))) {
                return true;
            }
        }
        return false;
    }

    private static String nameOfBody(String body) {
        if (body == null) {
            return "";
        }
        String[] f = body.split("\\|", -1);
        return f.length > 5 ? f[5] : "";
    }

    public static boolean nonIdempotent(String name) {
        if (name == null) {
            return false;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "mine":
            case "place":
            case "drop":
            case "pickup":
            case "craft":
            case "use":
                return true;
            default:
                return false;
        }
    }

    /**
     * Delivery retry and deadline give-up. Call on the bot tick. Does not treat a transport
     * accept as an ack, and does not mint a new command id.
     */
    public void pump() {
        List<Sent> due = new ArrayList<>();
        long now = clock.getAsLong();
        synchronized (lock) {
            sweepExpired();
            for (Sent s : outbound.values()) {
                if (s.acked || s.gaveUp) {
                    continue;
                }
                if (now >= s.deadline) {
                    s.gaveUp = true;
                    s.observation = ObservationState.UNKNOWN;
                    if (s.execution == null || !s.execution.terminal()) {
                        s.execution = ExecutionState.RUNNING;
                    }
                    log.accept("swarm: gave up on " + s.id + " at deadline; running, observation unknown");
                    continue;
                }
                // Retries only while the link is up. A transport accept is still not an ack.
                if (linkUp && now >= s.nextRetry) {
                    due.add(s);
                }
            }
        }
        for (Sent s : due) {
            if (!linkUp()) {
                break;
            }
            // Ostinato: ordinary retries yield to safety commands and acknowledgements.
            SwarmPriority priority = commandPriority(nameOfBody(s.body)) == SwarmPriority.HIGH
                    ? SwarmPriority.HIGH : SwarmPriority.LOW;
            boolean queued = wire.send(s.target, TYPE_CMD, s.body, priority);
            synchronized (lock) {
                if (queued) {
                    s.sends++;
                    s.nextRetry = now + retryDelay(s.sends);
                } else {
                    s.nextRetry = now;
                }
            }
        }
    }

    // Ostinato: allow the slow whisper queue to drain; cap exponential backoff at 30 seconds.
    private long retryDelay(int sends) {
        return Math.min(30_000L, Math.min(retryMs, 30_000L) * (1L << Math.min(sends + 1, 15)));
    }

    // Ostinato: safety commands preempt ordinary traffic, including on redelivery.
    private static SwarmPriority commandPriority(String name) {
        return "cancel".equalsIgnoreCase(name) || "stop".equalsIgnoreCase(name)
                || "forcecancel".equalsIgnoreCase(name) ? SwarmPriority.HIGH : SwarmPriority.NORMAL;
    }

    /**
     * One already-authenticated message. {@code from} is the SIGIL sender, not the body.
     * {@code transport} is recorded only so two transports can deliver the same id.
     */
    public void receive(String transport, String from, String type, String body) {
        if (type == null || body == null) {
            return;
        }
        if (TYPE_HELLO.equals(type)) {
            onHello(from, body);
            return;
        }
        if (TYPE_ACK.equals(type)) {
            onAck(from, body);
            return;
        }
        if (!TYPE_CMD.equals(type)) {
            return;
        }
        Parsed p;
        try {
            p = Parsed.parse(body);
        } catch (IllegalArgumentException e) {
            log.accept("swarm: rejected bad payload (" + e.getMessage() + ")");
            return;
        }
        Decision d;
        synchronized (lock) {
            d = decide(transport, from, p);
        }
        if (d.kind == Kind.START) {
            boolean ok = false;
            RuntimeException boom = null;
            try {
                ok = executor.start(d.id, d.name, d.args);
            } catch (RuntimeException e) {
                boom = e;
            }
            String ack;
            synchronized (lock) {
                Tracked t = ledger.get(d.id);
                if (t == null) {
                    return;
                }
                if (ok && boom == null) {
                    starts++;
                }
                if (!linkUp) {
                    move(t, ExecutionState.RUNNING);
                    t.observation = ObservationState.UNKNOWN;
                } else if (!ok || boom != null) {
                    move(t, ExecutionState.FAILED);
                    t.observation = ObservationState.CONFIRMED;
                    t.rejectReason = "failed";
                } else if (t.ambiguous) {
                    // A chain that merely goes idle is not a verified success.
                    move(t, ExecutionState.RUNNING);
                    t.observation = ObservationState.UNKNOWN;
                } else if (completesInline(d.name) && !t.worldMutation) {
                    move(t, ExecutionState.RUNNING);
                    move(t, ExecutionState.SUCCEEDED);
                    t.observation = ObservationState.CONFIRMED;
                } else {
                    move(t, ExecutionState.RUNNING);
                    t.observation = ObservationState.CONFIRMED;
                }
                if (ok && boom == null && "pause".equals(d.name)) {
                    ownerEmergency = true;
                }
                ack = ackBody(t);
            }
            if (ok && boom == null && "disconnect".equals(d.name)) {
                onDisconnect();
            }
            wire.send(from, TYPE_ACK, ack, SwarmPriority.HIGH); // Ostinato: acks preempt retries.
            return;
        }
        if (d.kind == Kind.CANCEL) {
            boolean confirmed = false;
            if (d.askStop) {
                try {
                    confirmed = executor.confirmStopped(d.cancelTarget);
                } catch (RuntimeException ignored) {
                    confirmed = false;
                }
            }
            String ack;
            Decision targetAck = null; // Ostinato: snapshot the original outcome under the lock.
            synchronized (lock) {
                Tracked cancel = ledger.get(d.id);
                Tracked target = d.cancelTarget == null ? null : ledger.get(d.cancelTarget);
                if (cancel != null) {
                    boolean targetTerminal = target != null && target.execution.terminal();
                    if (!linkUp) {
                        // The link dropped. We do not know if the process stopped.
                        move(cancel, ExecutionState.RUNNING);
                        cancel.observation = ObservationState.UNKNOWN;
                        if (target != null && !targetTerminal) {
                            move(target, ExecutionState.RUNNING);
                            target.observation = ObservationState.UNKNOWN;
                        }
                    } else if (confirmed && target != null && !targetTerminal) {
                        move(cancel, ExecutionState.RUNNING);
                        move(cancel, ExecutionState.SUCCEEDED);
                        cancel.observation = ObservationState.CONFIRMED;
                        move(target, ExecutionState.RUNNING);
                        move(target, ExecutionState.CANCELED);
                        target.observation = ObservationState.CONFIRMED;
                        target.rejectReason = null;
                        targetAck = outcomeAck(target); // Ostinato: notify the original issuer too.
                    } else if (targetTerminal) {
                        // Verified finish won. The stop is not confirmed and must not rewrite the result.
                        move(cancel, ExecutionState.RUNNING);
                        cancel.observation = ObservationState.UNKNOWN;
                        cancel.rejectReason = "already-finished";
                    } else {
                        // Not confirmed (still running, or no such command). Unknown, never canceled.
                        move(cancel, ExecutionState.RUNNING);
                        cancel.observation = ObservationState.UNKNOWN;
                        if (target != null && !targetTerminal) {
                            move(target, ExecutionState.RUNNING);
                            target.observation = ObservationState.UNKNOWN;
                        }
                    }
                    ack = ackBody(cancel);
                } else {
                    ack = null;
                }
            }
            // Ostinato: send the original outcome outside the protocol lock.
            if (targetAck != null) {
                wire.send(targetAck.replyTo, TYPE_ACK, targetAck.ack, SwarmPriority.HIGH);
            }
            if (ack != null) {
                wire.send(from, TYPE_ACK, ack, SwarmPriority.HIGH); // Ostinato: acks preempt retries.
            }
            return;
        }
        if (d.ack != null) {
            wire.send(from, TYPE_ACK, d.ack, SwarmPriority.HIGH); // Ostinato: acks preempt retries.
        }
    }

    /** Link dropped. In-flight work stays running; its observation becomes unknown. */
    public void onDisconnect() {
        onDisconnect(true); // Ostinato: real drops keep the existing announcement.
    }

    // Ostinato: planned restarts update observation without announcing a link failure.
    public void onDisconnect(boolean announce) {
        synchronized (lock) {
            linkUp = false;
            for (Tracked t : ledger.values()) {
                if (!t.execution.terminal()) {
                    // Ostinato: preserve execution state on link loss.
                    t.observation = ObservationState.UNKNOWN;
                }
            }
            long now = clock.getAsLong();
            for (Sent s : outbound.values()) {
                if (s.gaveUp) {
                    continue;
                }
                if (s.acked && s.execution != null && s.execution.terminal()) {
                    continue;
                }
                if (!s.acked && now >= s.deadline) {
                    s.gaveUp = true;
                    log.accept("swarm: gave up on " + s.id + " at deadline; no ack");
                }
                // Acked or not: a drop means we no longer know. Mine and place are not retried from here.
                s.observation = ObservationState.UNKNOWN;
                // Ostinato: no execution transition is justified by a disconnect.
            }
            if (announce) { // Ostinato:
                log.accept("swarm: link down; execution unchanged, observation unknown");
            }
        }
    }

    // Ostinato: remote loss changes observation only; a missing peer is not an execution result.
    public void onPeerDisconnect(String member) { // Ostinato:
        synchronized (lock) { // Ostinato:
            for (Sent sent : outbound.values()) { // Ostinato:
                if ((member.equals(sent.target) || multiBot(sent.target)) // Ostinato:
                        && (sent.execution == null || !sent.execution.terminal())) { // Ostinato:
                    sent.observation = ObservationState.UNKNOWN; // Ostinato:
                } // Ostinato:
            } // Ostinato:
        } // Ostinato:
    } // Ostinato:

    public void pollExecutions() {
        List<UUID> running = new ArrayList<>();
        synchronized (lock) {
            if (!linkUp) {
                return;
            }
            for (Tracked t : ledger.values()) {
                if (t.started && t.execution == ExecutionState.RUNNING && t.observation != ObservationState.UNKNOWN) {
                    running.add(t.id);
                }
            }
        }
        for (UUID id : running) {
            ExecutionState next;
            try {
                next = executor.poll(id);
            } catch (RuntimeException e) {
                next = null;
            }
            if (next == null || next == ExecutionState.RUNNING || next == ExecutionState.ACCEPTED) {
                continue;
            }
            Decision outcome = null; // Ostinato: capture send work while locked.
            synchronized (lock) {
                if (!linkUp) {
                    return;
                }
                Tracked t = ledger.get(id);
                if (t == null || t.execution.terminal()) {
                    continue;
                }
                if (t.ambiguous) {
                    t.observation = ObservationState.UNKNOWN;
                    continue;
                }
                if (next == ExecutionState.SUCCEEDED || next == ExecutionState.FAILED || next == ExecutionState.CANCELED) {
                    try {
                        move(t, next);
                        t.observation = ObservationState.CONFIRMED;
                        outcome = outcomeAck(t); // Ostinato: publish verified terminal results.
                    } catch (IllegalStateException refused) {
                        log.accept("swarm: refused illegal outcome " + t.id + " " + refused.getMessage());
                    }
                }
            }
            // Ostinato: wire callbacks must not run under the protocol lock.
            if (outcome != null) {
                wire.send(outcome.replyTo, TYPE_ACK, outcome.ack, SwarmPriority.HIGH);
            }
        }
    }

    // Ostinato: caller holds lock; capture immutable ACK data for sending outside it.
    private Decision outcomeAck(Tracked t) {
        return t.issuer == null || t.issuer.equals(self.memberId())
                ? null : Decision.ack(t.issuer, ackBody(t));
    }

    public String outcomeLine(String idText) {
        UUID id;
        try {
            id = UUID.fromString(idText == null ? "" : idText.trim());
        } catch (IllegalArgumentException e) {
            return "swarm: not a command id";
        }
        synchronized (lock) {
            Sent s = outbound.get(id);
            if (s != null) {
                return formatSent(s);
            }
            Tracked t = ledger.get(id);
            if (t != null) {
                return formatTracked(t);
            }
        }
        return "swarm: no command " + id;
    }

    public List<String> statusLines() {
        synchronized (lock) {
            List<String> out = new ArrayList<>();
            out.add("protocol: bot " + self.memberId() + " signet " + self.signetKeyId()
                    + (linkUp ? " link up" : " link down"));
            int n = 0;
            for (Sent s : outbound.values()) {
                if (n++ >= 16) {
                    break;
                }
                out.add("  " + formatSent(s));
            }
            for (Tracked t : ledger.values()) {
                if (outbound.containsKey(t.id)) {
                    continue;
                }
                if (n++ >= 24) {
                    break;
                }
                out.add("  " + formatTracked(t));
            }
            return out;
        }
    }

    public ISwarmStatusView openStatusView() {
        StatusView view = new StatusView();
        synchronized (lock) {
            views.add(view);
        }
        return view;
    }

    public int openViewCount() {
        synchronized (lock) {
            int n = 0;
            for (StatusView v : views) {
                if (v.open) {
                    n++;
                }
            }
            return n;
        }
    }

    private Decision decide(String transport, String from, Parsed p) {
        if (!validToken(from) || !from.equals(p.issuer)) {
            log.accept("swarm: rejected forged command " + p.id + " on " + transport);
            return Decision.ack(from, rejectAck(p.id, "forged", ExecutionState.FAILED));
        }
        if (!members.contains(from)) {
            log.accept("swarm: rejected unknown member " + from);
            return Decision.ack(from, rejectAck(p.id, "unknown-member", ExecutionState.FAILED));
        }
        if (!isTarget(p.target)) {
            Tracked prior = ledger.get(p.id);
            if (prior != null && prior.payloadHash.equals(p.hash)) {
                return Decision.ack(from, ackBody(prior));
            }
            return Decision.ack(from, rememberReject(p, "wrong-target"));
        }
        if (p.deadline < p.issuedAt || clock.getAsLong() > p.deadline) {
            Tracked prior = ledger.get(p.id);
            if (prior != null && prior.payloadHash.equals(p.hash)) {
                return Decision.ack(from, ackBody(prior));
            }
            return Decision.ack(from, rememberReject(p, "expired"));
        }
        Tracked prior = ledger.get(p.id);
        if (prior != null) {
            if (!prior.payloadHash.equals(p.hash)) {
                log.accept("swarm: rejected tampered replay " + p.id + " on " + transport);
                return Decision.ack(from, rejectAck(p.id, "tampered-replay", prior.execution));
            }
            log.accept("swarm: duplicate " + p.id + " on " + transport + "; returning recorded outcome");
            return Decision.ack(from, ackBody(prior));
        }
        CommandCatalog.Entry entry = describe(p.name);
        if (entry == null) {
            return Decision.ack(from, rememberReject(p, "unknown-command"));
        }
        String badArgs = entry.validate(p.args);
        if (badArgs != null) {
            return Decision.ack(from, rememberReject(p, "bad-args"));
        }
        String denied = deny(p, entry);
        if (denied != null) {
            return Decision.ack(from, rememberReject(p, denied));
        }
        String missing = localCap(entry);
        if (missing != null) {
            return Decision.ack(from, rememberReject(p, missing));
        }
        if (multiBot(p.target) && (entry.leaseNeeding || entry.worldMutation)) {
            return Decision.ack(from, rememberReject(p, "coordinated"));
        }
        if (isCancel(p.name)) {
            return decideCancel(from, p, entry);
        }
        if (!linkUp) {
            return Decision.ack(from, rememberReject(p, "link-down"));
        }
        // A verified inline command (eta, help, echo) may run beside an in-flight path.
        // A second path or world action may not: that would be a second active task.
        if (!entry.ownerEmergency && !completesInline(p.name) && busy()) {
            return Decision.ack(from, rememberReject(p, "busy"));
        }
        if (!reserve(p, true, null, ExecutionState.ACCEPTED, ObservationState.CONFIRMED, entry)) {
            return Decision.ack(from, rejectAck(p.id, "ledger-full", ExecutionState.FAILED));
        }
        return Decision.start(p.id, p.name, p.args, from);
    }

    private Decision decideCancel(String from, Parsed p, CommandCatalog.Entry entry) {
        UUID target;
        try {
            target = UUID.fromString(p.args == null ? "" : p.args.trim());
        } catch (IllegalArgumentException e) {
            return unconfirmedStop(p, entry, "bad-cancel-target");
        }
        Tracked existing = ledger.get(target);
        if (existing != null && existing.execution.terminal()) {
            // A verified finish wins the race. Do not rewrite it as canceled.
            return unconfirmedStop(p, entry, "already-finished");
        }
        if (!linkUp) {
            if (!unconfirmedStopStored(p, entry, "link-down")) {
                return Decision.ack(from, rejectAck(p.id, "ledger-full", ExecutionState.FAILED));
            }
            if (existing != null) {
                move(existing, ExecutionState.RUNNING);
                existing.observation = ObservationState.UNKNOWN;
            }
            return Decision.ack(from, ackBody(ledger.get(p.id)));
        }
        if (existing == null) {
            return unconfirmedStop(p, entry, "unknown-command");
        }
        if (!reserve(p, false, null, ExecutionState.ACCEPTED, ObservationState.CONFIRMED, entry)) {
            return Decision.ack(from, rejectAck(p.id, "ledger-full", ExecutionState.FAILED));
        }
        return Decision.cancel(p.id, target, from, true);
    }

    /** Stop that the executing bot has not confirmed. Never stored as canceled. */
    private Decision unconfirmedStop(Parsed p, CommandCatalog.Entry entry, String reason) {
        if (!unconfirmedStopStored(p, entry, reason)) {
            return Decision.ack(fromOf(p), rejectAck(p.id, "ledger-full", ExecutionState.FAILED));
        }
        return Decision.ack(fromOf(p), ackBody(ledger.get(p.id)));
    }

    private boolean unconfirmedStopStored(Parsed p, CommandCatalog.Entry entry, String reason) {
        if (!reserve(p, false, reason, ExecutionState.ACCEPTED, ObservationState.UNKNOWN, entry)) {
            return false;
        }
        Tracked cancel = ledger.get(p.id);
        move(cancel, ExecutionState.RUNNING);
        cancel.observation = ObservationState.UNKNOWN;
        cancel.rejectReason = reason;
        return true;
    }

    private static String fromOf(Parsed p) {
        return p.issuer;
    }

    private boolean issuerIsOwner(String issuer) {
        String owner = policy.ownerName == null || policy.ownerName.isBlank() ? self.memberId() : policy.ownerName;
        return AgentPolicy.ownerAllowed(true, owner, issuer);
    }

    private String deny(Parsed p, CommandCatalog.Entry entry) {
        if (immediateSafety) {
            return "outranked-by-immediate-safety";
        }
        // Ostinato: a non-owner swarm cancel is outranked by pause/survival. The owner's
        // cancel still gets through, same as #stop.
        boolean emergency = entry.ownerEmergency || (isCancel(p.name) && issuerIsOwner(p.issuer));
        if (!emergency && ownerEmergency) {
            return "outranked-by-owner-emergency";
        }
        if (!emergency && localSurvival) {
            return "outranked-by-local-survival";
        }
        CommandPermission perm = entry.permission;
        switch (perm) {
            case OWNER: {
                String owner = policy.ownerName == null || policy.ownerName.isBlank() ? self.memberId() : policy.ownerName;
                if (!AgentPolicy.ownerAllowed(true, owner, p.issuer)) {
                    return "unauthorized";
                }
                break;
            }
            case SWARM:
                if (!members.contains(p.issuer)) {
                    return "unauthorized";
                }
                break;
            case AI:
                if (!AgentPolicy.ownerAllowed(policy.ownerOnly, policy.ownerName, p.issuer)) {
                    return "unauthorized";
                }
                if (!AgentPolicy.toolAllowed(policy.toolCsv, p.name)) {
                    return "unauthorized";
                }
                break;
            default:
                return "unauthorized";
        }
        if (p.dryRun || (perm == CommandPermission.AI && policy.dryRun)) {
            return "preview";
        }
        return null;
    }

    private boolean reserve(Parsed p, boolean started, String reason, ExecutionState exec, ObservationState obs,
                            CommandCatalog.Entry entry) {
        sweepExpired();
        evictTerminal();
        if (ledger.size() >= LEDGER_CAP) {
            return false;
        }
        Tracked t = new Tracked(p.id, p.name, p.hash, ExecutionState.transit(null, ExecutionState.ACCEPTED), obs, started);
        if (exec != ExecutionState.ACCEPTED) {
            t.execution = ExecutionState.transit(t.execution, exec);
        }
        t.issuer = p.issuer; // Ostinato: retain the authenticated issuer for later outcomes.
        t.rejectReason = reason;
        t.storedAtMs = clock.getAsLong();
        if (entry != null) {
            t.ambiguous = entry.ambiguous;
            t.worldMutation = entry.worldMutation;
        }
        ledger.put(p.id, t);
        return true;
    }

    private String rememberReject(Parsed p, String reason) {
        Tracked prior = ledger.get(p.id);
        if (prior != null) {
            if (!prior.payloadHash.equals(p.hash)) {
                return rejectAck(p.id, "tampered-replay", prior.execution);
            }
            return ackBody(prior);
        }
        if (!reserve(p, false, reason, ExecutionState.FAILED, ObservationState.CONFIRMED, null)) {
            return rejectAck(p.id, "ledger-full", ExecutionState.FAILED);
        }
        log.accept("swarm: rejected " + p.id + " " + reason);
        return ackBody(ledger.get(p.id));
    }

    private void evictTerminal() {
        if (ledger.size() < LEDGER_CAP) {
            return;
        }
        Iterator<Tracked> it = ledger.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            if (t.execution.terminal()) {
                it.remove();
                return;
            }
        }
    }

    private void onAck(String from, String body) {
        Ack ack;
        try {
            ack = Ack.parse(body);
        } catch (IllegalArgumentException e) {
            log.accept("swarm: ignored bad ack (" + e.getMessage() + ")");
            return;
        }
        synchronized (lock) {
            if (!members.contains(from)) {
                log.accept("swarm: ignored ack from unknown member " + from);
                return;
            }
            Sent s = outbound.get(ack.id);
            if (s == null) {
                return;
            }
            // Ostinato: only the command's target (or a member, when the target is a group/all) may ack it.
            if (!ackFromIntendedTarget(from, s.target)) {
                log.accept("swarm: ignored ack from " + from + " for command aimed at " + s.target);
                return;
            }
            // Ostinato: delayed ACKs cannot replace any confirmed terminal result.
            if (s.acked && s.execution != null && s.execution.terminal()
                    && s.observation == ObservationState.CONFIRMED
                    && (ack.execution != s.execution || ack.observation != ObservationState.CONFIRMED
                    || ack.accepted != s.accepted)) {
                log.accept("swarm: ignored later ack that would unwind " + ack.id);
                return;
            }
            s.acked = true;
            s.gaveUp = false;
            s.accepted = ack.accepted;
            s.ackReason = ack.reason;
            if (ack.execution.terminal() && ack.observation != ObservationState.CONFIRMED) {
                s.execution = ExecutionState.RUNNING;
                s.observation = ObservationState.UNKNOWN;
            } else {
                s.execution = ack.execution;
                s.observation = ack.observation;
            }
            // Ostinato: a verified stop also confirms its original outbound command locally.
            if (isCancel(nameOfBody(s.body)) && ack.accepted && ack.execution == ExecutionState.SUCCEEDED
                    && ack.observation == ObservationState.CONFIRMED) {
                try {
                    UUID targetId = UUID.fromString(Parsed.parse(s.body).args.trim());
                    Sent target = outbound.get(targetId);
                    if (target != null && ackFromIntendedTarget(from, target.target)
                            && !(target.execution != null && target.execution.terminal()
                            && target.observation == ObservationState.CONFIRMED)) {
                        target.execution = ExecutionState.CANCELED;
                        target.observation = ObservationState.CONFIRMED;
                        target.acked = true;
                        target.accepted = true;
                        target.gaveUp = false;
                        target.ackReason = "";
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ostinato: malformed stop arguments cannot identify an original outcome.
                }
            }
            log.accept("swarm: ack " + ack.id + " " + (ack.accepted ? "accepted" : "rejected")
                    + (ack.reason == null || ack.reason.isEmpty() ? "" : " " + ack.reason)
                    + " " + ack.execution.label() + " observation " + ack.observation.label());
        }
    }

    /** Caller holds {@link #lock}. */
    private boolean ackFromIntendedTarget(String from, String target) {
        if (from == null || target == null) {
            return false;
        }
        if (from.equals(target)) {
            return true;
        }
        if ("*".equals(target) || "all".equalsIgnoreCase(target) || groups.contains(target)) {
            return members.contains(from);
        }
        return false;
    }

    private boolean isTarget(String target) {
        return "*".equals(target) || "all".equalsIgnoreCase(target) || self.memberId().equals(target)
                || groups.contains(target);
    }

    private boolean multiBot(String target) {
        return "*".equals(target) || "all".equalsIgnoreCase(target) || groups.contains(target);
    }

    private static boolean isCancel(String name) {
        return "cancel".equals(name) || "stop".equals(name) || "forcecancel".equals(name);
    }

    private static void move(Tracked t, ExecutionState next) {
        if (t.execution == next) {
            return;
        }
        t.execution = ExecutionState.transit(t.execution, next);
    }

    private CommandCatalog.Entry describe(String name) {
        try {
            return executor.describe(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String localCap(CommandCatalog.Entry entry) {
        if (entry.requires.isEmpty()) {
            return null;
        }
        if (localCaps == null) {
            return "capability-missing";
        }
        return localCaps.rejection(entry.requires) == null ? null : "capability-missing";
    }

    private boolean busy() {
        for (Tracked t : ledger.values()) {
            if (t.started && !t.execution.terminal()) {
                return true;
            }
        }
        return false;
    }

    private void sweepExpired() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<UUID, Tracked>> it = ledger.entrySet().iterator();
        while (it.hasNext()) {
            Tracked t = it.next().getValue();
            if (t.execution.terminal() && now - t.storedAtMs >= ledgerTtlMs) {
                it.remove();
            }
        }
    }

    private String allocatorRefuse(String target, String name, String args, boolean dryRun) {
        CommandCatalog.Entry entry;
        try {
            entry = executor.describe(name);
        } catch (RuntimeException e) {
            entry = null;
        }
        if (entry == null) {
            return "swarm: unknown command " + name;
        }
        String bad = entry.validate(args);
        if (bad != null) {
            return "swarm: " + bad;
        }
        boolean multi;
        String cap;
        boolean aiDry;
        boolean safety;
        String safetyWhy;
        synchronized (lock) {
            multi = multiBot(target);
            cap = capsFor(target, entry);
            aiDry = entry.permission == CommandPermission.AI && policy.dryRun;
            // Ostinato: the issuer's own survival latch must not block sending a command
            // to a peer. The receiving bot applies the same checks in deny() before it runs.
            boolean selfTarget = self.memberId().equals(target);
            safety = selfTarget && (immediateSafety || (!entry.ownerEmergency && (ownerEmergency || localSurvival)));
            safetyWhy = immediateSafety ? "outranked-by-immediate-safety"
                    : ownerEmergency ? "outranked-by-owner-emergency" : "outranked-by-local-survival";
        }
        String perm = submitPermission(entry, name);
        if (perm != null) {
            return perm;
        }
        if (safety) {
            return "swarm: " + safetyWhy;
        }
        if (dryRun || aiDry) {
            return "swarm: preview " + name + " on " + target + " (not executed)";
        }
        if (multi && (entry.leaseNeeding || entry.worldMutation)) {
            return "swarm: coordinated work is not available in phase 1";
        }
        if (cap != null) {
            return "swarm: " + cap;
        }
        return null;
    }

    private String submitPermission(CommandCatalog.Entry entry, String name) {
        String issuer = self.memberId();
        switch (entry.permission) {
            case OWNER: {
                String owner = policy.ownerName == null || policy.ownerName.isBlank() ? issuer : policy.ownerName;
                if (!AgentPolicy.ownerAllowed(true, owner, issuer)) {
                    return "swarm: unauthorized";
                }
                break;
            }
            case SWARM:
                if (!members.contains(issuer)) {
                    return "swarm: unauthorized";
                }
                break;
            case AI:
                if (!AgentPolicy.ownerAllowed(policy.ownerOnly, policy.ownerName, issuer)) {
                    return "swarm: unauthorized";
                }
                if (!AgentPolicy.toolAllowed(policy.toolCsv, name)) {
                    return "swarm: unauthorized";
                }
                break;
            default:
                return "swarm: unauthorized";
        }
        return null;
    }

    /** Null when every bot in scope can do the command. */
    private String capsFor(String target, CommandCatalog.Entry entry) {
        if (entry.requires.isEmpty()) {
            return null;
        }
        List<String> who = new ArrayList<>();
        if (self.memberId().equals(target)) {
            who.add(self.memberId());
        } else if (multiBot(target)) {
            for (String member : members) {
                if (!member.equals(self.memberId())) {
                    who.add(member);
                }
            }
        } else {
            who.add(target);
        }
        if (who.isEmpty()) {
            return "bot capabilities are unknown";
        }
        for (String member : who) {
            CapabilityDescriptor d = self.memberId().equals(member) ? localCaps : peerCaps.get(member);
            if (d == null || d.rejection(entry.requires) != null) {
                return d == null ? "bot capabilities are unknown" : "capability-missing";
            }
        }
        return null;
    }

    private void onHello(String from, String body) {
        String[] f = body.split("\\|", -1);
        if (f.length != 5 || !"1".equals(f[0]) || !"hello".equals(f[1])) {
            log.accept("swarm: ignored bad hello");
            return;
        }
        if (from == null || !from.equals(f[2])) {
            log.accept("swarm: ignored hello from " + from);
            return;
        }
        synchronized (lock) {
            String pinned = pinnedSignets.get(from);
            if (pinned == null || !pinned.equalsIgnoreCase(f[3])) {
                log.accept("swarm: ignored hello with unexpected signet from " + from);
                return;
            }
            CapabilityDescriptor caps = CapabilityDescriptor.decode(f[4]);
            if (!caps.understood()) {
                log.accept("swarm: ignored hello with unknown capability version from " + from);
                return;
            }
            // Ostinato: compare understood capability sets, not descriptor identity or ordering.
            CapabilityDescriptor previous = peerCaps.put(from, caps);
            if (previous != null && previous.version() == caps.version() && previous.kinds().equals(caps.kinds())) {
                return;
            }
        }
        advertise(); // Ostinato: teach a late joiner our caps without an identical-hello loop.
    }

    /** Commands that finish inside {@code ICommand.execute} and can be marked verified immediately. */
    public static boolean completesInline(String name) {
        if (name == null) {
            return false;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "set":
            case "help":
            case "settings":
            case "echo":
            case "status":
            case "reload":
            case "ping":
            case "version":
            case "eta":
                return true;
            default:
                return false;
        }
    }

    private static String commandName(String line) {
        String t = line.trim();
        int sp = t.indexOf(' ');
        String name = sp < 0 ? t : t.substring(0, sp);
        if (name.startsWith("#") || name.startsWith("@")) {
            name = name.substring(1);
        }
        return name;
    }

    private static String commandArgs(String line) {
        String t = line.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? "" : t.substring(sp + 1).trim();
    }

    private static boolean validToken(String s) {
        if (s == null || s.isEmpty() || s.length() > 40) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == '|' || c == ';') {
                return false;
            }
        }
        return true;
    }

    static String encodeCmd(UUID id, String issuer, String target, String name, String args,
                             long issuedAt, long deadline, boolean dryRun) {
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (args == null ? "" : args).getBytes(StandardCharsets.UTF_8));
        return "1|cmd|" + id + "|" + issuer + "|" + target + "|" + name + "|" + b64 + "|"
                + issuedAt + "|" + deadline + "|" + (dryRun ? "1" : "0");
    }

    private static String ackBody(Tracked t) {
        boolean accepted = t.rejectReason == null && t.execution != ExecutionState.FAILED;
        String reason = t.rejectReason == null ? "" : t.rejectReason;
        return "1|ack|" + t.id + "|" + (accepted ? "accepted" : "rejected") + "|" + reason + "|"
                + t.execution.label() + "|" + t.observation.label();
    }

    private static String rejectAck(UUID id, String reason, ExecutionState exec) {
        ExecutionState e = exec == null ? ExecutionState.FAILED : exec;
        return "1|ack|" + id + "|rejected|" + reason + "|" + e.label() + "|" + ObservationState.CONFIRMED.label();
    }

    private static String formatTracked(Tracked t) {
        String why = t.rejectReason == null ? "" : " " + t.rejectReason;
        return t.id + " " + t.name + " " + t.execution.label() + " observation " + t.observation.label() + why;
    }

    private static String formatSent(Sent s) {
        if (s.gaveUp && !s.acked) {
            return s.id + " gave up at deadline; no ack";
        }
        if (!s.acked) {
            if (s.observation == ObservationState.UNKNOWN) {
                return s.id + " to " + s.target + " " + (s.execution == null ? "awaiting ack" : s.execution.label()) // Ostinato:
                        + " observation unknown (sends " + s.sends + ")"; // Ostinato: observation never invents execution.
            }
            return s.id + " to " + s.target + " awaiting ack (sends " + s.sends + ")";
        }
        String why = s.ackReason == null || s.ackReason.isEmpty() ? "" : " " + s.ackReason;
        String exec = s.execution == null ? "accepted" : s.execution.label();
        String obs = s.observation == null ? "unknown" : s.observation.label();
        return s.id + " " + (s.accepted ? "accepted" : "rejected") + why + " " + exec + " observation " + obs;
    }

    public Sent lookupSent(UUID id) {
        synchronized (lock) {
            return outbound.get(id);
        }
    }

    public Tracked lookup(UUID id) {
        synchronized (lock) {
            return ledger.get(id);
        }
    }

    /** Transport accept is not an ack. The body must be identical across retries. */
    public interface Wire {
        boolean send(String to, String type, String body);

        // Ostinato: keep existing lambda wires source-compatible.
        default boolean send(String to, String type, String body, SwarmPriority priority) {
            return send(to, type, body);
        }
    }

    /** Runs a command that has already passed auth, dedup and the safety check. */
    public interface Executor {
        /** Null when this bot does not know the command. */
        CommandPermission permissionOf(String name);

        /** Registry metadata. Null when this bot does not know the command. */
        default CommandCatalog.Entry describe(String name) {
            CommandPermission perm = permissionOf(name);
            if (perm == null) {
                return null;
            }
            return CommandCatalog.infer(name, perm);
        }

        boolean start(UUID id, String name, String args);

        boolean confirmStopped(UUID id);

        /** Null while still running. Terminal values are verified or confirmed results. */
        ExecutionState poll(UUID id);
    }

    /** Owner name, AI dry-run and the tool whitelist. Empty owner means "this bot". */
    public static final class Policy {
        public final String ownerName;
        public final boolean ownerOnly;
        public final boolean dryRun;
        public final String toolCsv;

        public Policy(String ownerName, boolean ownerOnly, boolean dryRun, String toolCsv) {
            this.ownerName = ownerName == null ? "" : ownerName;
            this.ownerOnly = ownerOnly;
            this.dryRun = dryRun;
            this.toolCsv = toolCsv == null ? AgentPolicy.DEFAULT_TOOLS : toolCsv;
        }

        public static Policy defaults() {
            return new Policy("", true, false, AgentPolicy.DEFAULT_TOOLS);
        }
    }

    /** Stable id: the SIGIL roster member, plus the signet key id pinned for that member. */
    public static final class BotIdentity {
        private final String memberId;
        private final String signetKeyId;

        public BotIdentity(String memberId, String signetKeyId) {
            if (!validToken(memberId)) {
                throw new IllegalArgumentException("bad bot id");
            }
            this.memberId = memberId;
            this.signetKeyId = signetKeyId == null || signetKeyId.isBlank() ? "unsigned" : signetKeyId;
        }

        public String memberId() {
            return memberId;
        }

        public String signetKeyId() {
            return signetKeyId;
        }
    }

    public static final class Tracked {
        public final UUID id;
        public final String name;
        public final String payloadHash;
        public String issuer; // Ostinato: optional for legacy Tracked construction.
        public ExecutionState execution;
        public ObservationState observation;
        public String rejectReason;
        public final boolean started;
        public boolean ambiguous;
        public boolean worldMutation;
        public long storedAtMs;

        Tracked(UUID id, String name, String payloadHash, ExecutionState execution, ObservationState observation,
                boolean started) {
            this.id = id;
            this.name = name;
            this.payloadHash = payloadHash;
            this.execution = execution;
            this.observation = observation;
            this.started = started;
        }
    }

    public static final class Sent {
        public final UUID id;
        public final String target;
        public final String body;
        public final long deadline;
        public int sends;
        public long nextRetry;
        public boolean acked;
        public boolean accepted;
        public boolean gaveUp;
        public String ackReason;
        public ExecutionState execution;
        public ObservationState observation;

        Sent(UUID id, String target, String body, long deadline) {
            this.id = id;
            this.target = target;
            this.body = body;
            this.deadline = deadline;
        }
    }

    private enum Kind { ACK_ONLY, START, CANCEL }

    private static final class Decision {
        final Kind kind;
        final UUID id;
        final String name;
        final String args;
        final UUID cancelTarget;
        final boolean askStop;
        final String replyTo;
        final String ack;

        private Decision(Kind kind, UUID id, String name, String args, UUID cancelTarget, boolean askStop,
                         String replyTo, String ack) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.args = args;
            this.cancelTarget = cancelTarget;
            this.askStop = askStop;
            this.replyTo = replyTo;
            this.ack = ack;
        }

        static Decision ack(String to, String body) {
            return new Decision(Kind.ACK_ONLY, null, null, null, null, false, to, body);
        }

        static Decision start(UUID id, String name, String args, String from) {
            return new Decision(Kind.START, id, name, args, null, false, from, null);
        }

        static Decision cancel(UUID id, UUID target, String from, boolean ask) {
            return new Decision(Kind.CANCEL, id, "cancel", null, target, ask, from, null);
        }
    }

    private static final class Parsed {
        final UUID id;
        final String issuer;
        final String target;
        final String name;
        final String args;
        final long issuedAt;
        final long deadline;
        final boolean dryRun;
        final String hash;

        Parsed(UUID id, String issuer, String target, String name, String args, long issuedAt, long deadline,
               boolean dryRun, String hash) {
            this.id = id;
            this.issuer = issuer;
            this.target = target;
            this.name = name;
            this.args = args;
            this.issuedAt = issuedAt;
            this.deadline = deadline;
            this.dryRun = dryRun;
            this.hash = hash;
        }

        static Parsed parse(String body) {
            String[] f = body.split("\\|", -1);
            if (f.length != 10 || !"1".equals(f[0]) || !"cmd".equals(f[1])) {
                throw new IllegalArgumentException("shape");
            }
            UUID id = UUID.fromString(f[2]);
            String issuer = f[3];
            String target = f[4];
            String name = f[5];
            byte[] raw = Base64.getUrlDecoder().decode(f[6]);
            String args = new String(raw, StandardCharsets.UTF_8);
            long issued = Long.parseLong(f[7]);
            long deadline = Long.parseLong(f[8]);
            boolean dry = "1".equals(f[9]);
            if (!validToken(issuer) || !validToken(target) || !validToken(name)) {
                throw new IllegalArgumentException("token");
            }
            return new Parsed(id, issuer, target, name, args, issued, deadline, dry, sha256(body));
        }
    }

    private static final class Ack {
        final UUID id;
        final boolean accepted;
        final String reason;
        final ExecutionState execution;
        final ObservationState observation;

        Ack(UUID id, boolean accepted, String reason, ExecutionState execution, ObservationState observation) {
            this.id = id;
            this.accepted = accepted;
            this.reason = reason;
            this.execution = execution;
            this.observation = observation;
        }

        static Ack parse(String body) {
            String[] f = body.split("\\|", -1);
            if (f.length != 7 || !"1".equals(f[0]) || !"ack".equals(f[1])) {
                throw new IllegalArgumentException("shape");
            }
            boolean accepted = "accepted".equals(f[3]);
            if (!accepted && !"rejected".equals(f[3])) {
                throw new IllegalArgumentException("verdict");
            }
            return new Ack(UUID.fromString(f[2]), accepted, f[4], ExecutionState.parse(f[5]), ObservationState.parse(f[6]));
        }
    }

    private static String sha256(String body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(d.length * 2);
            for (byte v : d) {
                b.append(Character.forDigit((v >> 4) & 0xF, 16));
                b.append(Character.forDigit(v & 0xF, 16));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class StatusView implements ISwarmStatusView {
        private volatile boolean open = true;

        @Override
        public List<String> lines() {
            if (!open) {
                return Collections.emptyList();
            }
            return statusLines();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
