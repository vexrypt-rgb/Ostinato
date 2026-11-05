/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

/**
 * Where one opponent will be over the next few ticks, and how sure that is. Game-free: the adapter feeds it the
 * position the client shows for the opponent, once per tick, and asks it about some ticks ahead.
 * <p>
 * Horizontal motion is a least-squares line through the recent positions, with a bounded lead of the measured
 * acceleration; after a turn only the newest samples are fitted, so a strafe reversal is followed in one tick rather
 * than averaged away. Vertical motion is vanilla's: a body in the air falls as {@code vy = (vy - 0.08) * 0.98} after
 * each move (26.3 {@code LivingEntity.travelInAir}, verified), and lands on the floor the adapter reports.
 * <p>
 * Every prediction carries two radii. {@link Prediction#spread} is the error to expect, built from how much this
 * opponent's path has wandered from the fit and how often it turns. {@link Prediction#bound} is the reach of any
 * player whose velocity changes by at most {@link Rules#ACCEL} a tick from the last step it was seen to make: a
 * position outside it means that rule is wrong, or the opponent was knocked.
 * <p>
 * Bounded: {@link Rules#KEEP} samples, no allocation per observation.
 */
public final class OpponentModel {

    /**
     * PROVISIONAL — PENDING IN-GAME CALIBRATION, except where a line says verified. Every number of the model, in one
     * place. {@link PredictionAudit} measures each prediction against the position that followed, by horizon: those
     * figures are what these get tuned from.
     */
    public static final class Rules {
        /** Samples held. Enough for the longest fit and the acceleration's two halves. */
        public static final int KEEP = 12;
        /** Samples the velocity is fitted over while the path is steady. More smooths a strafe away, fewer is noise. */
        public static final int FIT = 5;
        /** Samples fitted after a turn: the newest step and the one before it. */
        public static final int TURN_FIT = 2;
        /** A step longer than this many blocks a tick is a teleport, a respawn or a pearl: the history restarts. */
        public static final double TELEPORT = 4.0;
        /** A gap without samples longer than this many ticks restarts the history too: the old path says nothing. */
        public static final int GAP = 20;
        /** Vanilla 26.3 {@code LivingEntity.DEFAULT_BASE_GRAVITY}, verified. */
        public static final double GRAVITY = 0.08;
        /** Vanilla 26.3 {@code BASE_VERTICAL_AIR_DRAG}, applied after the gravity, verified. */
        public static final double DRAG = 0.98f;
        /** Vanilla 26.3 {@code BASE_JUMP_POWER}, verified. Jump Boost adds to it; that is not modelled. */
        public static final double JUMP = 0.42;
        /**
         * A body flagged on the ground but shown falling faster than this, blocks a tick, is still airborne: the
         * flag arrives with the packet, the position is played back over the next ticks (26.3
         * {@code SteppedInterpolationHandler}, verified), so the flag runs ahead of the shown position.
         */
        public static final double SETTLE = 0.05;
        /** A first rise off the ground of at least this much in one tick is read as a jump. */
        public static final double JUMP_MIN = 0.3;
        /**
         * The newest step and the fitted heading this far apart (cosine under it, 60 degrees) is a turn. Only
         * judged when both move faster than {@link #MOVING}.
         */
        public static final double TURN_COS = 0.5;
        /** Blocks a tick below which a body is standing for heading purposes. */
        public static final double MOVING = 0.03;
        /**
         * Blocks a tick a player can change its horizontal velocity by in one tick. A sprinting player on stone
         * adds 0.13 * 1.3 of input a tick and keeps 0.546 of what it had (verified: 0.6 friction * 0.91); a full
         * reversal is about 0.2 a tick for a few ticks. A knockback is far more and is not covered.
         */
        public static final double ACCEL = 0.2;
        /** Horizontal speed no player on foot passes in blocks a tick (a sprint-jump peaks near 0.5). */
        public static final double VMAX = 0.7;
        /** Ticks of measured acceleration carried into a prediction before the velocity is held. */
        public static final int ACCEL_TICKS = 2;
        /** The acceleration is believed only over this many blocks a tick a tick: below it is position noise. */
        public static final double ACCEL_MIN = 0.02;
        /** Ticks without a fresh sample before the prediction is called stale. */
        public static final int STALE = 3;
        /** Ticks without a sample past which nothing is predicted. */
        public static final int LOST = 20;
        /** Radius floor in blocks: what interpolation smears even a standing body by. */
        public static final double BASE = 0.05;
        /** Weight of the newest tick in the behaviour averages: about a 2.5 second memory. */
        public static final double LEARN = 0.02;

