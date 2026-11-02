/*
 * Ostinato: new file (Grace Note). Not part of upstream Baritone.
 */
package baritone.gracenote;

import baritone.api.gracenote.GraceNoteOutcome;
import baritone.api.gracenote.GraceNotePolicy;
import baritone.api.gracenote.GraceNoteStatus;
import baritone.api.gracenote.IGraceNote.Emergency;
import baritone.api.gracenote.IGraceNote.Mode;
import baritone.api.gracenote.IGraceNote.Phase;
import baritone.api.gracenote.IGraceNote.Response;
import baritone.api.gracenote.IGraceNote.Result;

import java.util.ArrayList;
import java.util.List;

/**
 * Grace Note's decisions with no Minecraft in them: the state machine, the water clutch's tick window, the anti-thrash
 * guard and the outcome record. The adapter ({@link GraceNote}) reads the game into these inputs three times a tick:
 * {@link #early} before the path executor runs (detect, evaluate, ask for the hold, aim), {@link #late} after every
 * other listener (the one-shot click, gated on what the game shows right then), {@link #post} after the player has
 * moved (what happened).
 * <p>
 * Ticks are relative to the tick a forecast was taken on: tick 0 is now, before this tick's movement. The bucket
 * empties during this tick's key handling, before the player moves; a landing at forecast tick t happens during the
 * movement of tick t-1, so the water has to go in on tick t-1 at the latest. A look set on tick k is applied in tick
 * k's player update and is what the crosshair uses from tick k+1 (verified in 26.3's Minecraft.tick bytecode: pick,
 * then Baritone's tick, then keybinds, then entities).
 */
public final class GraceNoteCore {

    /** Ticks after landing (or dying) before the outcome is final: health arrives from the server a tick or more late. */
    static final int SETTLE_TICKS = 10;
    /** Ticks the path executor has to be seen held before the takeover counts as failed. */
    static final int ACQUIRE_TICKS = 2;
    /** Ticks to see the hold gone once no longer asked for. */
    static final int RELEASE_TICKS = 3;
    static final int RECOVER_TICKS = 20;
    static final int CLICK_ATTEMPTS = 3;
    /** Nothing lasts longer than this: every phase ends by then, through release. */
    static final int EPISODE_TICKS = 200;
    /** Ticks a forecast has to say "safe now" before an unexecuted maneuver stands down (one tick of noise is not enough). */
    static final int ABORT_TICKS = 2;

    /** The body at the start of a tick. {@code exempt}: why fall damage cannot happen (flying, gliding, ...), null if it can. */
    public record Body(boolean alive, boolean onGround, boolean inWater, String exempt, double vy, double fallDistance,
                       float health, boolean recentlyHurt) {}

    /**
     * A PPE fall forecast cut down to what is decided on. {@code landingTicks} -1: no landing inside the horizon.
     * {@code hazard}: what the landing block or cell does apart from the fall (LAVA, FIRE, ...), null for nothing.
     */
    public record Forecast(boolean trusted, String confidence, int landingTicks, int reachTick, float damage,
                           boolean lethal, boolean landsInFluid, String hazard) {
        public static Forecast untrusted(String why) {
            return new Forecast(false, why, -1, -1, 0, false, false, null);
        }
    }

    /**
     * What a response would work with on the same tick. {@code readyTicks}: the first tick the look can be on the target
     * (0 already there), plus one if a held use has to be let go first.
     */
    public record Means(int bucketSlot, int readyTicks, boolean screenOpen, boolean evaporates, boolean targetReplaceable,
                        boolean interactionPaused, boolean plannedFallOwns) {}

    public enum Verdict {
        FEASIBLE, UNTRUSTED, NO_LANDING, LANDS_IN_FLUID, NOT_DANGEROUS, HAZARD_UNSUPPORTED, POLICY_DENIES,
        INTERACTION_PAUSED, NO_BUCKET, EVAPORATES, TARGET_BLOCKED, SCREEN_OPEN, PLANNED_FALL_OWNS, NEVER_IN_REACH,
        TOO_LATE
    }

    /** {@code clickTick}: the first tick the bucket can empty with effect; {@code margin}: ticks to spare after it. */
    public record Plan(Verdict verdict, int clickTick, int margin) {
        public boolean feasible() {
            return verdict == Verdict.FEASIBLE;
        }
    }

