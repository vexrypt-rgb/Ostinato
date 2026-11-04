package baritone.pathing.kinematic;

import baritone.api.physics.PredictionResult;
import java.util.Locale;

/**
 * Ostinato: Presto, the predictive layer of kinematic travel ({@code presto} setting, off by default). It owns no
 * keys and no plan: {@link KinematicController} still picks and presses everything. Presto only takes options away
 * from it when the prediction behind them can't be trusted, and keeps the evidence.
 * <ul>
 * <li><b>Reliability</b>: each driven tick the controller states what {@link PlayerSim} expects one tick on; the next
 * tick it reports what happened. Yaw is left out (the camera lags the command): the vertical position, ground contact
 * and horizontal speed are compared. Too many misses in the last {@link #WINDOW} observations, or one jump in position
 * no tick explains (a server correction), and the model is distrusted until {@link #RETRUST} clean ticks in a row,
 * twice as many each time it is lost again soon after (no flapping between the two).</li>
 * <li><b>Takeoff check</b>: before a gap jump is pressed, the same takeoff is flown in the predictive physics engine
 * (vanilla 26.3 rules, where PlayerSim is the older formulas). {@link #judge} says whether the two agree.</li>
 * <li><b>Landing audit</b>: the landing both predicted at the takeoff is compared with the one that happened.</li>
 * </ul>
 * Pure Java, so it is tested without a game. The controller supplies the Minecraft side.
 */
public final class Presto {

    /** Vertical miss, blocks, over one tick. PlayerSim's gravity and jump are exact on plain blocks. */
    static final double VERTICAL_TOL = 0.05;
    /** Horizontal speed miss, blocks/tick. A 20 degree camera lag changes it by under 0.01. */
    static final double SPEED_TOL = 0.08;
    /** Further than this from where one tick could have taken the player: the server moved it. */
    static final double CORRECTION = 1.0;
    /** A landing this far from the predicted height is a different landing. */
    static final double LANDING_TOL = 0.5;
    /**
     * The engine's landing this far to the side of the takeoff-to-landing line: it flew one held yaw where the controller
     * steers, so it answered another jump than the planned one (no opinion). A one-wide walkway is lost at about 0.8.
     */
    static final double OFF_LINE = 0.75;
    static final int WINDOW = 20, DISTRUST_AT = 3, RETRUST = 40, MAX_RETRUST = 320;
    /** Ticks a vetoed takeoff may be put off by running on, before the veto gives way (see KinematicController). */
    static final int MAX_DEFER = 6;

    public enum Reason { NONE, VERTICAL, GROUND, SPEED, CORRECTION, LANDING }

    public enum Verdict {
        /** Both models land the jump at the same level, without damage. */
        AGREE,
        /** No usable engine answer (unsupported, partial, outcome-changing assumption): Presto has no opinion. */
        UNAVAILABLE,
        /** The engine has it still in the air at the end of the horizon: into the gap. */
        NO_LANDING,
        /** The engine's landing hurts. */
        DAMAGE,
        /** The engine lands it on another level than PlayerSim (short into the gap, or onto something in the way). */
        LEVEL;

        public boolean veto() {
            return this == NO_LANDING || this == DAMAGE || this == LEVEL;
        }
    }

    // ---- reliability
    private boolean trusted = true;
    private long bits; // the last WINDOW observations, 1 = diverged, newest in bit 0
    private int clean, retrust = RETRUST, sinceTrusted = Integer.MAX_VALUE;
    private Reason why = Reason.NONE;

    // ---- the one-step expectation
    private boolean expecting;
    private int expectTick;
    private double ex, ey, ez, evh;
    private boolean eGround;

    // ---- the landing audit
    private boolean armed, wasAirborne, withPpe;
    private int armTick, ppeTicks, simTicks;
    private double ppeY, simY, auditY;

    // ---- context: a prediction belongs to one world, one player body and one path, on consecutive driven ticks
    private Object world, player, path;
    private int lastTick = Integer.MIN_VALUE;

