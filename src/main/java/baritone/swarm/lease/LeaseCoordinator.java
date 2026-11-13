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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Ostinato: the Phase 2a lease coordinator. It runs in one client and talks over the existing swarm transport.
 *
 * <p>Safety rules:
 * <ul>
 *     <li>Every grant is persisted (state and wall-clock {@code notBefore}) before it is sent. No persist, no grant.</li>
 *     <li>A lease counts as live until {@code lastGrantSend + D + M} on this client's monotonic clock and until its
 *     durable {@code notBefore}. A replacement is offered only after both have passed, or after the holder acked a
 *     revoke ({@code rack}). A revoke without a rack never shortens that wait.</li>
 *     <li>After a restart nothing is granted until reconciliation finishes.</li>
 *     <li>Only the designated coordinator ({@code swarmCoordinatorId}, else the circle owner) goes active.</li>
 * </ul>
 *
 * <p>Log lines: {@code swarm-lease: <state> lease=<id> gen=<n> bot=<member>}, one per state change.
 */
public final class LeaseCoordinator {

    public enum Mode { IDLE, RECONCILING, ACTIVE, REFUSED }

    public interface Commands {
        /** Send the bound command with the lease's commandId. Called outside the coordinator lock. */
        String submitLeased(String bot, String commandLine, UUID commandId);

        /** Ostinato: why the command line would be refused if sent now, or null. Called outside the lock. */
        String refusal(String bot, String commandLine);

        /** Ostinato: the lease bound to this command ended and its fence passed. Called outside the lock. */
        default void fenced(UUID commandId) {
        }
    }

    /** Prefix of {@link Commands#submitLeased} output when the command was queued. */
    static final String SENT_PREFIX = "swarm: sent ";

    /** Default allowance for clock skew between restarts (wall clock only). */
    public static final long DEFAULT_SKEW_MS = 2_000L;

    private final String self;
    private final Supplier<String> designated;
    private final CoordinatorStore store;
    private final BotLeaseGuard.Sender sender;
    private final Commands commands;
    private final LongSupplier mono;
    private final LongSupplier wall;
    private final Consumer<String> log;
    private final LongSupplier configuredDurationMs;
    private final long marginMs;
    private final long skewMs;
    private final Predicate<String> chatPeer;
    private final Supplier<Collection<String>> peers;