    /** What the adapter does this tick: hold the path executor, where to look, which hotbar slot to hold (-1 leave). */
    public record Command(boolean hold, Aim aim, int selectSlot) {
        public static final Command NONE = new Command(false, Aim.NONE, -1);
    }

    public enum Aim { NONE, LANDING, WATER }

    public enum Hand { WATER_BUCKET, BUCKET, OTHER }

    /** After the player has moved this tick. {@code waterAtTarget}: a water source in the cell the bucket aimed at. */
    public record Post(boolean alive, boolean onGround, boolean inWater, double fallDistance, float health, Hand hand,
                       boolean waterAtTarget) {}

    /** Late in the tick, after every other listener: what the click would act on. */
    public record Late(boolean heldNow, boolean foreignKeys, boolean bucketInHand, boolean rayOnTarget,
                       boolean screenOpen, boolean usingItem) {}

    /** The verdict for emptying a water bucket under this forecast. Order matters: the first that applies is reported. */
    public static Plan waterClutch(Forecast f, Means m, GraceNotePolicy p) {
        if (f == null || !f.trusted()) return new Plan(Verdict.UNTRUSTED, -1, -1);
        if (f.landingTicks() < 0) return new Plan(Verdict.NO_LANDING, -1, -1);
        if ("LAVA".equals(f.hazard())) return new Plan(Verdict.HAZARD_UNSUPPORTED, -1, -1);
        if (f.landsInFluid()) return new Plan(Verdict.LANDS_IN_FLUID, -1, -1);
        if (!harmful(f, p)) return new Plan(f.hazard() != null ? Verdict.HAZARD_UNSUPPORTED : Verdict.NOT_DANGEROUS, -1, -1);
        if (!p.allowWaterBucket()) return new Plan(Verdict.POLICY_DENIES, -1, -1);
        if (m.interactionPaused() && p.respectInteractionPause() && !f.lethal()) return new Plan(Verdict.INTERACTION_PAUSED, -1, -1);
        if (m.bucketSlot() < 0) return new Plan(Verdict.NO_BUCKET, -1, -1);
        if (m.evaporates()) return new Plan(Verdict.EVAPORATES, -1, -1);
        if (!m.targetReplaceable()) return new Plan(Verdict.TARGET_BLOCKED, -1, -1);
        if (m.screenOpen()) return new Plan(Verdict.SCREEN_OPEN, -1, -1);
        if (m.plannedFallOwns()) return new Plan(Verdict.PLANNED_FALL_OWNS, -1, -1);
        if (f.reachTick() < 0) return new Plan(Verdict.NEVER_IN_REACH, -1, -1);
        int first = Math.max(f.reachTick(), m.readyTicks()), last = f.landingTicks() - 1;
        return new Plan(first > last ? Verdict.TOO_LATE : Verdict.FEASIBLE, first, last - first);
    }

    /** The fall itself costs enough to act on (the hazard alone never does: nothing answers it yet). */
    static boolean harmful(Forecast f, GraceNotePolicy p) {
        return f.lethal() || f.damage() > 0 && f.damage() >= p.minDamage();
    }

    /** An episode starts here: a trusted landing inside the horizon that hurts, or that lands on a hazard. */
    static boolean alarming(Forecast f, GraceNotePolicy p) {
        return f != null && f.trusted() && f.landingTicks() >= 0 && !f.landsInFluid() && (harmful(f, p) || f.hazard() != null);
    }

    /** Takeovers in a sliding window and the cooldown after one: control can never flap. */
    static final class Guard {
        private final long[] took = new long[64];
        private int count;
        private long lastEnd = Long.MIN_VALUE / 2;

        String check(long tick, boolean lethal, GraceNotePolicy p) {
            int recent = 0;
            for (int i = 0; i < count; i++) if (tick - took[i] < GraceNotePolicy.WINDOW_TICKS) recent++;
            if (recent >= p.maxActionsPerWindow()) return "THRASH_WINDOW";
            if (!lethal && tick - lastEnd < p.cooldownTicks()) return "COOLDOWN";
            return null;
        }

        void took(long tick) {
            if (count == took.length) {
                System.arraycopy(took, 1, took, 0, took.length - 1);
                count--;
            }
            took[count++] = tick;
        }

        void ended(long tick) {
            lastEnd = tick;
        }
    }