        private Rules() {}
    }

    /** What the adapter knows of the ground. */
    public interface Ground {
        /** Top of the highest collision at or below {@code y} in the column at x, z within a few blocks; NaN when none is known. */
        double floor(double x, double y, double z);
    }

    // ring buffer of samples
    private final long[] tick = new long[Rules.KEEP];
    private final double[] xs = new double[Rules.KEEP], ys = new double[Rules.KEEP], zs = new double[Rules.KEEP];
    private final boolean[] ground = new boolean[Rules.KEEP];
    private int head = -1, count;

    // what the newest sample read as
    private double vx, vz, ax, az, resid;
    private double sx, sz; // the newest step, blocks a tick: the velocity the opponent last actually had
    private boolean turned, jumped, airborne;
    private double vy; // per-tick vertical step the next move will make
    private double lastGroundY = Double.NaN;
    private long lastJumpTick = Long.MIN_VALUE;

    // behaviour, bounded averages over the fight
    private double turnRate, jumpRate, speed;
    private long samples, turns, jumps, resets;

    /** Another world, another opponent, or a respawn: nothing seen before says anything now. */
    public void reset() {
        head = -1;
        count = 0;
        vx = vz = ax = az = resid = vy = sx = sz = 0;
        turned = jumped = airborne = false;
        lastGroundY = Double.NaN;
        lastJumpTick = Long.MIN_VALUE;
    }

    /** {@link #reset} plus the behaviour averages, for a new opponent. */
    public void forget() {
        reset();
        turnRate = jumpRate = speed = 0;
        samples = turns = jumps = resets = 0;
    }

    /**
     * One position as the client shows it on tick {@code t}. A repeated or older tick is ignored. The ground flag is
     * the opponent's own, as synced; a body that has not moved vertically for two ticks counts as grounded too, and
     * one flagged grounded but shown falling does not (see {@link Rules#SETTLE}).
     */
    public void observe(long t, double x, double y, double z, boolean onGround) {
        if (count > 0) {
            long last = tick[head];
            if (t <= last) return;
            long dt = t - last;
            double dx = x - xs[head], dy = y - ys[head], dz = z - zs[head];
            if (dt > Rules.GAP || Math.sqrt(dx * dx + dy * dy + dz * dz) > Rules.TELEPORT * dt) {
                reset();
                resets++;
            }
        }
        double prevDy = count > 1 ? (ys[head] - ys[prev(head)]) / Math.max(1, tick[head] - tick[prev(head)]) : 0;
        boolean wasStanding = count > 0 && (ground[head] || count > 1 && Math.abs(prevDy) < 1e-3);
        head = (head + 1) % Rules.KEEP;
        tick[head] = t;
        xs[head] = x;
        ys[head] = y;
        zs[head] = z;
        count = Math.min(count + 1, Rules.KEEP);
        double dy = count > 1 ? (y - ys[prev(head)]) / Math.max(1, t - tick[prev(head)]) : 0;
        boolean still = count > 2 && Math.abs(dy) < 1e-3 && Math.abs(prevDy) < 1e-3;
        // the synced flag comes with the packet and the position is played back after it, so the flag runs ahead of
        // what is shown: flagged down while still visibly falling is a landing that has not been shown yet
        ground[head] = still || onGround && dy > -Rules.SETTLE;
        if (ground[head]) lastGroundY = y;
        jumped = count > 1 && wasStanding && !ground[head] && dy >= Rules.JUMP_MIN && dy <= Rules.JUMP + 0.2;
        if (jumped) lastJumpTick = t;
        airborne = !ground[head];
        // the next move's vertical step: what this one was, less a tick of gravity, dragged
        vy = airborne && count > 1 ? (dy - Rules.GRAVITY) * Rules.DRAG : 0;
        fit();
        samples++;
        if (turned) turns++;
        if (jumped) jumps++;
        turnRate += ((turned ? 1 : 0) - turnRate) * Rules.LEARN;
        jumpRate += ((jumped ? 1 : 0) - jumpRate) * Rules.LEARN;
        speed += (Math.hypot(vx, vz) - speed) * Rules.LEARN;
    }

    private static int prev(int i) {
        return (i - 1 + Rules.KEEP) % Rules.KEEP;
    }

    /** The i-th newest sample's ring index, 0 the newest. */
    private int at(int i) {
        return (head - i + Rules.KEEP * 2) % Rules.KEEP;
    }