    // ---- telemetry
    public long observations, distrusts, retrusts, contextResets, corrections, checks, deferred, fallbacks, overridden,
            declined, hopsHeld, landings, landingMisses, ppeNanos, ppeMaxNanos;
    /** Time in Presto's per-tick bookkeeping (observe, expect), outside the engine checks; and the ticks it ran on. */
    public long hookNanos, hookTicks;
    /** Landing misses that were still falling when overdue. */
    public long overdue, ppeLandings;
    /** Long-jump-stretch takeoffs not checked (their line has floor under it all the way); checks ruled off the line. */
    public long noGap, offLine;
    /** One-tick horizontal position error (not gated on: the camera's lag makes it noisy): worst, sum, and over 0.05 / 0.15. */
    public double maxHorizontal, sumHorizontal;
    public long horizontalOver5, horizontalOver15;
    public final long[] divergences = new long[Reason.values().length];
    public final long[] verdicts = new long[Verdict.values().length];
    public double maxVertical, maxSpeed, sumPpeTickErr, sumSimTickErr, maxPpeYErr;

    /**
     * Start of a driven tick. A change of world or player body starts afresh (trust included); a new path or a tick
     * that was not driven only drops what was expected, since nothing compares with it any more.
     */
    public void begin(Object world, Object player, Object path, int tick) {
        if (world != this.world || player != this.player) {
            if (this.world != null) contextResets++;
            this.world = world;
            this.player = player;
            resetTrust();
            drop();
            disarm();
        } else if (path != this.path || tick != lastTick + 1) {
            drop(); // the audit stays armed: the flight it measures doesn't care which path or driver comes next
        }
        this.path = path;
        lastTick = tick;
    }

    /** Guard the audit even on ticks when the movement executor never calls drive. */
    public boolean auditContext(Object world, Object player, boolean eligible) {
        if (!eligible || world == null || player == null || world != this.world || player != this.player) {
            invalidate();
            return false;
        }
        return true;
    }

    /** Cancellation, disabled mode or another owner: no prediction may survive to a later action. */
    public void invalidate() {
        drop();
        disarm();
        clean = 0;
    }

    /** Explicit server correction, including small corrections and corrections while another driver runs. */
    public void corrected() {
        invalidate();
        record(Reason.CORRECTION);
    }

    /** This implementation and its oracles target exactly this release; ports require another physics audit. */
    public static boolean supportsVersion(String version) {
        return "26.3".equals(version);
    }

    /** PlayerSim does not read these modern attributes or changing poses; do not optimize from that model then. */
    public static boolean supportsBody(double width, double height, double step, double drag, double friction, double bounce) {
        return Math.abs(width - PlayerSim.HALF_WIDTH * 2) < 1e-5 && Math.abs(height - PlayerSim.HEIGHT) < 1e-5
                && Math.abs(step - PlayerSim.STEP) < 1e-5 && Math.abs(drag - 1) < 1e-6
                && Math.abs(friction - 1) < 1e-6 && bounce == 0;
    }

    /** A short/old/approximate answer must not turn a later simulated landing into a false veto. */
    public static boolean usable(PredictionResult r, int landingTick) {
        return r != null && r.status == PredictionResult.Status.OK && r.failure == PredictionResult.Failure.NONE
                && (r.confidence == PredictionResult.Confidence.EXACT || r.confidence == PredictionResult.Confidence.BOUNDED)
                && r.snapshotAgeTicks >= 0 && r.snapshotAgeTicks <= 20 && landingTick > 0 && r.horizonTicks >= landingTick;
    }

    /** Forget the one-tick expectation (the controller handed back, the stretch changed). */
    public void drop() {
        expecting = false;
    }

    /** Stop the landing audit (someone else took the player over, or it is another body now). */
    public void disarm() {
        armed = false;
    }

    private void resetTrust() {
        trusted = true;
        bits = 0;
        clean = 0;
        retrust = RETRUST;
        sinceTrusted = Integer.MAX_VALUE;
        why = Reason.NONE;
    }

    public boolean trusted() {
        return trusted;
    }

    /** Why it was last distrusted, NONE if it never was. */
    public Reason reason() {
        return why;
    }

    /** What PlayerSim expects next tick from the keys just pressed. */
    public void expect(int tick, double x, double y, double z, double vx, double vz, boolean onGround) {
        expecting = true;
        expectTick = tick;
        ex = x;
        ey = y;
        ez = z;
        evh = Math.hypot(vx, vz);
        eGround = onGround;
    }