    private static final class Episode {
        long id, start;
        Emergency kind;
        Mode mode;
        GraceNotePolicy policy;
        Forecast forecast;
        Plan plan;
        boolean owned, aborted, interrupted, died, rejected, recovered, missed, controlLost;
        String reason;
        float healthStart, minHealth;
        long requested = -1, started = -1, executed = -1, acknowledged = -1, avoided = -1, landed = -1, released = -1;
        long clicked = -1, pickedUp = -1, releaseAsked = -1, recoverStart = -1;
        int clickAttempts, pickupAttempts, safeTicks, lostTicks;
        boolean waterSeen;
        final List<Phase> trail = new ArrayList<>();
    }

    private final Guard guard = new Guard();
    private GraceNotePolicy policy = GraceNotePolicy.DEFAULT;
    private Phase phase = Phase.MONITORING;
    private Episode ep;
    private boolean spanBusy;
    private long episodes, sequence, tick;
    private GraceNoteOutcome last;
    private GraceNoteStatus status = GraceNoteStatus.idle(0, Mode.OBSERVE);
    private Mode mode = Mode.OBSERVE;
    private int bucketSlot = -1, threatLevel;
    private String entityNote;
    private long clickAuthorizedTick = -1;
    final Counters counters = new Counters();

    static final class Counters {
        long emergencies, acquisitions, clicks, avoided, survived, died, failed, aborted, observed, deferred, thrashBlocked;
    }

    public void setPolicy(GraceNotePolicy p) {
        this.policy = java.util.Objects.requireNonNull(p);
    }

    public GraceNotePolicy policy() {
        return policy;
    }

    public Phase phase() {
        return phase;
    }

    public GraceNoteStatus status() {
        return status;
    }

    public GraceNoteOutcome lastOutcome() {
        return last;
    }

    /** The phases the current (or last) episode passed through, for logs and tests. */
    public List<Phase> trail() {
        return ep == null ? List.of() : List.copyOf(ep.trail);
    }

    /** True while the path executor is to be held: from asking for control until release is asked for. */
    public boolean wantsHold() {
        return ep != null && ep.owned && ep.releaseAsked < 0;
    }

    /** True from asking for control until release has been seen. */
    public boolean holdsControl() {
        return ep != null && ep.owned;
    }

    /**
     * Start of the tick. {@code inWorld} false on a tick without a player or world; {@code worldChanged} when the world
     * or dimension is not the one the episode began in. {@code heldLastTick}: the path executor was held last tick.
     */
    public Command early(long tick, Mode mode, boolean inWorld, boolean worldChanged, Body body, Forecast f, Means m,
                         boolean heldLastTick, int threatLevel, String entityNote) {
        this.tick = tick;
        clickAuthorizedTick = -1;
        this.mode = mode;
        this.threatLevel = threatLevel;
        this.entityNote = entityNote;
        if (phase == Phase.COMPLETED || phase == Phase.FAILED) to(Phase.MONITORING);
        if (ep != null && phase != Phase.MONITORING) {
            if (!inWorld || worldChanged) {
                ep.interrupted = true;
                note(!inWorld ? "NO_WORLD" : "WORLD_CHANGED");
            } else if (!body.alive()) {
                if (!ep.died) note("DIED");
                ep.died = true;
            }
        }
        if (phase == Phase.MONITORING) {
            Command c = monitor(tick, mode, inWorld, body, f, m);
            publish();
            return c;
        }
        Command c = advance(tick, mode, body, f, m, heldLastTick);
        publish();
        return c;
    }