    private void fit() {
        if (count < 2) {
            vx = vz = ax = az = resid = sx = sz = 0;
            turned = false;
            return;
        }
        int n = Math.min(Rules.FIT, count);
        line(xs, 0, n);
        double lx = slope;
        line(zs, 0, n);
        double lz = slope;
        // the newest step against the fitted heading
        int a = at(0), b = at(1);
        double dt = Math.max(1, tick[a] - tick[b]);
        sx = (xs[a] - xs[b]) / dt;
        sz = (zs[a] - zs[b]) / dt;
        double fs = Math.hypot(lx, lz), ss = Math.hypot(sx, sz);
        turned = n > Rules.TURN_FIT && fs > Rules.MOVING && ss > Rules.MOVING
                && (sx * lx + sz * lz) / (fs * ss) < Rules.TURN_COS;
        // a stop or a start from a stand is a turn as well: the long fit lags it by half its span
        if (!turned && n > Rules.TURN_FIT && Math.hypot(sx - lx, sz - lz) > Rules.ACCEL) turned = true;
        if (turned) {
            vx = sx;
            vz = sz;
        } else {
            vx = lx;
            vz = lz;
        }
        // how far the steps wandered from the line: the per-tick velocity scatter
        double r = 0;
        int steps = 0;
        for (int i = 0; i + 1 < n; i++) {
            int p = at(i), q = at(i + 1);
            double d = Math.max(1, tick[p] - tick[q]);
            double ex = (xs[p] - xs[q]) / d - lx, ez = (zs[p] - zs[q]) / d - lz;
            r += ex * ex + ez * ez;
            steps++;
        }
        resid = steps == 0 ? 0 : Math.sqrt(r / steps);
        // acceleration: the newer half's velocity less the older half's, over the time between their middles
        ax = az = 0;
        if (!turned && count >= 4) {
            int half = Math.min(count, Rules.FIT + 1) / 2;
            line(xs, 0, half);
            double newX = slope, newMid = mid;
            line(zs, 0, half);
            double newZ = slope;
            line(xs, half, half);
            double oldX = slope, oldMid = mid;
            line(zs, half, half);
            double oldZ = slope;
            double span = newMid - oldMid;
            if (span > 0) {
                double cax = (newX - oldX) / span, caz = (newZ - oldZ) / span;
                if (Math.hypot(cax, caz) >= Rules.ACCEL_MIN) {
                    double m = Math.hypot(cax, caz), cap = Rules.ACCEL;
                    ax = m > cap ? cax * cap / m : cax;
                    az = m > cap ? caz * cap / m : caz;
                }
            }
        }
    }

    private double slope, mid; // the last line()'s answer, kept in fields so a fit allocates nothing

    /**
     * Least-squares slope through {@code n} samples of {@code v} starting {@code skip} back from the newest, into
     * {@link #slope}; their mean tick relative to the newest into {@link #mid}.
     */
    private void line(double[] v, int skip, int n) {
        double tm = 0, vm = 0;
        long t0 = tick[head];
        for (int i = skip; i < skip + n; i++) {
            int k = at(i);
            tm += tick[k] - t0;
            vm += v[k];
        }
        tm /= n;
        vm /= n;
        double num = 0, den = 0;
        for (int i = skip; i < skip + n; i++) {
            int k = at(i);
            double dt = tick[k] - t0 - tm;
            num += dt * (v[k] - vm);
            den += dt * dt;
        }
        slope = den == 0 ? 0 : num / den;
        mid = tm;
    }