    private Mode mode = Mode.IDLE;
    private String refusedReason;
    private final Map<String, Long> highWater = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, LeaseRecord> records = new LinkedHashMap<>();
    /** Per lease: the duration sent with its last offer or grant. */
    private final Map<UUID, Long> durations = new HashMap<>();
    private final Map<UUID, Long> lastResend = new HashMap<>();
    /** Ostinato: when each denial (REVOKE to an unknown, stale or wrong-state request) last went out, by bot and lease. */
    private final LinkedHashMap<String, Long> lastDenied = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
            return size() > 128;
        }
    };
    private long repliesSuppressed;

    // Reconciliation.
    private long reconcileStartMono;
    private long lastRreqMono;
    private final Map<String, Long> holderNotBefore = new HashMap<>(); // lower-cased bot -> latest notBeforeWall
    private final List<UUID> prunedFenced = new ArrayList<>(); // Ostinato: fenced commands of pruned records
    private final Set<String> reported = new HashSet<>();
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final List<Object[]> heldRequests = new ArrayList<>(); // {from, LeaseMessage}

    private final Set<String> rivalsLogged = new HashSet<>();
    private long rivalOps;
    private long grants;
    private long renewals;
    private long earlyRenewals; // Ostinato: renews granted while reconciling

    private static final class Claim {
        final String bot;
        final LeaseMessage m;
        final Map<String, String> extra;

        Claim(String bot, LeaseMessage m, Map<String, String> extra) {
            this.bot = bot;
            this.m = m;
            this.extra = extra;
        }
    }

    public LeaseCoordinator(String self, Supplier<String> designated, CoordinatorStore store,
                            BotLeaseGuard.Sender sender, Commands commands, LongSupplier mono, LongSupplier wall,
                            Consumer<String> log, LongSupplier configuredDurationMs, long marginMs, long skewMs,
                            Predicate<String> chatPeer, Supplier<Collection<String>> peers) {
        this.self = self;
        this.designated = designated;
        this.store = store;
        this.sender = sender;
        this.commands = commands;
        this.mono = mono;
        this.wall = wall;
        this.log = log;
        this.configuredDurationMs = configuredDurationMs;
        this.marginMs = Math.max(0, marginMs);
        this.skewMs = Math.max(0, skewMs);
        this.chatPeer = chatPeer;
        this.peers = peers;
    }

    // ---- lifecycle --------------------------------------------------------------------------------------

    /** Load the store and reconcile. Refuses (and logs why) when this client is not the designated coordinator. */
    public synchronized Mode start() {
        if (mode != Mode.IDLE) {
            return mode;
        }
        String d = designated.get();
        if (d == null || d.isEmpty()) {
            return refuse("no designated coordinator (set swarmCoordinatorId or agentOwner)");
        }
        if (self == null || !d.equalsIgnoreCase(self)) {
            return refuse("this client (" + self + ") is not the designated coordinator " + d
                    + "; bots accept leases only from " + d);
        }
        CoordinatorStore.Snapshot snap;
        try {
            snap = store.load();
        } catch (IOException e) {
            return refuse("store unreadable: " + e.getMessage());
        }
        highWater.putAll(snap.highWater);
        for (LeaseRecord r : snap.leases) {
            records.put(r.leaseId, r);
        }
        log.accept("swarm-lease: coordinator " + store.describe() + " loaded leases=" + records.size()
                + " resources=" + highWater.size());
        long now = mono.getAsLong();
        mode = Mode.RECONCILING;
        reconcileStartMono = now;
        for (LeaseRecord r : records.values()) {
            if (r.state.live()) {
                holderNotBefore.merge(r.bot.toLowerCase(Locale.ROOT), r.notBeforeWall, Math::max);
            }
        }
        log.accept("swarm-lease: reconciling holders=" + holderNotBefore.keySet() + " bot=*");
        sendRreq(now);
        return mode;
    }

    private Mode refuse(String why) {
        mode = Mode.REFUSED;
        refusedReason = why;
        log.accept("swarm-lease: refused coordinator=" + self + " reason=" + why);
        return mode;
    }

    public String self() {
        return self;
    }

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized String refusedReason() {
        return refusedReason;
    }

    // ---- inbound ----------------------------------------------------------------------------------------

    /** A bot op (req, renew, rack, report). */
    public void onMessage(String from, LeaseMessage m) {
        if (m == null || from == null) {
            return;
        }
        if (m.op.fromCoordinator()) {
            noteRival(from, m);
            return;
        }
        List<Runnable> after = new ArrayList<>();
        synchronized (this) {
            switch (mode) {
                case RECONCILING:
                    if (m.op == LeaseMessage.Op.REPORT) {
                        onReconcileReport(from, m, after);
                    } else if (m.op == LeaseMessage.Op.RENEW && reported.contains(from.toLowerCase(Locale.ROOT))
                            && tryEarlyRenew(from, m, after)) {
                        break; // Ostinato: renewed now, not held until reconciliation ends.
                    } else if (m.op == LeaseMessage.Op.REQ || m.op == LeaseMessage.Op.RENEW) {
                        heldRequests.add(new Object[]{from, m});
                        log.accept("swarm-lease: grant-held lease=" + m.leaseId + " gen=" + m.gen + " bot=" + from
                                + " (reconciling)");
                    } else if (m.op == LeaseMessage.Op.RACK) {
                        onRack(from, m);
                    }
                    break;
                case ACTIVE:
                    handleActive(from, m, after);
                    break;
                default:
                    break;
            }
        }
        for (Runnable r : after) {
            r.run();
        }
    }

    /** Another member sent coordinator ops. Counted and logged once per member; never acted on. */
    private synchronized void noteRival(String from, LeaseMessage m) {
        if (from.equalsIgnoreCase(self)) {
            return;
        }
        rivalOps++;
        if (rivalsLogged.add(from.toLowerCase(Locale.ROOT))) {
            log.accept("swarm-lease: rival-coordinator bot=" + from + " op=" + m.op.wire() + " mode="
                    + mode.name().toLowerCase(Locale.ROOT) + " (bots follow only " + designated.get() + ")");
        }
    }

    private void handleActive(String from, LeaseMessage m, List<Runnable> after) {
        handleActive(from, m, after, false);
    }

    /** Ostinato: {@code urgent} for requests held through reconciliation; their GRANTs go out ahead of the queue. */
    private void handleActive(String from, LeaseMessage m, List<Runnable> after, boolean urgent) {
        switch (m.op) {
            case REQ:
            case RENEW:
                onRequest(from, m, after, urgent);
                break;
            case RACK:
                onRack(from, m);
                break;
            case REPORT:
                onReport(from, m);
                break;
            default:
                break;
        }
    }

    private void onRequest(String from, LeaseMessage m, List<Runnable> after, boolean urgent) {
        LeaseRecord r = m.leaseId == null ? null : records.get(m.leaseId);
        long now = mono.getAsLong();
        if (r == null || !r.bot.equalsIgnoreCase(from) || r.gen != m.gen || m.commandId == null
                || !r.commandId.equals(m.commandId)) {
            if (denyDue(from, m.leaseId, m.gen, now)) {
                sendRevoke(from, m.leaseId, m.resource, m.gen, m.commandId);
                log.accept("swarm-lease: denied lease=" + m.leaseId + " gen=" + m.gen + " bot=" + from
                        + " (unknown or stale generation)");
            }
            return;
        }
        long d = durationFor(r.bot);
        if (r.state == LeaseRecord.State.REVOKING) {
            // Ostinato: the REVOKE timer in expire() repeats it; a REQ that crossed it in flight gets no extra copy.
            if (now - lastResend.getOrDefault(r.leaseId, Long.MIN_VALUE / 2) >= LeaseClock.repeatGap(d)) {
                lastResend.put(r.leaseId, now);
                sendRevoke(r.bot, r.leaseId, r.resource, r.gen, r.commandId);
            } else {
                repliesSuppressed++;
            }
            return;
        }
        boolean renew = m.op == LeaseMessage.Op.RENEW;
        if (!(r.state == LeaseRecord.State.GRANTED || (!renew && r.state == LeaseRecord.State.OFFERED))) {
            if (denyDue(r.bot, r.leaseId, r.gen, now)) {
                sendRevoke(r.bot, r.leaseId, r.resource, r.gen, r.commandId);
                log.accept("swarm-lease: denied lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot + " state="
                        + r.state.name().toLowerCase(Locale.ROOT));
            }
            return;
        }
        LeaseRecord.State before = r.state;
        // Ostinato: a RENEW on a lease this coordinator never granted is the holder's reconcile renew (the RREQ made
        // it). Live b9r1 it landed just after reconciliation ended, went out HIGH and waited 6 s behind the caps
        // query, past the lease's deadline. Its GRANT goes URGENT however late in reconciliation it arrives.
        urgent |= renew && r.lastGrantMono == Long.MIN_VALUE;
        if (!grant(r, m, d, now, urgent)) {
            return;
        }
        if (before == LeaseRecord.State.OFFERED) {
            grants++;
            log.accept("swarm-lease: granted lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot + " resource="
                    + r.resource + " D=" + d + "ms");
        } else {
            renewals++;
        }
        if (!r.commandSent) {
            // The grant goes first; the bound command follows it.
            r.commandSent = true;
            String bot = r.bot;
            String line = r.commandLine;
            UUID cmd = r.commandId;
            UUID leaseId = r.leaseId;
            long gen = r.gen;
            after.add(() -> {
                String out = commands.submitLeased(bot, line, cmd);
                if (out != null && !out.isEmpty()) {
                    log.accept(out);
                }
                if (out == null || !out.startsWith(SENT_PREFIX)) {
                    commandRefused(leaseId, gen); // Ostinato: never leave a grant with no command behind it.
                }
            });
        }
    }

    /**
     * Ostinato: the grant core shared by {@link #onRequest} and {@link #tryEarlyRenew}: persist GRANTED and the new
     * notBefore first, then send. False (state restored, nothing sent) when the store write failed.
     */
    private boolean grant(LeaseRecord r, LeaseMessage m, long d, long now, boolean urgent) {
        LeaseRecord.State before = r.state;
        long nbBefore = r.notBeforeWall;
        r.state = LeaseRecord.State.GRANTED;
        r.notBeforeWall = Math.max(r.notBeforeWall, LeaseClock.notBeforeWall(wall.getAsLong(), d, marginMs, skewMs));
        if (!persist()) {
            r.state = before;
            r.notBeforeWall = nbBefore;
            log.accept("swarm-lease: grant-withheld lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot
                    + " (store write failed)");
            return false;
        }
        r.lastGrantMono = now;
        r.coordDeadlineMono = LeaseClock.coordDeadline(now, d, marginMs);
        durations.put(r.leaseId, d);
        send(r.bot, new LeaseMessage(LeaseMessage.Op.GRANT, r.leaseId, r.resource, r.gen, r.commandId, m.nonce, d, "-"),
                urgent);
        return true;
    }

    /**
     * Ostinato: renew a reported holder's lease while reconciliation still waits on other bots. Live b8r3 kept a
     * lease and fenced it 4 s past its deadline because its renew sat in heldRequests until every holder reported.
     * Only a lease this store already holds is renewed early: at exactly the stored generation, for exactly the bot
     * it was granted to, at the resource's high-water mark, and only when that bot's own report claims it valid and
     * running with no other claim on the resource. Anything else stays held for {@link #finishReconcile}. Never a
     * REQ, never an OFFERED or unknown lease, and never a command submit.
     */
    private boolean tryEarlyRenew(String from, LeaseMessage m, List<Runnable> after) {
        if (m.op != LeaseMessage.Op.RENEW || m.leaseId == null || m.commandId == null) {
            return false;
        }
        LeaseRecord r = records.get(m.leaseId);
        if (r == null || !r.bot.equalsIgnoreCase(from) || r.gen != m.gen || !r.commandId.equals(m.commandId)
                || r.state != LeaseRecord.State.GRANTED || highWater.getOrDefault(r.resource, 0L) != r.gen) {
            return false;
        }
        Claim c = claims.get(r.leaseId);
        if (c == null || !c.bot.equalsIgnoreCase(r.bot) || c.m.gen != r.gen || c.m.commandId == null
                || !c.m.commandId.equals(r.commandId) || !"valid".equals(c.extra.get("phase"))
                || !("true".equals(c.extra.get("active")) || !"none".equals(c.extra.getOrDefault("exec", "none")))) {
            return false;
        }
        for (Claim o : claims.values()) {
            if (o != c && r.resource.equals(o.m.resource)) {
                return false; // Another claim on the resource: finishReconcile decides.
            }
        }
        long d = durationFor(r.bot);
        boolean sentBefore = r.commandSent;
        r.commandSent = true; // The holder already runs the command; reconciliation never submits one.
        if (!grant(r, m, d, mono.getAsLong(), true)) {
            r.commandSent = sentBefore;
            return false;
        }
        renewals++;
        earlyRenewals++;
        log.accept("swarm-lease: renewed-early lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot
                + " (reconciling)");
        return true;
    }

    /** Ostinato: renews granted while reconciliation was still running. */
    public synchronized long earlyRenewals() {
        return earlyRenewals;
    }

    /** The bound command was refused after the grant: revoke, so the resource frees on rack or expiry. */
    private synchronized void commandRefused(UUID leaseId, long gen) {
        LeaseRecord r = records.get(leaseId);
        if (r == null || r.gen != gen || r.state != LeaseRecord.State.GRANTED) {
            return;
        }
        r.state = LeaseRecord.State.REVOKING;
        r.endReason = "command-refused";
        persist();
        lastResend.put(r.leaseId, mono.getAsLong());
        sendRevoke(r.bot, r.leaseId, r.resource, r.gen, r.commandId);
        log.accept("swarm-lease: revoking lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot
                + " reason=command-refused");
    }

    private void onRack(String from, LeaseMessage m) {
        LeaseRecord r = m.leaseId == null ? null : records.get(m.leaseId);
        if (r == null || !r.bot.equalsIgnoreCase(from) || r.gen != m.gen) {
            return;
        }
        if (!r.state.live()) {
            return;
        }
        r.state = LeaseRecord.State.RELEASED;
        if (r.endReason == null) {
            r.endReason = "rack";
        }
        if ("stopped".equals(m.extra)) {
            r.exec = ExecutionState.CANCELED;
            r.obs = ObservationState.CONFIRMED;
        }
        persist();
        log.accept("swarm-lease: released lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot + " (rack "
                + m.extra + ")");
    }

    private void onReport(String from, LeaseMessage m) {
        if (m.leaseId == null) {
            return; // End marker outside reconciliation.
        }
        LeaseRecord r = records.get(m.leaseId);
        if (r == null || !r.bot.equalsIgnoreCase(from) || r.gen != m.gen) {
            return;
        }
        Map<String, String> x = parseExtra(m.extra);
        applyReport(r, x);
        boolean active = "true".equals(x.get("active"));
        boolean terminal = r.exec != null && r.exec.terminal();
        if (r.state.live() && !active && (terminal || "done".equals(x.get("phase")))) {
            r.state = LeaseRecord.State.RELEASED;
            persist();
            log.accept("swarm-lease: released lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot + " exec="
                    + (r.exec == null ? "none" : r.exec.label()) + " obs=" + r.obs.label());
        } else {
            persist();
            log.accept("swarm-lease: reported lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot + " exec="
                    + (r.exec == null ? "none" : r.exec.label()) + " obs=" + r.obs.label() + " active=" + active
                    + " state=" + r.state.name().toLowerCase(Locale.ROOT));
        }
    }

    private static void applyReport(LeaseRecord r, Map<String, String> x) {
        String exec = x.get("exec");
        if (exec != null && !"none".equals(exec)) {
            try {
                r.exec = ExecutionState.parse(exec);
            } catch (IllegalArgumentException ignored) {
                // Unknown label: keep what we had.
            }
        }
        String obs = x.get("obs");
        if (obs != null) {
            try {
                r.obs = ObservationState.parse(obs);
            } catch (IllegalArgumentException ignored) {
                // Unknown label: keep what we had.
            }
        }
        String reason = x.get("reason");
        if (reason != null && !"none".equals(reason) && r.endReason == null) {
            r.endReason = reason;
        }
    }

    static Map<String, String> parseExtra(String extra) {
        Map<String, String> out = new HashMap<>();
        if (extra == null) {
            return out;
        }
        for (String kv : extra.split(",")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                out.put(kv.substring(0, i), kv.substring(i + 1));
            }
        }
        return out;
    }

    // ---- reconciliation ---------------------------------------------------------------------------------

    private void onReconcileReport(String from, LeaseMessage m, List<Runnable> after) {
        String bot = from.toLowerCase(Locale.ROOT);
        if (m.leaseId == null) {
            if ("end".equals(m.extra) && reported.add(bot)) {
                log.accept("swarm-lease: reconcile-report bot=" + from + " claims=" + claimCount(from));
                renewHeld(from, after);
            }
            return;
        }
        Map<String, String> x = parseExtra(m.extra);
        LeaseRecord r = records.get(m.leaseId);
        if (r != null && r.bot.equalsIgnoreCase(from) && r.gen == m.gen) {
            applyReport(r, x);
        }
        String phase = x.get("phase");
        boolean active = "true".equals(x.get("active"));
        if (active || "pending".equals(phase) || "valid".equals(phase)) {
            claims.put(m.leaseId, new Claim(from, m, x));
        }
    }

    /**
     * Ostinato: a holder just finished its report. The newest held renew per lease of that bot gets an early renew;
     * one that is granted takes every held renew of its lease with it, the rest stay held.
     */
    private void renewHeld(String from, List<Runnable> after) {
        Map<UUID, LeaseMessage> newest = new LinkedHashMap<>();
        for (Object[] q : heldRequests) {
            LeaseMessage h = (LeaseMessage) q[1];
            if (h.op == LeaseMessage.Op.RENEW && h.leaseId != null && from.equalsIgnoreCase((String) q[0])) {
                LeaseMessage prev = newest.get(h.leaseId);
                if (prev == null || h.nonce >= prev.nonce) {
                    newest.put(h.leaseId, h);
                }
            }
        }
        for (LeaseMessage h : newest.values()) {
            if (tryEarlyRenew(from, h, after)) {
                heldRequests.removeIf(q -> from.equalsIgnoreCase((String) q[0])
                        && ((LeaseMessage) q[1]).op == LeaseMessage.Op.RENEW
                        && h.leaseId.equals(((LeaseMessage) q[1]).leaseId));
            }
        }
    }

    private int claimCount(String bot) {
        int n = 0;
        for (Claim c : claims.values()) {
            if (c.bot.equalsIgnoreCase(bot)) {
                n++;
            }
        }
        return n;
    }

    private boolean reconcileDone(long now) {
        long waited = now - reconcileStartMono;
        if (waited < 2 * marginMs) {
            return false;
        }
        long w = wall.getAsLong();
        for (Map.Entry<String, Long> e : holderNotBefore.entrySet()) {
            if (reported.contains(e.getKey())) {
                continue;
            }
            if (w < e.getValue()) {
                return false;
            }
            // Ostinato: a chat holder's report needs several seconds (two-line frames, rate limiter backlog), so
            // 2*M alone turned a live holder into reconcile-timeout. Wait one renew period of its lease for it.
            if (chatPeer != null && chatPeer.test(e.getKey()) && waited < LeaseClock.resendEvery(durationFor(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private void finishReconcile(long now, List<Runnable> after) {
        long w = wall.getAsLong();
        Set<String> poisoned = new LinkedHashSet<>();
        // A claim above the durable high-water mark means grants this store never saw: trust none of them.
        for (Claim c : claims.values()) {
            long hw = highWater.getOrDefault(c.m.resource, 0L);
            if (c.m.gen > hw) {
                highWater.put(c.m.resource, c.m.gen);
                poisoned.add(c.m.resource);
                log.accept("swarm-lease: high-water-raised resource=" + c.m.resource + " gen=" + c.m.gen + " bot="
                        + c.bot + " (claim above durable " + hw + "; revoking all claims)");
            }
        }
        Set<UUID> kept = new HashSet<>();
        for (Claim c : claims.values()) {
            LeaseRecord r = records.get(c.m.leaseId);
            boolean keep = r != null && !poisoned.contains(c.m.resource) && r.bot.equalsIgnoreCase(c.bot)
                    && r.gen == c.m.gen && r.state == LeaseRecord.State.GRANTED
                    && highWater.getOrDefault(r.resource, 0L) == r.gen;
            if (keep) {
                kept.add(r.leaseId);
                // Ostinato: live b7 - commandSent is not persisted, so the first renew after a restart re-submitted
                // the bound command; with caps not yet relearned that was refused and the kept lease got revoked.
                // A holder whose report shows the command already has it; nothing must be sent again.
                if (!"none".equals(c.extra.getOrDefault("exec", "none")) || "true".equals(c.extra.get("active"))) {
                    r.commandSent = true;
                }
                log.accept("swarm-lease: kept lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot);
                continue;
            }
            if (r == null) {
                // Unknown to the store: track it as revoking so the resource stays blocked until rack or expiry.
                r = new LeaseRecord(c.m.leaseId, c.m.resource, c.m.gen,
                        c.m.commandId == null ? new UUID(0, 0) : c.m.commandId, c.bot, "", LeaseRecord.State.REVOKING);
                r.notBeforeWall = LeaseClock.notBeforeWall(w, durationFor(c.bot), marginMs, skewMs);
                r.coordDeadlineMono = LeaseClock.coordDeadline(now, durationFor(c.bot), marginMs);
                records.put(r.leaseId, r);
            } else if (r.state.live()) {
                r.state = LeaseRecord.State.REVOKING;
            }
            if (r.endReason == null) {
                r.endReason = "reconcile-conflict";
            }
            sendRevoke(c.bot, r.leaseId, r.resource, r.gen, r.commandId);
            log.accept("swarm-lease: revoking lease=" + r.leaseId + " gen=" + r.gen + " bot=" + c.bot
                    + " (reconcile conflict)");
        }
        for (LeaseRecord r : records.values()) {
            if (!r.state.live() || kept.contains(r.leaseId) || claims.containsKey(r.leaseId)) {
                continue;
            }
            String bot = r.bot.toLowerCase(Locale.ROOT);
            if (r.state == LeaseRecord.State.OFFERED) {
                // No grant was ever persisted, so none was sent: nothing ran under it.
                r.state = LeaseRecord.State.EXPIRED;
                r.endReason = "offer-lost";
            } else if (!reported.contains(bot)) {
                r.state = LeaseRecord.State.EXPIRED;
                if (r.exec == null || !r.exec.terminal()) {
                    r.exec = ExecutionState.RUNNING;
                    r.obs = ObservationState.UNKNOWN;
                }
                r.endReason = "reconcile-timeout"; // Never retried automatically.
            } else {
                // The holder answered without claiming it: blocked until its notBefore, then free.
                r.state = LeaseRecord.State.EXPIRED;
                if (r.exec == null || !r.exec.terminal()) {
                    r.obs = ObservationState.UNKNOWN;
                }
                if (r.endReason == null) {
                    r.endReason = "reconcile-unclaimed";
                }
            }
            log.accept("swarm-lease: " + r.state.name().toLowerCase(Locale.ROOT) + " lease=" + r.leaseId + " gen="
                    + r.gen + " bot=" + r.bot + " reason=" + r.endReason);
        }
        if (!persist()) {
            refuse("store write failed after reconciliation");
            return;
        }
        mode = Mode.ACTIVE;
        log.accept("swarm-lease: active coordinator=" + self + " leases=" + liveCount() + " held-requests="
                + heldRequests.size());
        List<Object[]> queued = new ArrayList<>(heldRequests);
        heldRequests.clear();
        claims.clear();
        for (Object[] q : queued) {
            handleActive((String) q[0], (LeaseMessage) q[1], after, true);
        }
    }

    private void sendRreq(long now) {
        lastRreqMono = now;
        Set<String> to = new LinkedHashSet<>();
        for (LeaseRecord r : records.values()) {
            if (r.state.live() && !reported.contains(r.bot.toLowerCase(Locale.ROOT))) {
                to.add(r.bot);
            }
        }
        Collection<String> ps = peers.get();
        if (ps != null) {
            for (String p : ps) {
                if (p != null && !p.equalsIgnoreCase(self) && !reported.contains(p.toLowerCase(Locale.ROOT))) {
                    to.add(p);
                }
            }
        }
        for (String b : to) {
            send(b, new LeaseMessage(LeaseMessage.Op.RREQ, null, "-", 0, null, 0, 0, "-"), true);
        }
    }

    // ---- tick -------------------------------------------------------------------------------------------

    public void tick() {
        List<Runnable> after = new ArrayList<>();
        synchronized (this) {
            long now = mono.getAsLong();
            if (mode == Mode.RECONCILING) {
                if (reconcileDone(now)) {
                    finishReconcile(now, after);
                } else if (now - lastRreqMono >= LeaseClock.resendEvery(durationFor(null))) {
                    sendRreq(now);
                }
            } else if (mode == Mode.ACTIVE) {
                expire(now);
            }
        }
        for (Runnable r : after) {
            r.run();
        }
    }

    private void expire(long now) {
        long w = wall.getAsLong();
        boolean changed = false;
        for (LeaseRecord r : records.values()) {
            long d = durations.getOrDefault(r.leaseId, durationFor(r.bot));
            switch (r.state) {
                case OFFERED:
                    if (now - r.offeredAtMono >= d + marginMs) {
                        r.state = LeaseRecord.State.EXPIRED;
                        r.endReason = "offer-unanswered";
                        changed = true;
                        log.accept("swarm-lease: expired lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot
                                + " reason=offer-unanswered");
                    } else if (now - lastResend.getOrDefault(r.leaseId, r.offeredAtMono) >= LeaseClock.resendEvery(d)) {
                        lastResend.put(r.leaseId, now);
                        send(r.bot, new LeaseMessage(LeaseMessage.Op.OFFER, r.leaseId, r.resource, r.gen, r.commandId,
                                0, d, "-"));
                    }
                    break;
                case GRANTED:
                case REVOKING:
                    boolean monoPassed = r.coordDeadlineMono == Long.MIN_VALUE || now >= r.coordDeadlineMono;
                    if (monoPassed && w >= r.notBeforeWall) {
                        boolean revoking = r.state == LeaseRecord.State.REVOKING;
                        r.state = LeaseRecord.State.EXPIRED;
                        if (r.endReason == null || revoking) {
                            r.endReason = revoking ? "revoke-unacked" : "lease-expired";
                        }
                        if (r.exec == null || !r.exec.terminal()) {
                            r.obs = ObservationState.UNKNOWN;
                        }
                        changed = true;
                        log.accept("swarm-lease: expired lease=" + r.leaseId + " gen=" + r.gen + " bot=" + r.bot
                                + " reason=" + r.endReason);
                    } else if (r.state == LeaseRecord.State.REVOKING
                            && now - lastResend.getOrDefault(r.leaseId, Long.MIN_VALUE / 2) >= LeaseClock.resendEvery(d)) {
                        lastResend.put(r.leaseId, now);
                        sendRevoke(r.bot, r.leaseId, r.resource, r.gen, r.commandId);
                    }
                    break;
                default:
                    break;
            }
        }
        if (changed) {
            persist();
            prune();
        }
    }

    // ---- operator actions -------------------------------------------------------------------------------

    /** Lease {@code resource} to {@code bot} and, once granted, send it {@code commandLine}. Returns a status line. */
    public String lease(String bot, String resource, String commandLine) {
        // Ostinato: a live or still-fenced lease is the first answer the operator needs.
        String busy = LeaseMessage.validResource(resource) ? busyReason(resource) : null;
        if (busy != null) {
            return "swarm-lease: resource " + resource + " busy: " + busy;
        }
        // Ostinato: commands of leases whose fence passed no longer block new leased work.
        for (UUID id : fencedCommands()) {
            commands.fenced(id);
        }
        // Ostinato: ask outside the lock (the protocol has its own) whether the command could be sent at all.
        String refused = commandLine == null || bot == null ? null : commands.refusal(bot, commandLine.trim());
        if (refused != null) {
            log.accept("swarm-lease: not-offered resource=" + resource + " bot=" + bot + " reason=command-refused ("
                    + refused + ")");
            return "swarm-lease: not offering " + resource + ": " + refused;
        }
        synchronized (this) {
            if (mode != Mode.ACTIVE) {
                return "swarm-lease: coordinator is " + mode.name().toLowerCase(Locale.ROOT)
                        + (refusedReason != null ? " (" + refusedReason + ")" : "") + "; not granting";
            }
            if (bot == null || bot.isEmpty() || bot.equalsIgnoreCase(self)) {
                return "swarm-lease: lease needs another member as the bot";
            }
            if (!LeaseMessage.validResource(resource) || "-".equals(resource)) {
                return "swarm-lease: bad resource name";
            }
            if (commandLine == null || commandLine.trim().isEmpty()) {
                return "swarm-lease: lease needs a command";
            }
            busy = busyReason(resource);
            if (busy != null) {
                return "swarm-lease: resource " + resource + " busy: " + busy;
            }
            long now = mono.getAsLong();
            long hw = highWater.getOrDefault(resource, 0L);
            long gen = hw + 1;
            long d = durationFor(bot);
            LeaseRecord r = new LeaseRecord(UUID.randomUUID(), resource, gen, UUID.randomUUID(), bot,
                    commandLine.trim(), LeaseRecord.State.OFFERED);
            r.offeredAtMono = now;
            records.put(r.leaseId, r);
            highWater.put(resource, gen);
            if (!persist()) {
                records.remove(r.leaseId);
                if (hw == 0L) {
                    highWater.remove(resource);
                } else {
                    highWater.put(resource, hw);
                }
                return "swarm-lease: store write failed; not offering";
            }
            durations.put(r.leaseId, d);
            lastResend.put(r.leaseId, now);
            send(bot, new LeaseMessage(LeaseMessage.Op.OFFER, r.leaseId, resource, gen, r.commandId, 0, d, "-"));
            log.accept("swarm-lease: offered lease=" + r.leaseId + " gen=" + gen + " bot=" + bot + " resource="
                    + resource + " D=" + d + "ms");
            return "swarm-lease: offered " + resource + " gen " + gen + " to " + bot + " (lease " + r.leaseId + ")";
        }
    }

    /** Revoke the live lease on {@code resource} (or with that lease id). The resource frees on rack or expiry. */
    public synchronized String revoke(String resourceOrId) {
        if (mode != Mode.ACTIVE) {
            return "swarm-lease: coordinator is " + mode.name().toLowerCase(Locale.ROOT);
        }
        LeaseRecord target = null;
        for (LeaseRecord r : records.values()) {
            if (r.state.live() && (r.resource.equals(resourceOrId) || r.leaseId.toString().equals(resourceOrId))) {
                target = r;
            }
        }
        if (target == null) {
            return "swarm-lease: no live lease for " + resourceOrId;
        }
        if (target.state == LeaseRecord.State.OFFERED) {
            // Never granted (grants are persisted first), so nothing runs under it.
            target.state = LeaseRecord.State.RELEASED;
            target.endReason = "revoked-before-grant";
        } else {
            target.state = LeaseRecord.State.REVOKING;
            target.endReason = "lease-revoked";
        }
        persist();
        lastResend.put(target.leaseId, mono.getAsLong());
        sendRevoke(target.bot, target.leaseId, target.resource, target.gen, target.commandId);
        log.accept("swarm-lease: " + target.state.name().toLowerCase(Locale.ROOT) + " lease=" + target.leaseId
                + " gen=" + target.gen + " bot=" + target.bot + " reason=" + target.endReason);
        return "swarm-lease: revoking " + target.resource + " gen " + target.gen + " from " + target.bot;
    }

    // ---- queries ----------------------------------------------------------------------------------------

    /** Null when {@code resource} may be leased now, else why not. */
    /** Ostinato: command ids of ended leases whose deadline plus margin and notBefore have both passed. */
    synchronized List<UUID> fencedCommands() {
        long now = mono.getAsLong();
        long w = wall.getAsLong();
        List<UUID> out = new ArrayList<>(prunedFenced);
        prunedFenced.clear();
        for (LeaseRecord r : records.values()) {
            if (r.commandId != null && !r.state.live() && r.state != LeaseRecord.State.OFFERED && w >= r.notBeforeWall
                    && (r.coordDeadlineMono == Long.MIN_VALUE || now >= r.coordDeadlineMono)) {
                out.add(r.commandId);
            }
        }
        return out;
    }

    public synchronized String busyReason(String resource) {
        long w = wall.getAsLong();
        long now = mono.getAsLong();
        for (LeaseRecord r : records.values()) {
            if (!r.resource.equals(resource)) {
                continue;
            }
            if (r.state.live()) {
                return r.state.name().toLowerCase(Locale.ROOT) + " to " + r.bot + " (gen " + r.gen + ")";
            }
            if (r.state == LeaseRecord.State.EXPIRED && w < r.notBeforeWall) {
                return "expired lease gen " + r.gen + " blocks it for " + (r.notBeforeWall - w) + " ms";
            }
            if (r.state == LeaseRecord.State.EXPIRED && r.coordDeadlineMono != Long.MIN_VALUE
                    && now < r.coordDeadlineMono) {
                return "expired lease gen " + r.gen + " blocks it for " + (r.coordDeadlineMono - now) + " ms";
            }
        }
        return null;
    }

    public synchronized LeaseRecord record(UUID leaseId) {
        return records.get(leaseId);
    }

    /** The newest record for {@code resource}, or null. */
    public synchronized LeaseRecord latest(String resource) {
        LeaseRecord out = null;
        for (LeaseRecord r : records.values()) {
            if (r.resource.equals(resource) && (out == null || r.gen >= out.gen)) {
                out = r;
            }
        }
        return out;
    }

    public synchronized long highWater(String resource) {
        return highWater.getOrDefault(resource, 0L);
    }

    public synchronized long rivalOps() {
        return rivalOps;
    }

    public synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("coordinator mode=" + mode.name().toLowerCase(Locale.ROOT) + (refusedReason == null ? ""
                : " (" + refusedReason + ")") + " grants=" + grants + " renewals=" + renewals + " early=" + earlyRenewals + " rivalOps="
                + rivalOps + " " + store.path());
        long w = wall.getAsLong();
        for (LeaseRecord r : records.values()) {
            if (r.state.live() || (r.state == LeaseRecord.State.EXPIRED && w < r.notBeforeWall)) {
                out.add(r.toString() + (r.endReason == null ? "" : " reason=" + r.endReason));
            }
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private long durationFor(String bot) {
        boolean chat = bot != null && chatPeer != null && chatPeer.test(bot);
        return LeaseClock.effectiveDuration(configuredDurationMs.getAsLong(), chat);
    }

    private int liveCount() {
        int n = 0;
        for (LeaseRecord r : records.values()) {
            if (r.state.live()) {
                n++;
            }
        }
        return n;
    }

    private boolean persist() {
        try {
            store.save(highWater, new ArrayList<>(records.values()));
            return true;
        } catch (IOException e) {
            log.accept("swarm-lease: store-write-failed " + store.path() + ": " + e.getMessage());
            return false;
        }
    }

    private void prune() {
        int terminal = 0;
        for (LeaseRecord r : records.values()) {
            if (!r.state.live()) {
                terminal++;
            }
        }
        long w = wall.getAsLong();
        for (Iterator<LeaseRecord> it = records.values().iterator(); it.hasNext() && terminal > CoordinatorStore.KEEP_TERMINAL; ) {
            LeaseRecord r = it.next();
            if (!r.state.live() && w >= r.notBeforeWall) {
                if (r.commandId != null) {
                    prunedFenced.add(r.commandId); // Ostinato: still owed its fence after the record is gone.
                }
                it.remove();
                durations.remove(r.leaseId);
                lastResend.remove(r.leaseId);
                terminal--;
            }
        }
    }

    /** Ostinato: one denial per bot, lease and generation per {@link LeaseClock#repeatGap}; repeats crossed it in flight. */
    private boolean denyDue(String bot, UUID leaseId, long gen, long now) {
        String key = bot.toLowerCase(Locale.ROOT) + "|" + leaseId + "|" + gen;
        Long last = lastDenied.get(key);
        if (last != null && now - last < LeaseClock.repeatGap(durationFor(bot))) {
            repliesSuppressed++;
            return false;
        }
        lastDenied.put(key, now);
        return true;
    }

    /** Ostinato: replies not sent because an identical one went out less than {@link LeaseClock#repeatGap} ago. */
    public synchronized long repliesSuppressed() {
        return repliesSuppressed;
    }

    private void sendRevoke(String bot, UUID leaseId, String resource, long gen, UUID commandId) {
        send(bot, new LeaseMessage(LeaseMessage.Op.REVOKE, leaseId, resource, gen, commandId, 0, 0, "-"));
    }

    private void send(String to, LeaseMessage m) {
        send(to, m, false);
    }

    private void send(String to, LeaseMessage m, boolean urgent) {
        try {
            if (urgent) {
                sender.send(to, m.encode(), true);
            } else {
                sender.send(to, m.encode());
            }
        } catch (RuntimeException e) {
            log.accept("swarm-lease: send-failed op=" + m.op.wire() + " bot=" + to + ": " + e.getMessage());
        }
    }
}