    private Command monitor(long tick, Mode mode, boolean inWorld, Body body, Forecast f, Means m) {
        if (!inWorld || !body.alive() || body.onGround() || body.inWater() || body.exempt() != null) spanBusy = false;
        if (mode == Mode.OFF || !inWorld || !body.alive() || body.onGround() || body.inWater()
                || body.exempt() != null || spanBusy || !alarming(f, policy)) return Command.NONE;
        // one episode per airborne span: whatever it decided, the same fall is not taken up again
        spanBusy = true;
        ep = new Episode();
        ep.id = ++episodes;
        ep.start = tick;
        ep.mode = mode;
        ep.policy = policy;
        ep.forecast = f;
        ep.healthStart = ep.minHealth = body.health();
        ep.kind = m.plannedFallOwns() ? Emergency.PLANNED_FALL : !harmful(f, policy) ? Emergency.HAZARD_LANDING
                : body.recentlyHurt() ? Emergency.KNOCKBACK_FALL : Emergency.FALL;
        counters.emergencies++;
        to(Phase.THREAT_DETECTED);
        to(Phase.EVALUATING);
        ep.plan = waterClutch(f, m, policy);
        bucketSlot = m.bucketSlot();
        if (ep.plan.verdict() == Verdict.PLANNED_FALL_OWNS) counters.deferred++;
        if (mode == Mode.LIVE && ep.plan.feasible()) {
            String blocked = guard.check(tick, f.lethal(), policy);
            if (blocked == null) {
                ep.owned = true;
                ep.requested = tick;
                guard.took(tick);
                counters.acquisitions++;
                note("ACQUIRE");
                to(Phase.ACQUIRING_CONTROL);
                clickAuthorizedTick = tick;
                return new Command(true, Aim.LANDING, bucketSlot);
            }
            counters.thrashBlocked++;
            note(blocked);
        } else {
            note(mode == Mode.LIVE ? ep.plan.verdict().name() : "OBSERVE_ONLY_" + ep.plan.verdict());
        }
        to(Phase.VERIFYING);
        return Command.NONE;
    }

    private Command advance(long tick, Mode mode, Body body, Forecast f, Means m, boolean heldLastTick) {
        Episode e = ep;
        boolean over = e.interrupted || e.died || tick - e.start >= EPISODE_TICKS;
        if (tick - e.start >= EPISODE_TICKS && !e.interrupted && !e.died) note("EPISODE_TIMEOUT");
        if (e.owned && e.releaseAsked < 0 && (over || mode != Mode.LIVE)) {
            if (mode != Mode.LIVE && !over) {
                note("MODE_CHANGED");
                e.aborted = e.executed < 0;
            }
            askRelease(tick);
        }
        if (e.owned && e.releaseAsked < 0 && e.lostTicks >= 2) {
            e.controlLost = true;
            note("CONTROL_LOST");
            askRelease(tick);
        }
        switch (phase) {
            case ACQUIRING_CONTROL:
            case EXECUTING: {
                if (body.exempt() != null) {
                    e.aborted = e.executed < 0;
                    note("BODY_EXEMPT");
                    askRelease(tick);
                    break;
                }
                if (e.started < 0 && tick - e.requested >= ACQUIRE_TICKS) {
                    note("HOLD_NOT_OBSERVED");
                    askRelease(tick);
                    break;
                }
                if (body.onGround() || body.inWater()) {
                    // down before the bucket went: nothing left to do with control
                    if (e.executed < 0) {
                        e.missed = true;
                        note("LANDED_BEFORE_CLICK");
                    }
                    askRelease(tick);
                    break;
                }
                if (e.clicked < 0) {
                    boolean safe = f != null && f.trusted() && (f.landingTicks() < 0 || f.landsInFluid() || !harmful(f, e.policy));
                    e.safeTicks = safe ? e.safeTicks + 1 : 0;
                    if (e.safeTicks >= ABORT_TICKS) {
                        e.aborted = true;
                        note("NO_LONGER_DANGEROUS");
                        askRelease(tick);
                        break;
                    }
                    if (f != null && f.trusted() && !safe) {
                        Plan now = waterClutch(f, m, e.policy);
                        if (now.feasible()) clickAuthorizedTick = tick;
                        if (now.verdict() == Verdict.TOO_LATE || now.verdict() == Verdict.NEVER_IN_REACH) {
                            e.missed = true;
                            note("WINDOW_MISSED_" + now.verdict());
                            askRelease(tick);
                            break;
                        }
                    }
                }
                return new Command(true, Aim.LANDING, bucketSlot);
            }
            case VERIFYING: {
                if (!settled(tick)) return e.owned && e.releaseAsked < 0 ? new Command(true, Aim.NONE, -1) : Command.NONE;
                if (e.owned && e.releaseAsked < 0) {
                    if (body.onGround() && recoverable()) {
                        e.recoverStart = tick;
                        to(Phase.RECOVERING);
                        return new Command(true, Aim.WATER, -1);
                    }
                    askRelease(tick);
                    break;
                }
                if (!e.owned) finish(tick);
                break;
            }
            case RECOVERING: {
                if (e.recovered || !e.waterSeen || tick - e.recoverStart >= RECOVER_TICKS) {
                    if (!e.recovered) note(e.waterSeen ? "RECOVERY_TIMEOUT" : "WATER_GONE");
                    askRelease(tick);
                    break;
                }
                return new Command(true, Aim.WATER, -1);
            }
            case RELEASING_CONTROL:
                break;
            default:
                break;
        }
        if (phase == Phase.RELEASING_CONTROL) {
            if (!heldLastTick && tick > e.releaseAsked) {
                e.released = tick;
                e.owned = false;
                guard.ended(tick);
                if (settled(tick)) finish(tick);
                else to(Phase.VERIFYING);
            } else if (tick - e.releaseAsked >= RELEASE_TICKS) {
                note("RELEASE_NOT_OBSERVED");
                e.controlLost = true;
                e.owned = false;
                guard.ended(tick);
                finish(tick);
            }
        }
        return Command.NONE;
    }