    /**
     * Where the opponent will be {@code ahead} ticks after {@code now}. Ticks since the newest sample are added to the
     * horizon, so a stale model widens instead of pretending. Null when there is nothing to say: no samples, or none
     * for {@link Rules#LOST} ticks.
     */
    public Prediction predict(long now, double ahead, Ground floorOf) {
        if (count == 0) return null;
        long age = Math.max(0, now - tick[head]);
        if (age > Rules.LOST) return null;
        double h = Math.max(0, ahead) + age;
        int flags = 0;
        if (count < 3) flags |= Prediction.FEW_SAMPLES;
        if (age > Rules.STALE) flags |= Prediction.STALE;
        if (turned) flags |= Prediction.TURNING;
        if (airborne) flags |= Prediction.AIRBORNE;
        if (jumped || lastJumpTick != Long.MIN_VALUE && tick[head] - lastJumpTick <= 12) flags |= Prediction.JUMPING;
        // horizontal: the velocity with a bounded lead of the acceleration, capped at a player's top speed
        double px = xs[head], pz = zs[head];
        int whole = (int) Math.floor(h);
        double cvx = vx, cvz = vz;
        for (int k = 1; k <= whole + 1; k++) {
            if (k <= Rules.ACCEL_TICKS) {
                cvx += ax;
                cvz += az;
                double s = Math.hypot(cvx, cvz);
                if (s > Rules.VMAX) {
                    cvx *= Rules.VMAX / s;
                    cvz *= Rules.VMAX / s;
                }
            }
            double part = k <= whole ? 1 : h - whole;
            px += cvx * part;
            pz += cvz * part;
        }
        // vertical: vanilla gravity from the step the next move will make, or standing at the last ground height
        double py = ys[head];
        double floor = Double.NaN;
        if (floorOf != null) floor = floorOf.floor(px, ys[head] + 1.3, pz);
        if (Double.isNaN(floor) && !Double.isNaN(lastGroundY) && airborne) {
            floor = lastGroundY;
            flags |= Prediction.FLOOR_ASSUMED;
        }
        double vyNow = vy;
        if (airborne) {
            for (int k = 1; k <= whole + 1; k++) {
                double part = k <= whole ? 1 : h - whole;
                double step = vyNow;
                py += step * part;
                vyNow = (vyNow - Rules.GRAVITY) * Rules.DRAG;
                // only a falling body lands: one rising past a ledge's top is still climbing over it
                if (step < 0 && !Double.isNaN(floor) && py < floor) {
                    py = floor;
                    vyNow = 0;
                    break;
                }
            }
        } else if (!Double.isNaN(floor) && floor < py - 0.6) {
            // standing, but the floor under where it is heading lies lower: a walk off a ledge
            double fall = 0, v = 0;
            for (int k = 1; k <= whole && py - fall > floor; k++) {
                v = (v - Rules.GRAVITY) * Rules.DRAG;
                fall -= v;
            }
            py = Math.max(floor, py - fall);
            flags |= Prediction.LEDGE;
        }
        // radii. The bound is centred on the newest step held for the horizon, the one velocity known to have been
        // real: from it no player moves further than ACCEL a tick compounds to, and our centre's offset from it adds on
        double last = Math.hypot(sx, sz);
        double offset = Math.hypot(px - (xs[head] + sx * h), pz - (zs[head] + sz * h));
        double bound = Rules.BASE + offset + Math.min(Rules.ACCEL * h * (h + 1) / 2, (Rules.VMAX + last) * h);
        double spread = Math.min(bound, Rules.BASE + resid * h + 0.5 * turnRate * Rules.ACCEL * h * h);
        // vertical spread: a grounded body may jump (the jump's rise inside the horizon), an airborne one is ballistic
        double up = airborne ? Rules.BASE + 0.02 * h : rise(h), down = airborne ? Rules.BASE + 0.02 * h : Rules.BASE;
        return new Prediction(px, py, pz, cvx, vyNow, cvz, spread, bound, up, down, h, age, confidence(flags), flags);
    }

    /** Highest a standing player can be within {@code h} ticks by jumping now (vanilla arc from {@link Rules#JUMP}). */
    static double rise(double h) {
        double y = 0, best = 0, v = Rules.JUMP;
        for (int k = 0; k < Math.ceil(h); k++) {
            y += v;
            v = (v - Rules.GRAVITY) * Rules.DRAG;
            best = Math.max(best, y);
        }
        return Rules.BASE + best;
    }

    private Prediction.Confidence confidence(int flags) {
        if ((flags & (Prediction.STALE | Prediction.FEW_SAMPLES)) != 0) return Prediction.Confidence.LOW;
        if ((flags & (Prediction.TURNING | Prediction.JUMPING | Prediction.FLOOR_ASSUMED | Prediction.LEDGE)) != 0) return Prediction.Confidence.MEDIUM;
        return Prediction.Confidence.HIGH;
    }

    /** Bounded behaviour estimate over this opponent: how often it turns and jumps, and how fast it moves. */
    public Behavior behavior() {
        return new Behavior(turnRate, jumpRate, speed, samples, turns, jumps, resets);
    }

    public record Behavior(double turnRate, double jumpRate, double speed, long samples, long turns, long jumps, long resets) {}

    public int samples() {
        return count;
    }

    /** Times the history restarted on a teleport or a long gap. */
    public long resets() {
        return resets;
    }

    public long newestTick() {
        return count == 0 ? Long.MIN_VALUE : tick[head];
    }

    public double vx() {
        return vx;
    }

    public double vz() {
        return vz;
    }

    public double vy() {
        return vy;
    }

    public double residual() {
        return resid;
    }

    public boolean turned() {
        return turned;
    }

    public boolean jumped() {
        return jumped;
    }

    public boolean airborne() {
        return airborne;
    }
}
