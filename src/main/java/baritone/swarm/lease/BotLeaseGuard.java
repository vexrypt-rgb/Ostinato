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

package baritone.swarm.lease;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import baritone.swarm.protocol.CommandCatalog;
import baritone.swarm.protocol.CommandProtocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Ostinato: the bot side of Phase 2a leases.
 *
 * <p>Authority comes only from {@code nonce + D} on this bot's own monotonic clock, where {@code nonce} is the
 * time this bot sent the req/renew that the grant echoes. Nothing here trusts the coordinator's clock. When that
 * deadline passes the bot stops the leased process at once, link or no link, and the fence in
 * {@code BaritonePlayerController} denies the leased process any further break, place or container action.
 *
 * <p>Survival (eating, fleeing lava, fighting) never asks the guard: it is not a leased command and is not fenced.
 *
 * <p>Only the designated coordinator ({@code swarmCoordinatorId}, else the circle owner) is listened to; any other
 * sender of coordinator ops is ignored and counted.
 *
 * <p>Locking: guard state is guarded by {@code this}. The protocol calls {@link #state} under its own lock, so
 * the order is protocol, then guard. The guard never calls the protocol while holding its own lock.
 */
public final class BotLeaseGuard implements CommandProtocol.LeaseHooks {

    /** Commands flagged lease-needing on top of the catalog: the Baritone-driven world mutators. */
    public static final Set<String> LEASED_COMMANDS =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList("mine", "tunnel")));

    /**
     * Never leased and never fenced: they drive gameMode directly or are survival. Listed so the bypass is
     * explicit (see the Phase 2a notes): MovementFall, BoatFallController, BastionProcess, BoatProcess and the
     * bastion/boat/farm/build commands.
     */
    public static final List<String> BYPASS = Collections.unmodifiableList(java.util.Arrays.asList(
            "MovementFall", "BoatFallController", "BastionProcess", "BoatProcess",
            "bastion", "boat", "farm", "build"));

    /** Done leases remembered for reconciliation reports. */
    static final long RECONCILE_REPORT_MIN_MS = 120_000L; // Ostinato:
    static final int KEEP_DONE = 64;
    /**
     * Ostinato: hard cap on nonces remembered per lease. Nonces are kept for as long as a GRANT answering them
     * could still be on time ({@code nonce + D > now}); the cap only bounds memory against a flood of re-sends.
     */
    static final int NONCE_CAP = 64;
    /** Ostinato: a repeated OFFER re-sends REQ at most this often. */
    static final long OFFER_REQ_GAP_MS = 5_000L;

    public interface Sender {
        /** Send an SWL body to {@code to}; false when it could not be queued. */
        boolean send(String to, String body);

        /** Ostinato: {@code urgent} sends ahead of other traffic (reconcile replies); same budget. */
        default boolean send(String to, String body, boolean urgent) {
            return send(to, body);
        }
    }

    enum Phase { PENDING, VALID, EXPIRED, REVOKED, DONE }

    static final class Lease {
        final UUID leaseId;
        final String resource;
        final long gen;
        final UUID commandId;
        final String coordinator;
        Phase phase = Phase.PENDING;
        long durationMs;
        long authUntil = Long.MIN_VALUE;
        // Ostinato: chat links take tens of seconds and REQ is re-sent meanwhile. A count-based ring evicted the
        // nonce a late GRANT answered (ore1 gen2: 4 REQs in 42 s against a 3-slot ring), so nonces are now kept
        // by time: every nonce whose GRANT would still be on time is remembered.
        final ArrayDeque<Long> nonces = new ArrayDeque<>();
        long offeredAt;
        long lastReqAt;
        long lastRackAt = Long.MIN_VALUE / 2; // Ostinato: repeat-gap for RACK
        boolean granted;
        long grantedAt; // Ostinato: monotonic receipt of the first GRANT; bounds renewing before the start.
        boolean reqSent;
        boolean grantRejectLogged;
        long nextRenewAt;
        boolean awaitingRenew;
        int renewFailures;
        boolean renewFailLogged;
        // Ostinato: set by an RREQ. The next renew goes out urgent, and its GRANT is logged with the margin left.
        boolean urgentRenew;
        boolean logNextRenew;
        boolean expiryLogged;
        boolean started;
        /** The leased process may still be running: set at start, cleared by a terminal or a confirmed stop. */
        boolean active;
        CommandProtocol startedBy;
        boolean stopConfirmed;
        boolean rackOwed;
        String endReason;
        long endedAt = Long.MIN_VALUE; // Ostinato: when the lease ended here, for the reconcile report filter.
        boolean reportOwed;
        boolean reportSent;
        ExecutionState exec;
        ObservationState obs = ObservationState.UNKNOWN;

        Lease(UUID leaseId, String resource, long gen, UUID commandId, String coordinator) {
            this.leaseId = leaseId;
            this.resource = resource;
            this.gen = gen;
            this.commandId = commandId;
            this.coordinator = coordinator;
        }

        // Ostinato: time-based retention; a nonce older than D can only produce a late GRANT, which is rejected
        // anyway, so dropping it loses nothing.
        void addNonce(long n, long now) {
            while (!nonces.isEmpty() && nonces.peekFirst() + durationMs <= now) {
                nonces.pollFirst();
            }
            while (nonces.size() >= NONCE_CAP) {
                nonces.pollFirst();
            }
            nonces.addLast(n);
        }

        boolean sentNonce(long n) {
            return nonces.contains(n);
        }

        boolean authorized(long now) {
            return phase == Phase.VALID && now < authUntil;
        }
    }

    private final Sender sender;
    private final LongSupplier mono;
    private final Consumer<String> log;
    private final Supplier<String> allowedSource;
    private final Predicate<UUID> stopper;
    private final Set<String> leased;

    private final Map<UUID, Lease> leases = new LinkedHashMap<>();
    private final Map<UUID, Lease> byCommand = new HashMap<>();
    private final Map<String, Long> highWater = new HashMap<>();
    private final Set<String> ignoredLogged = new HashSet<>();
    private volatile CommandProtocol protocol;
    private UUID lastStarted;
    private long ignored;
    private long grantRejects;
    /** Ostinato: RACKs to REVOKEs for unknown leases, by lease and generation: when each last went out. */
    private final java.util.LinkedHashMap<String, Long> unknownRacks = new java.util.LinkedHashMap<String, Long>(16, 0.75f,
            true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
            return size() > 64;
        }
    };
    private long racksSuppressed;
    /**
     * Ostinato: REVOKE carries no D, so an unknown lease uses the spool gap; the coordinator's REVOKE timer
     * (every D/3 >= 20 s; renews every D/6 >= 10 s) is never this fast, so only crossed copies are dropped.
     */
    static final long UNKNOWN_RACK_GAP_MS = LeaseClock.repeatGap(30_000L);

    public BotLeaseGuard(Sender sender, LongSupplier mono, Consumer<String> log, Supplier<String> allowedSource,
                         Predicate<UUID> stopper, Set<String> leased) {
        this.sender = sender;
        this.mono = mono;
        this.log = log;
        this.allowedSource = allowedSource;
        this.stopper = stopper;
        this.leased = leased == null ? LEASED_COMMANDS : leased;
    }

    /** Bind to the protocol of a new link. Leases survive link restarts; the protocol does not. */
    public void attach(CommandProtocol p) {
        protocol = p;
    }

    /** SWL coordinator ops that came from somebody other than the designated coordinator. */
    /** GRANTs from the designated coordinator that granted nothing (late, unknown nonce, stale or ended). */
    public synchronized long grantRejectCount() {
        return grantRejects;
    }

    /** Ostinato: RACKs not re-sent because one for the same lease went out less than {@link LeaseClock#repeatGap} ago. */
    public synchronized long racksSuppressed() {
        return racksSuppressed;
    }

    public synchronized long ignoredCount() {
        return ignored;
    }

    // ---- inbound ----------------------------------------------------------------------------------------

    /** One coordinator op (offer, grant, revoke, rreq) from {@code from}. */
    public void onMessage(String from, LeaseMessage m) {
        if (m == null || !m.op.fromCoordinator()) {
            return;
        }
        List<Runnable> after = new ArrayList<>();
        synchronized (this) {
            String allowed = allowedSource.get();
            if (allowed == null || allowed.isEmpty() || from == null || !allowed.equalsIgnoreCase(from)) {
                ignored++;
                String who = from == null ? "?" : from.toLowerCase(Locale.ROOT);
                if (ignoredLogged.add(who)) {
                    log.accept("swarm-lease: ignored " + m.op.wire() + " from " + from
                            + " (not the designated coordinator " + (allowed == null || allowed.isEmpty()
                            ? "<none>" : allowed) + ")");
                }
                return;
            }
            long now = mono.getAsLong();
            switch (m.op) {
                case OFFER:
                    onOffer(from, m, now, after);
                    break;
                case GRANT:
                    onGrant(m, now, after);
                    break;
                case REVOKE:
                    onRevoke(from, m, after);
                    break;
                case RREQ:
                    onReconcileRequest(from);
                    break;
                default:
                    break;
            }
        }
        for (Runnable r : after) {
            r.run();
        }
    }

    private void onOffer(String from, LeaseMessage m, long now, List<Runnable> after) {
        if (m.leaseId == null || m.commandId == null || !LeaseMessage.validResource(m.resource)) {
            return;
        }
        long hw = highWater.getOrDefault(m.resource, 0L);
        Lease l = leases.get(m.leaseId);
        if (l == null) {
            if (m.gen < hw) {
                log.accept("swarm-lease: stale-offer lease=" + m.leaseId + " gen=" + m.gen + " bot=self hw=" + hw);
                return;
            }
            Lease clash = byCommand.get(m.commandId);
            if (clash != null) {
                return; // One lease per command id.
            }
            // A newer generation on the same resource supersedes anything older this bot still holds.
            for (Lease old : leases.values()) {
                if (old.resource.equals(m.resource) && old.gen < m.gen
                        && (old.phase == Phase.PENDING || old.phase == Phase.VALID)) {
                    endLease(old, Phase.EXPIRED, "lease-superseded", after);
                }
            }
            highWater.put(m.resource, Math.max(hw, m.gen));
            l = new Lease(m.leaseId, m.resource, m.gen, m.commandId, from);
            l.durationMs = Math.max(LeaseClock.MIN_DURATION_MS, m.durationMs);
            l.offeredAt = now;
            leases.put(l.leaseId, l);
            byCommand.put(l.commandId, l);
            log.accept("swarm-lease: offered lease=" + l.leaseId + " gen=" + l.gen + " bot=self resource=" + l.resource);
            prune();
        }
        if (l.gen != m.gen || !l.commandId.equals(m.commandId)) {
            return;
        }
        if ((l.phase == Phase.PENDING || l.phase == Phase.VALID)
                && (!l.reqSent || now - l.lastReqAt >= OFFER_REQ_GAP_MS)) {
            sendReq(l, LeaseMessage.Op.REQ, now); // A repeated offer re-sends the request, at most every 5 s.
        }
    }

    private void onGrant(LeaseMessage m, long now, List<Runnable> after) {
        Lease l = m.leaseId == null ? null : leases.get(m.leaseId);
        if (l == null) {
            rejectGrant(null, m, "unknown-lease", now);
            return;
        }
        if (l.gen != m.gen || m.commandId == null || !l.commandId.equals(m.commandId)) {
            rejectGrant(l, m, "stale", now);
            return;
        }
        if (l.phase != Phase.PENDING && l.phase != Phase.VALID) {
            rejectGrant(l, m, "phase-" + l.phase.name().toLowerCase(Locale.ROOT), now);
            return; // Expired, revoked or done never come back.
        }
        if (!l.sentNonce(m.nonce)) {
            rejectGrant(l, m, "unknown-nonce", now);
            return; // Not an answer to anything this bot sent.
        }
        long until = LeaseClock.botAuthUntil(m.nonce, m.durationMs);
        if (until <= now) {
            rejectGrant(l, m, "late", now);
            return; // Arrived after its own deadline: grants nothing.
        }
        long left = l.authUntil - now; // Ostinato: margin this grant was renewed with, measured before it extends
        l.durationMs = Math.max(LeaseClock.MIN_DURATION_MS, m.durationMs);
        l.authUntil = Math.max(l.authUntil, until);
        boolean first = !l.granted;
        if (first) {
            l.grantedAt = now;
        }
        l.granted = true;
        l.phase = Phase.VALID;
        l.awaitingRenew = false;
        if (!first && l.logNextRenew) {
            // Ostinato: the first renew after a coordinator relaunch, with the time that was left on the lease.
            l.logNextRenew = false;
            log.accept("swarm-lease: renewed lease=" + l.leaseId + " gen=" + l.gen + " bot=self after-reconcile"
                    + " deadline in " + left + " ms (unanswered=" + l.renewFailures + ")");
        } else if (l.renewFailures > 0) {
            log.accept("swarm-lease: renewed lease=" + l.leaseId + " gen=" + l.gen + " bot=self after "
                    + l.renewFailures + " unanswered renew(s), deadline in " + left + " ms");
        }
        l.renewFailures = 0;
        l.renewFailLogged = false;
        // Ostinato: schedule from the newest RENEW sent, not the one this GRANT answers. From the echoed nonce, a
        // second RENEW sent while one was unanswered kept two in flight for good whenever renewEvery/2 < RTT <
        // renewEvery (live b12: 8 renews/min per lease on Copper's saturated link instead of 4).
        l.nextRenewAt = Math.max(m.nonce, l.lastReqAt) + LeaseClock.renewEvery(l.durationMs);
        if (first) {
            log.accept("swarm-lease: granted lease=" + l.leaseId + " gen=" + l.gen + " bot=self until+"
                    + (l.authUntil - now) + "ms");
            UUID cmd = l.commandId;
            after.add(() -> {
                CommandProtocol p = protocol;
                if (p != null) {
                    p.leaseGranted(cmd);
                }
            });
        }
    }

    /** Ostinato: count every GRANT that grants nothing; log the first per lease (and once for unknown leases). */
    private void rejectGrant(Lease l, LeaseMessage m, String reason, long now) {
        grantRejects++;
        if (l != null && l.grantRejectLogged) {
            return;
        }
        if (l == null && !ignoredLogged.add("grant:" + m.leaseId)) {
            return;
        }
        if (l != null) {
            l.grantRejectLogged = true;
        }
        log.accept("swarm-lease: grant-rejected lease=" + m.leaseId + " gen=" + m.gen + " bot=self reason=" + reason
                + " age=" + (now - m.nonce) + "ms");
    }

    private void onRevoke(String from, LeaseMessage m, List<Runnable> after) {
        Lease l = m.leaseId == null ? null : leases.get(m.leaseId);
        long now = mono.getAsLong();
        if (l == null) {
            // Unknown here (a relaunch, or never offered): nothing runs under it, so ack at once.
            // Ostinato: once per repeat gap; the coordinator re-sends REVOKE on its own timer if this one is lost.
            String key = m.leaseId + "|" + m.gen;
            Long last = unknownRacks.get(key);
            if (last != null && now - last < UNKNOWN_RACK_GAP_MS) {
                racksSuppressed++;
                return;
            }
            if (send(from, new LeaseMessage(LeaseMessage.Op.RACK, m.leaseId, m.resource, m.gen, m.commandId, 0, 0,
                    "unknown"))) {
                unknownRacks.put(key, now);
            }
            return;
        }
        if (l.gen != m.gen) {
            return;
        }
        if (l.phase == Phase.DONE || (l.phase == Phase.EXPIRED && !l.active)) {
            l.phase = Phase.DONE;
            // Ostinato: a REVOKE that crossed our RACK in flight gets no second RACK; a timer resend after the gap does.
            if (now - l.lastRackAt < LeaseClock.repeatGap(l.durationMs)) {
                racksSuppressed++;
                return;
            }
            sendRack(l);
            return;
        }
        if (l.phase != Phase.REVOKED) {
            endLease(l, Phase.REVOKED, "lease-revoked", after);
        }
        l.rackOwed = true;
        if (!l.active) {
            sendRack(l);
        }
    }

    // Ostinato: how long after its end a finished lease is still reported to a reconciling coordinator.
    static long reconcileReportWindow(Lease l) {
        return Math.max(RECONCILE_REPORT_MIN_MS, 2 * l.durationMs);
    }

    private void markEnded(Lease l) {
        if (l.endedAt == Long.MIN_VALUE) {
            l.endedAt = mono.getAsLong();
        }
    }

    private void onReconcileRequest(String from) {
        long now = mono.getAsLong();
        for (Lease l : leases.values()) {
            refresh(l, now);
            // Ostinato: a lease that ended long ago is kept only for duplicate detection; the coordinator expired or
            // released it before it went down, and an unreported record ends as reconcile-unclaimed anyway. Reporting
            // all KEEP_DONE of them on every RREQ resend overran the chat budget (~40 lines/min, live 11-12), so the
            // live lease's report came 62 s late and the hello replies the relaunched coordinator waited on never
            // left the queue. A recent end is still reported: its record may be one the coordinator is holding.
            if (l.phase == Phase.DONE && !l.active && l.endedAt != Long.MIN_VALUE
                    && now - l.endedAt > reconcileReportWindow(l)) {
                continue;
            }
            send(from, report(l), true);
            // Ostinato: the restarted coordinator never saw the renew we were waiting on, and the backoff can put
            // our next renew past the deadline (live b5: the lease was kept, then fenced 9 s later). Renew on the
            // next tick, behind the report; the coordinator holds it until reconciliation ends.
            // Ostinato: every valid lease renews now, not only one waiting on a renew: the relaunched coordinator
            // grants it early once our report is in, so the margin no longer depends on where the cadence was.
            if (l.phase == Phase.VALID) {
                l.awaitingRenew = false;
                l.nextRenewAt = Math.min(l.nextRenewAt, now);
                l.urgentRenew = true;
                l.logNextRenew = true;
            }
        }
        send(from, new LeaseMessage(LeaseMessage.Op.REPORT, null, "-", 0, null, 0, 0, "end"), true);
    }

    // ---- tick -------------------------------------------------------------------------------------------

    /** Once per client tick while leases are on, link or no link. */
    public void tick() {
        CommandProtocol p = protocol;
        boolean up = p != null && p.linkUp();
        List<Runnable> after = new ArrayList<>();
        List<UUID> toStop = new ArrayList<>();
        Set<UUID> settled = up ? settledBeforeStart(p) : Collections.emptySet();
        synchronized (this) {
            long now = mono.getAsLong();
            for (Lease l : leases.values()) {
                refresh(l, now);
                switch (l.phase) {
                    case PENDING:
                        if (now - l.offeredAt >= l.durationMs) {
                            log.accept("swarm-lease: dropped lease=" + l.leaseId + " gen=" + l.gen
                                    + " bot=self (no grant within " + l.durationMs + " ms)");
                            endLease(l, Phase.DONE, "offer-lost", after);
                        } else if (up && now - l.lastReqAt >= Math.max(5_000L, LeaseClock.resendEvery(l.durationMs))) {
                            sendReq(l, LeaseMessage.Op.REQ, now);
                        }
                        break;
                    case VALID:
                        if (now >= l.authUntil) {
                            endLease(l, Phase.EXPIRED, "lease-expired", after);
                        } else if (up && now >= l.nextRenewAt
                                && (l.started ? l.active : renewsBeforeStart(l, now, settled))) {
                            if (l.awaitingRenew) {
                                l.renewFailures++;
                                if (!l.renewFailLogged) {
                                    l.renewFailLogged = true;
                                    log.accept("swarm-lease: renew-unanswered lease=" + l.leaseId + " gen=" + l.gen
                                            + " bot=self; stopping at the deadline in " + (l.authUntil - now)
                                            + " ms unless a grant arrives; backing off");
                                }
                            }
                            long wait = LeaseClock.renewEvery(l.durationMs) << Math.min(l.renewFailures, 4);
                            l.nextRenewAt = now + wait;
                            l.awaitingRenew = true;
                            boolean urgent = l.urgentRenew;
                            l.urgentRenew = false;
                            sendReq(l, LeaseMessage.Op.RENEW, now, urgent);
                        }
                        break;
                    default:
                        break;
                }
                if (l.active && !l.authorized(now) && l.phase != Phase.PENDING) {
                    toStop.add(l.commandId);
                }
                if (up) {
                    flush(l);
                }
            }
        }
        for (Runnable r : after) {
            r.run();
        }
        // The guard stops the leased process itself as well: the protocol that started it may be detached.
        for (UUID cmd : toStop) {
            boolean stopped;
            try {
                stopped = stopper.test(cmd);
            } catch (RuntimeException e) {
                stopped = false;
            }
            if (stopped) {
                synchronized (this) {
                    Lease l = byCommand.get(cmd);
                    if (l != null && l.active) {
                        l.active = false;
                        l.stopConfirmed = true;
                        log.accept("swarm-lease: stopped lease=" + l.leaseId + " gen=" + l.gen + " bot=self reason="
                                + l.endReason);
                        if (up) {
                            flush(l);
                        }
                    }
                }
            }
        }
    }

    // Ostinato: a bot renews a lease it holds from the GRANT on, not from the start. The command can reach the bot
    // after its grant (live b10t1: grant at T, start at T+12 s), and a renew deferred to the start shortened the
    // lease a coordinator restart had to survive. Two bounds keep a command that never starts from holding the
    // resource forever: no renew once the protocol settled the command without starting it (rejected, e.g. busy),
    // and none once the first grant is D/2 old. Such a lease then expires at its last deadline, at most 1.5 D
    // after the grant.
    private boolean renewsBeforeStart(Lease l, long now, Set<UUID> settled) {
        return !settled.contains(l.commandId) && now - l.grantedAt < l.durationMs / 2;
    }

    /**
     * Ostinato: commands of granted, not yet started leases that the protocol already settled without starting.
     * Looked up without the guard lock held: the lock order is protocol, then guard.
     */
    private Set<UUID> settledBeforeStart(CommandProtocol p) {
        List<UUID> waiting = null;
        synchronized (this) {
            for (Lease l : leases.values()) {
                if (l.phase == Phase.VALID && !l.started && l.commandId != null) {
                    if (waiting == null) {
                        waiting = new ArrayList<>();
                    }
                    waiting.add(l.commandId);
                }
            }
        }
        if (waiting == null) {
            return Collections.emptySet();
        }
        Set<UUID> out = new HashSet<>();
        for (UUID id : waiting) {
            CommandProtocol.Tracked t = p.lookup(id);
            if (t != null && !t.started && t.execution != null && t.execution.terminal()) {
                out.add(id);
            }
        }
        return out;
    }

    // Caller holds this. Picks up the leased command's outcome from the protocol that started it.
    private void refresh(Lease l, long now) {
        if (!l.started || l.startedBy == null) {
            return;
        }
        CommandProtocol.Tracked t = l.startedBy.lookup(l.commandId);
        if (t == null) {
            return;
        }
        if (t.execution != null) {
            l.exec = t.execution;
            l.obs = t.observation == null ? ObservationState.UNKNOWN : t.observation;
        }
        boolean terminal = t.execution != null && t.execution.terminal();
        if (terminal && l.active) {
            l.active = false; // The executor or a confirmed stop settled it.
            if (t.execution == ExecutionState.CANCELED && t.observation == ObservationState.CONFIRMED) {
                l.stopConfirmed = true;
            }
        }
        if (t.endReason != null && l.endReason == null) {
            l.endReason = t.endReason;
        }
        if (terminal && (l.phase == Phase.VALID || l.phase == Phase.PENDING)) {
            log.accept("swarm-lease: released lease=" + l.leaseId + " gen=" + l.gen + " bot=self exec="
                    + t.execution.label());
            l.phase = Phase.DONE;
            markEnded(l); // Ostinato:
            l.reportOwed = true;
        } else if ((terminal || t.leaseEnded) && !l.reportSent && (l.phase == Phase.EXPIRED || l.phase == Phase.REVOKED)) {
            l.reportOwed = true;
        }
        if (l.phase == Phase.VALID && now >= l.authUntil) {
            return; // tick() expires it.
        }
    }

    // Caller holds this. Ends the lease's authority and asks the protocol to stop or reject its command.
    private void endLease(Lease l, Phase to, String reason, List<Runnable> after) {
        Phase from = l.phase;
        l.phase = to;
        markEnded(l); // Ostinato:
        if (l.endReason == null) {
            l.endReason = reason;
        }
        if (!l.expiryLogged) {
            l.expiryLogged = true;
            log.accept("swarm-lease: " + to.name().toLowerCase(Locale.ROOT) + " lease=" + l.leaseId + " gen="
                    + l.gen + " bot=self reason=" + reason + " was=" + from.name().toLowerCase(Locale.ROOT)
                    + (l.active ? " (stopping the leased process)" : ""));
        }
        l.reportOwed = true;
        UUID cmd = l.commandId;
        after.add(() -> {
            CommandProtocol p = protocol;
            if (p != null) {
                p.leaseExpired(cmd, reason);
            }
            CommandProtocol s = l.startedBy;
            if (s != null && s != p) {
                s.leaseExpired(cmd, reason);
            }
        });
    }

    // Caller holds this. Sends what is owed: the rack once stopped, and the terminal or settled report.
    private void flush(Lease l) {
        if (l.rackOwed && !l.active) {
            sendRack(l);
        }
        if (l.reportOwed && !l.active || l.reportOwed && l.exec != null && l.exec.terminal()) {
            if (send(l.coordinator, report(l))) {
                l.reportOwed = false;
                l.reportSent = true;
            }
        } else if (l.reportOwed && l.active && l.startedBy != null) {
            CommandProtocol.Tracked t = l.startedBy.lookup(l.commandId);
            if (t != null && t.leaseEnded) {
                // The protocol gave up confirming the stop: report running/unknown once; keep stopping.
                if (send(l.coordinator, report(l))) {
                    l.reportOwed = false;
                    l.reportSent = true;
                }
            }
        }
        if (!l.active && !l.rackOwed && !l.reportOwed && (l.phase == Phase.EXPIRED || l.phase == Phase.REVOKED)) {
            l.phase = Phase.DONE;
        }
    }

    private void sendRack(Lease l) {
        if (send(l.coordinator, new LeaseMessage(LeaseMessage.Op.RACK, l.leaseId, l.resource, l.gen, l.commandId,
                0, 0, l.stopConfirmed ? "stopped" : "idle"))) {
            l.lastRackAt = mono.getAsLong(); // Ostinato:
            l.rackOwed = false;
            l.reportOwed = false; // The rack is the release.
            l.reportSent = true;
            log.accept("swarm-lease: rack lease=" + l.leaseId + " gen=" + l.gen + " bot=self");
        }
    }

    private void sendReq(Lease l, LeaseMessage.Op op, long now) {
        sendReq(l, op, now, false);
    }

    private void sendReq(Lease l, LeaseMessage.Op op, long now, boolean urgent) {
        l.addNonce(now, now); // Ostinato: time-based nonce retention
        l.lastReqAt = now;
        l.reqSent = true;
        send(l.coordinator, new LeaseMessage(op, l.leaseId, l.resource, l.gen, l.commandId, now, l.durationMs, "-"),
                urgent);
    }

    /** Ostinato: one value inside the comma-separated SWL extra field: no comma, '=' or space. */
    static String wireToken(String v) {
        String t = v.replaceAll("[^A-Za-z0-9_:.+/-]+", "_");
        if (t.length() > 64) {
            t = t.substring(0, 64);
        }
        return t.isEmpty() ? "none" : t;
    }

    private LeaseMessage report(Lease l) {
        // Ostinato: the exec label of every terminal state ("canceled (confirmed)") has a space and brackets, which
        // the SWL extra field does not allow, so the receiver dropped the whole REPORT: a finished lease was never
        // reported, and still cost a chat line. Send the enum name (ExecutionState.parse accepts it) and keep the
        // free-text stop reason to the wire's charset.
        String extra = "phase=" + l.phase.name().toLowerCase(Locale.ROOT)
                + ",exec=" + (l.exec == null ? "none" : l.exec.name().toLowerCase(Locale.ROOT))
                + ",obs=" + l.obs.name().toLowerCase(Locale.ROOT)
                + ",reason=" + (l.endReason == null ? "none" : wireToken(l.endReason))
                + ",active=" + l.active;
        return new LeaseMessage(LeaseMessage.Op.REPORT, l.leaseId, l.resource, l.gen, l.commandId, 0, 0, extra);
    }

    private boolean send(String to, LeaseMessage m) {
        return send(to, m, false);
    }

    private boolean send(String to, LeaseMessage m, boolean urgent) {
        try {
            return urgent ? sender.send(to, m.encode(), true) : sender.send(to, m.encode());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void prune() {
        int done = 0;
        for (Lease l : leases.values()) {
            if (l.phase == Phase.DONE) {
                done++;
            }
        }
        for (Iterator<Lease> it = leases.values().iterator(); it.hasNext() && done > KEEP_DONE; ) {
            Lease l = it.next();
            if (l.phase == Phase.DONE && !l.active) {
                it.remove();
                byCommand.remove(l.commandId);
                done--;
            }
        }
    }

    // ---- hooks ------------------------------------------------------------------------------------------

    @Override
    public boolean needsLease(String name, CommandCatalog.Entry entry) {
        return (entry != null && entry.leaseNeeding) || (name != null && leased.contains(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public synchronized CommandProtocol.LeaseState state(UUID commandId) {
        Lease l = commandId == null ? null : byCommand.get(commandId);
        if (l == null || l.phase == Phase.PENDING) {
            return CommandProtocol.LeaseState.NONE;
        }
        return l.authorized(mono.getAsLong()) ? CommandProtocol.LeaseState.VALID : CommandProtocol.LeaseState.EXPIRED;
    }

    @Override
    public synchronized void started(UUID commandId, String name) {
        lastStarted = commandId;
        Lease l = byCommand.get(commandId);
        if (l != null) {
            l.started = true;
            l.active = true;
            l.startedBy = protocol;
            l.exec = ExecutionState.RUNNING;
            l.obs = ObservationState.CONFIRMED;
            log.accept("swarm-lease: started lease=" + l.leaseId + " gen=" + l.gen + " bot=self cmd=" + name);
        }
        // A later command took over the control slot: an older leased process it displaced is not ours to chase.
        for (Lease o : leases.values()) {
            if (o != l && o.active && !o.authorized(mono.getAsLong())) {
                o.active = false;
                o.reportOwed = true;
            }
        }
    }

    @Override
    public boolean stopLeased(UUID commandId) {
        boolean stopped = stopper.test(commandId);
        if (stopped) {
            synchronized (this) {
                Lease l = byCommand.get(commandId);
                if (l != null && l.active) {
                    l.active = false;
                    l.stopConfirmed = true;
                }
            }
        }
        return stopped;
    }

    /**
     * For the fence: a leased execution may still be running and its authorisation is gone (expired, revoked,
     * or past this bot's deadline before the tick noticed).
     */
    public synchronized boolean invalidActive() {
        if (leases.isEmpty()) {
            return false;
        }
        long now = mono.getAsLong();
        for (Lease l : leases.values()) {
            if (l.active && !l.authorized(now)) {
                return true;
            }
        }
        return false;
    }

    /** For status: one line per live lease. */
    public synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        long now = mono.getAsLong();
        for (Lease l : leases.values()) {
            if (l.phase == Phase.DONE) {
                continue;
            }
            out.add("lease=" + l.leaseId + " gen=" + l.gen + " resource=" + l.resource + " phase="
                    + l.phase.name().toLowerCase(Locale.ROOT) + (l.phase == Phase.VALID ? " left="
                    + Math.max(0, l.authUntil - now) + "ms" : "") + (l.active ? " active" : ""));
        }
        return out;
    }

    // ---- test access ------------------------------------------------------------------------------------

    synchronized Phase phaseOf(UUID leaseId) {
        Lease l = leases.get(leaseId);
        return l == null ? null : l.phase;
    }

    synchronized boolean activeOf(UUID leaseId) {
        Lease l = leases.get(leaseId);
        return l != null && l.active;
    }
}