    private void askRelease(long tick) {
        Episode e = ep;
        if (!e.owned) {
            to(Phase.VERIFYING);
            return;
        }
        if (e.releaseAsked < 0) e.releaseAsked = tick;
        to(Phase.RELEASING_CONTROL);
    }

    private boolean settled(long tick) {
        Episode e = ep;
        return e.interrupted || e.died || e.landed >= 0 && tick - e.landed >= SETTLE_TICKS || tick - e.start >= EPISODE_TICKS;
    }

    private boolean recoverable() {
        Episode e = ep;
        return e.policy.recoverWater() && e.executed >= 0 && e.acknowledged >= 0 && e.waterSeen && !e.died
                && !e.interrupted && !e.rejected && mode == Mode.LIVE;
    }

    /** Late in the tick. True: queue the one use press now. */
    public boolean late(long tick, Late in) {
        Episode e = ep;
        if (e == null || !e.owned || e.releaseAsked >= 0 || this.tick != tick) return false;
        if (!in.heldNow() || in.foreignKeys()) {
            if (e.started >= 0) e.lostTicks++;
            return false;
        }
        e.lostTicks = 0;
        if (phase == Phase.ACQUIRING_CONTROL) {
            e.started = tick;
            to(Phase.EXECUTING);
        }
        if (phase == Phase.EXECUTING) {
            // A raycast alone cannot renew a stale forecast or resource permission.
            if (clickAuthorizedTick != tick || this.tick != tick) return false;
            // one press per tick at most, and never a second once the bucket is seen empty: that press would take the water back
            if (e.executed >= 0 || e.clicked == tick || e.clickAttempts >= CLICK_ATTEMPTS) return false;
            if (!in.bucketInHand() || in.screenOpen() || in.usingItem() || !in.rayOnTarget()) return false;
            e.clicked = tick;
            e.clickAttempts++;
            counters.clicks++;
            publish();
            return true;
        }
        if (phase == Phase.RECOVERING) {
            // bucketInHand here is the empty bucket: the press fills it from the water it put down
            if (e.recovered || e.pickedUp == tick || e.pickupAttempts >= CLICK_ATTEMPTS || !in.bucketInHand() || in.screenOpen()
                    || in.usingItem() || !in.rayOnTarget()) {
                return false;
            }
            if (e.pickedUp >= 0 && tick - e.pickedUp < 2) return false;
            e.pickedUp = tick;
            e.pickupAttempts++;
            return true;
        }
        return false;
    }