    /** What happened. Returns the miss it counted, NONE when it held or there was nothing to compare. */
    public Reason observe(int tick, double x, double y, double z, double vx, double vz, boolean onGround) {
        if (!expecting || tick != expectTick + 1) {
            expecting = false;
            return Reason.NONE;
        }
        expecting = false;
        observations++;
        double dx = x - ex, dy = y - ey, dz = z - ez;
        double vertical = Math.abs(dy), speed = Math.abs(Math.hypot(vx, vz) - evh);
        Reason r;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > CORRECTION) {
            r = Reason.CORRECTION;
        } else {
            maxVertical = Math.max(maxVertical, vertical);
            double h = Math.hypot(dx, dz);
            maxHorizontal = Math.max(maxHorizontal, h);
            sumHorizontal += h;
            if (h > 0.05) horizontalOver5++;
            if (h > 0.15) horizontalOver15++;
            maxSpeed = Math.max(maxSpeed, speed);
            r = vertical > VERTICAL_TOL ? Reason.VERTICAL : onGround != eGround ? Reason.GROUND : speed > SPEED_TOL ? Reason.SPEED : Reason.NONE;
        }
        record(r);
        return r;
    }

    private void record(Reason r) {
        bits = (bits << 1 | (r == Reason.NONE ? 0 : 1)) & ((1L << WINDOW) - 1);
        if (sinceTrusted != Integer.MAX_VALUE) sinceTrusted++;
        if (r == Reason.NONE) {
            if (!trusted && ++clean >= retrust) {
                trusted = true;
                retrusts++;
                sinceTrusted = 0;
                bits = 0;
            }
            return;
        }
        divergences[r.ordinal()]++;
        clean = 0;
        if (r == Reason.CORRECTION) {
            corrections++;
            armed = false; // the flight it was measuring is not the one that happened
        }
        // a correction, or a jump both models landed that really didn't, is enough on its own
        if (trusted && (r == Reason.CORRECTION || r == Reason.LANDING || Long.bitCount(bits) >= DISTRUST_AT)) {
            trusted = false;
            distrusts++;
            why = r;
            // lost again soon after it came back: the trouble is lasting, wait longer before the next try
            retrust = sinceTrusted < 2 * retrust ? Math.min(MAX_RETRUST, retrust * 2) : RETRUST;
        }
    }

    /** Ticks of clean observation still wanted before trust returns (0 while trusted). */
    public int retrustIn() {
        return trusted ? 0 : retrust - clean;
    }

    /**
     * The takeoff check's ruling. {@code usable}: the engine ran the whole horizon with no outcome-changing
     * assumption. {@code landed}: it reported a landing. Heights are feet heights at touchdown.
     */
    public static Verdict judge(boolean usable, boolean landed, float damage, boolean lethal, double ppeLandY, double simLandY) {
        return judge(usable, landed, damage, lethal, ppeLandY, simLandY, 0);
    }

    /** As above, with the engine landing's distance to the side of the planned line ({@link #offLine}). */
    public static Verdict judge(boolean usable, boolean landed, float damage, boolean lethal, double ppeLandY, double simLandY, double side) {
        if (!Double.isFinite(simLandY) || landed && (!Float.isFinite(damage) || damage < 0
                || !Double.isFinite(ppeLandY) || !Double.isFinite(side) || side < 0)) return Verdict.UNAVAILABLE;
        if (!usable || landed && side > OFF_LINE) return Verdict.UNAVAILABLE;
        if (!landed) return Verdict.NO_LANDING;
        if (lethal || damage > 0) return Verdict.DAMAGE;
        if (Math.abs(ppeLandY - simLandY) > LANDING_TOL) return Verdict.LEVEL;
        return Verdict.AGREE;
    }

    /**
     * Distance, horizontal, of point p to the side of the line from (x0, z0) toward (x1, z1); its distance to (x0, z0)
     * when the line has no length.
     */
    public static double offLine(double x0, double z0, double x1, double z1, double px, double pz) {
        double dx = x1 - x0, dz = z1 - z0, len = Math.sqrt(dx * dx + dz * dz);
        double qx = px - x0, qz = pz - z0;
        return len < 1e-6 ? Math.sqrt(qx * qx + qz * qz) : Math.abs(dx * qz - dz * qx) / len;
    }

    /** Count a check and how long the engine took over it. */
    public void checked(Verdict v, long nanos) {
        checks++;
        verdicts[v.ordinal()]++;
        ppeNanos += nanos;
        ppeMaxNanos = Math.max(ppeMaxNanos, nanos);
    }

    /** A jump both models agree on was pressed: hold their landings to compare with the real one. */
    public void arm(int tick, int ppeLandTicks, double ppeLandY, int simLandTicks, double simLandY) {
        armed = true;
        withPpe = true;
        wasAirborne = false;
        armTick = tick;
        ppeTicks = ppeLandTicks;
        ppeY = ppeLandY;
        simTicks = simLandTicks;
        simY = simLandY;
        auditY = Double.NaN;
    }

    /** A jump only PlayerSim predicted (no engine check: a hop, a short gap): its landing is audited against that. */
    public void armSim(int tick, int simLandTicks, double simLandY) {
        arm(tick, simLandTicks, simLandY, simLandTicks, simLandY);
        withPpe = false;
    }

    public boolean armed() {
        return armed;
    }

    /** Ticks past the later predicted landing that a jump may still be in the air before it counts as missed. */
    static final int OVERDUE = 20;

    /**
     * Each tick while armed: on the first touchdown after leaving the ground, compare and disarm. Still in the air well
     * after both landings were due (into a deep gap): a miss.
     */
    public void landing(int tick, double y, boolean onGround) {
        if (!armed) return;
        double previousY = auditY;
        auditY = y;
        if (!onGround) {
            wasAirborne = true;
            int due = Math.max(ppeTicks, simTicks);
            boolean tooLate = tick - armTick > due + OVERDUE;
            // Past both touchdown times and descending below both supports: the landing is already missed.
            // Detect this before a failed-path cancellation invalidates the flight; never carry it past cancellation.
            boolean belowLanding = tick - armTick >= due && y < previousY && y < Math.min(ppeY, simY) - LANDING_TOL;
            if (tooLate || belowLanding) {
                armed = false;
                landings++;
                landingMisses++;
                if (tooLate) overdue++;
                record(Reason.LANDING);
            }
            return;
        }
        if (!wasAirborne) {
            if (tick - armTick > 2) armed = false; // the jump never left the ground
            return;
        }
        armed = false;
        landings++;
        int took = tick - armTick;
        sumSimTickErr += Math.abs(took - simTicks);
        double err = Math.abs(y - ppeY);
        if (withPpe) {
            ppeLandings++;
            sumPpeTickErr += Math.abs(took - ppeTicks);
            maxPpeYErr = Math.max(maxPpeYErr, err);
        }
        if (err > LANDING_TOL && Math.abs(y - simY) > LANDING_TOL) {
            landingMisses++;
            record(Reason.LANDING);
        }
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "PRESTO trusted=%b why=%s obs=%d distrusts=%d retrusts=%d corrections=%d resets=%d",
                trusted, why, observations, distrusts, retrusts, corrections, contextResets));
        sb.append(" miss[");
        for (Reason r : Reason.values()) if (r != Reason.NONE) sb.append(r).append('=').append(divergences[r.ordinal()]).append(' ');
        sb.setLength(sb.length() - 1);
        sb.append(String.format(Locale.ROOT, "] maxVert=%.4f maxSpeed=%.4f horiz avg=%.4f max=%.4f >0.05=%d >0.15=%d checks=%d", maxVertical, maxSpeed,
                observations == 0 ? 0 : sumHorizontal / observations, maxHorizontal, horizontalOver5, horizontalOver15, checks));
        sb.append(" verdicts[");
        for (Verdict v : Verdict.values()) sb.append(v).append('=').append(verdicts[v.ordinal()]).append(' ');
        sb.setLength(sb.length() - 1);
        sb.append(String.format(Locale.ROOT, "] deferred=%d fallbacks=%d overridden=%d declined=%d hopsHeld=%d landings=%d landingMisses=%d overdue=%d noGap=%d offLine=%d"
                        + " ppeTickErr=%.2f simTickErr=%.2f maxPpeYErr=%.3f ppeUs avg=%.1f max=%.1f hookUs/tick=%.2f",
                deferred, fallbacks, overridden, declined, hopsHeld, landings, landingMisses, overdue, noGap, offLine,
                ppeLandings == 0 ? 0 : sumPpeTickErr / ppeLandings, landings == 0 ? 0 : sumSimTickErr / landings, maxPpeYErr,
                checks == 0 ? 0 : ppeNanos / 1e3 / checks, ppeMaxNanos / 1e3, hookTicks == 0 ? 0 : hookNanos / 1e3 / hookTicks));
        return sb.toString();
    }
}