    /** End of the tick, after the player moved. */
    public void post(long tick, Post p) {
        Episode e = ep;
        if (e == null || phase == Phase.COMPLETED || phase == Phase.FAILED) return;
        if (!p.alive()) e.died = true;
        e.minHealth = Math.min(e.minHealth, p.health());
        if (e.clicked >= 0 && e.executed < 0) {
            if (p.hand() == Hand.BUCKET) {
                e.executed = e.clicked;
                if (e.releaseAsked < 0) to(Phase.VERIFYING);
            } else {
                // the press did not take (dropped, or the hand changed): the bucket is still full, so another is safe
                e.clicked = -1;
                if (e.clickAttempts >= CLICK_ATTEMPTS) {
                    note("CLICK_NOT_EXECUTED");
                    askRelease(tick);
                }
            }
        }
        if (e.executed >= 0) {
            if (p.waterAtTarget()) {
                if (e.waterSeen && e.acknowledged < 0) e.acknowledged = tick;
                e.waterSeen = true;
            } else if (e.waterSeen && e.recovered) {
                e.waterSeen = false;
            } else if (e.waterSeen && p.hand() == Hand.WATER_BUCKET && e.pickedUp < 0) {
                // the water went and the full bucket came back without a pickup: the server undid the use
                e.rejected = true;
                e.waterSeen = false;
                note("SERVER_REJECTED");
            } else if (e.waterSeen && e.pickedUp < 0) {
                e.waterSeen = false;
            }
            if (p.inWater() && e.avoided < 0 && e.landed < 0 && p.fallDistance() == 0) e.avoided = tick;
        }
        if (e.pickedUp >= 0 && !e.recovered && p.hand() == Hand.WATER_BUCKET && !p.waterAtTarget()) {
            e.recovered = true;
            note("RECOVERED");
        }
        if (e.landed < 0 && (p.onGround() || p.inWater())) e.landed = tick;
        publish();
    }

    private void finish(long tick) {
        Episode e = ep;
        float measured = Math.max(0, e.healthStart - e.minHealth);
        Result r;
        if (e.interrupted) r = Result.INTERRUPTED;
        else if (e.died) r = Result.DIED;
        else if (e.requested < 0) r = Result.OBSERVED;
        else if (e.started < 0) r = Result.FAILED_CONTROL;
        else if (e.controlLost || e.released < 0) r = Result.FAILED_CONTROL;
        else if (e.aborted && e.executed < 0) r = Result.ABORTED;
        else if (e.rejected) r = Result.REJECTED;
        // held, never emptied: control was lost, the press never took, or no tick had the bucket, the look and the landing in line
        else if (e.executed < 0) r = e.controlLost ? Result.FAILED_CONTROL : e.clickAttempts > 0 ? Result.FAILED_EXECUTION : Result.FAILED_WINDOW;
        else if (e.landed < 0 || e.acknowledged < 0) r = Result.FAILED_EXECUTION;
        else r = measured <= 0.01f && e.avoided >= 0 ? Result.AVOIDED : Result.SURVIVED;
        switch (r) {
            case AVOIDED -> counters.avoided++;
            case SURVIVED -> counters.survived++;
            case DIED -> counters.died++;
            case ABORTED -> counters.aborted++;
            case OBSERVED -> counters.observed++;
            default -> counters.failed++;
        }
        boolean good = r == Result.AVOIDED || r == Result.SURVIVED || r == Result.OBSERVED || r == Result.ABORTED;
        last = new GraceNoteOutcome(++sequence, e.id, e.mode, e.kind, e.requested >= 0 ? Response.WATER_BUCKET : Response.NONE,
                r, e.reason, e.forecast.damage(), e.forecast.lethal(), measured, !e.died, e.start, e.requested, e.started,
                e.executed, e.acknowledged, e.avoided, e.landed, e.released, tick, e.recovered);
        to(good ? Phase.COMPLETED : Phase.FAILED);
    }

    private void note(String reason) {
        if (ep != null) ep.reason = ep.reason == null ? reason : ep.reason.endsWith(reason) ? ep.reason : ep.reason + ">" + reason;
    }

    private void to(Phase next) {
        phase = next;
        if (ep != null && ep.trail.size() < 24 && (ep.trail.isEmpty() || ep.trail.getLast() != next)) ep.trail.add(next);
    }

    private void publish() {
        Episode e = ep;
        if (e == null || phase == Phase.MONITORING) {
            boolean entity = entityNote != null;
            status = new GraceNoteStatus(tick, mode, Phase.MONITORING, episodes, entity ? Emergency.ENTITY : Emergency.NONE,
                    Response.NONE, false, entity ? entityNote : "IDLE", 0, false, -1, -1, -1, "NONE", threatLevel);
            return;
        }
        Forecast f = e.forecast;
        status = new GraceNoteStatus(tick, mode, phase, e.id, e.kind, e.requested >= 0 ? Response.WATER_BUCKET : Response.NONE,
                e.owned, e.reason == null ? e.plan.verdict().name() : e.reason, f.damage(), f.lethal(), f.landingTicks(),
                f.reachTick(), e.plan.clickTick(), f.confidence(), threatLevel);
    }
}
