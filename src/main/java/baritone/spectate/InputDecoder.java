/*
 * Ostinato: new file (spectator learning). Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.pathing.kinematic.PlayerSim;

/**
 * Ostinato: works out what keys someone else is holding from where the server says they are. Every position update
 * of a watched player is one observation; between two of them lie one or more server ticks (players are sent every
 * second tick, plus whenever their metadata changes). For each interval the decoder runs {@link PlayerSim} from the
 * state it carried over and fits the movement impulse that reproduces the observed position:
 * <ul>
 * <li>Hypotheses: jump or not (only when the last update said on ground), sprinting as the metadata flag says or the
 * other way (penalised), and, when a velocity packet (knockback) came in, which tick of the interval it landed on.</li>
 * <li>For each, a Gauss-Newton fit of the horizontal impulse (strafe, forward), held inside the unit disk where vanilla
 * normalises, with finite-difference Jacobians from the simulator itself, so collisions and step-ups are in it.</li>
 * <li>The hypotheses are weighed by their residual over {@link #SIGMA} (the 1/4096 delta encoding, the 1.4 degree yaw
 * byte and model error) and the priors; keys are the fitted impulse past {@link #KEY} of a full press, scaled for
 * sneaking and item use when the metadata says so.</li>
 * </ul>
 * Confidence is the winning hypothesis's posterior times how far each fitted axis sits from its key threshold in
 * standard deviations of the fit. Position rounding is absorbed by the noise model; interpolation lag does not arise
 * because the decoder reads packet positions, never the interpolated entity. After each fit the carried state is
 * snapped onto the observation, keeping the fitted velocity, so error does not accumulate.
 */
public final class InputDecoder {
    /** Per-axis position noise, metres. */
    public static final double SIGMA = 0.0035;
    /** A key counts as held past this share of a full press. */
    public static final double KEY = 0.35;
    /** Longest interval decoded; anything longer restarts from the observation. */
    public static final int MAX_TICKS = 6;

    /** One position update of a watched player, with what the metadata said at the time. */
    public static final class Obs {
        public double x, y, z;
        public float yaw, pitch;
        public boolean onGround;
        /** Server ticks since the previous observation, at least 1. */
        public int ticks = 2;
        public boolean sprintFlag, sneakFlag, usingFlag;
        /** In water, gliding, riding, levitating: nothing here models it, so the interval is skipped. */
        public boolean special;
        /** A velocity packet received since the previous observation (knockback), or null. */
        public double[] kb;
        public double speedScale = 1, jumpStrength = 0.42, jumpBoost;
        /** How long the interval looked: game-thread arrival gap and network-thread gap in ticks (NaN unknown), server-tick bursts between (-1 unknown). */
        public double dtMain = Double.NaN, dtRecv = Double.NaN;
        public int bursts = -1;
        /** The network stamp's gap and bursts as seen, for logs, whether or not the decoder was given them. */
        public double dtSeen = Double.NaN;
        public int burstsSeen = -1;
    }

    /** The keys decoded for one interval (held the same over all its ticks). */
    public static final class Decoded {
        public int forward, strafe; // +1 W / A, -1 S / D
        public boolean jump, jumpObservable, sprint, sneak, using, kb, reset;
        /** Fitted impulse, in units of a full press. */
        public double forwardImpulse, strafeImpulse;
        /** Distance between the fitted and the observed position, metres. */
        public double residual;
        /** Observed less simulated position of the winner (diagnostics). */
        public double rx, ry, rz;
        public double confidence;
        public int ticks;
        /** The arrival-time guess of the tick count, and the posterior of the tick count chosen. */
        public int guess;
        public double pTicks;
        /** Simulator ticks spent on this interval. */
        public int work;

        /** W, A, S, D, then j (jump) p (sprint) s (sneak) u (using), for logs: {@code WA-p}. */
        public String keys() {
            StringBuilder sb = new StringBuilder();
            if (forward > 0) sb.append('W');
            if (strafe > 0) sb.append('A');
            if (forward < 0) sb.append('S');
            if (strafe < 0) sb.append('D');
            if (sb.length() == 0) sb.append('.');
            sb.append(jump ? "j" : "").append(sprint ? "p" : "").append(sneak ? "s" : "").append(using ? "u" : "");
            return sb.toString();
        }
    }

    private final PlayerSim state;
    private boolean primed;
    private float lastYaw;
    private double lastFwd, lastStrafe;
    private int lastKeyF, lastKeyS; // the keys read last interval
    private boolean lastClientNorm = true; // diagonals as a client makes them (normalised, then 0.98) rather than a server bot
    private final float[] yaws = new float[MAX_TICKS];
    private int work;

    public InputDecoder() {
        state = new PlayerSim(null);
    }

    private PlayerSim sim(PlayerSim.World w) {
        return new PlayerSim(w);
    }

    /** Forget the carried state: the next observation starts over. */
    public void reset() {
        primed = false;
    }

    public boolean primed() {
        return primed;
    }

    /** Decodes the interval that ends at {@code o}; returns a {@link Decoded#reset} result when it cannot. */
    public Decoded step(PlayerSim.World world, Obs o) {
        work = 0;
        Decoded d = new Decoded();
        d.ticks = o.ticks;
        d.sneak = o.sneakFlag;
        d.using = o.usingFlag;
        d.sprint = o.sprintFlag;
        double jumpLen = Math.sqrt(sq(o.x - state.x) + sq(o.z - state.z));
        if (!primed || o.special || o.ticks < 1 || o.ticks > MAX_TICKS || jumpLen > 1.2 * o.ticks + 1 || Math.abs(o.y - state.y) > 4) {
            restart(o);
            d.reset = true;
            return d;
        }
        PlayerSim start = sim(world).copyFrom(state);
        start.speedScale = o.speedScale;
        // Ostinato: 1.16.1 PlayerSim has no jumpStrength (vanilla 0.42 jump)
        start.jumpBoost = o.jumpBoost;
        double scale = 0.98 * (o.usingFlag ? 0.2 : 1) * (o.sneakFlag ? 0.3 : 1);
        // How many server ticks the interval spans is itself a guess (arrival times jitter, and metadata changes send an
        // update a tick early): try the guess and its neighbours, each weighed by how well its length fits the timing.
        int guess = o.ticks;
        d.guess = guess;
        Eval best = null;
        double zt = 0;
        Eval[] evals = new Eval[3];
        int ne = 0;
        boolean sure = timingSure(o, guess);
        int lo = sure ? guess : Math.max(1, guess - 1), hi = sure ? guess : Math.min(MAX_TICKS, guess + 1);
        for (int tc = lo; tc <= hi; tc++) {
            o.ticks = tc;
            Eval e = evaluate(start, o, scale);
            e.ticks = tc;
            e.cost += timingCost(o, tc, guess);
            evals[ne++] = e;
            if (best == null || e.cost < best.cost) best = e;
            // with only game-thread timing: the guess fitting within the noise ends the search
            if (tc == guess && e.bestFit.chi2 < 2 && Double.isNaN(o.dtRecv)) break;
        }
        double[] mass = new double[9];
        for (int i = 0; i < ne; i++) {
            double w = Math.exp(-(evals[i].cost - best.cost) / 2);
            zt += w;
            for (int k = 0; k < 9; k++) mass[k] += w * evals[i].keyMass[k];
        }
        d.pTicks = 1 / zt;
        o.ticks = best.ticks;
        d.ticks = best.ticks;
        for (int i = 0; i < best.ticks; i++) yaws[i] = lastYaw + wrap(o.yaw - lastYaw) * (i + 1) / best.ticks;
        Fit bestFit = best.bestFit;
        double pBest = best.pBest / zt;
        d.jump = bestFit.jumpAt >= 0;
        d.jumpObservable = best.jumpObservable;
        d.sprint = bestFit.sprint;
        d.kb = o.kb != null;
        d.forwardImpulse = bestFit.fz / scale;
        d.strafeImpulse = bestFit.sx / scale;
        d.forward = key(d.forwardImpulse);
        d.strafe = key(d.strafeImpulse);
        if (bestFit.discrete) {
            d.forward = bestFit.keyF;
            d.strafe = bestFit.keyS;
        }
        d.residual = Math.sqrt(bestFit.chi2) * SIGMA;
        if (bestFit.discrete) {
            // the posterior of the keys read, over every hypothesis and tick count; a fit far outside the noise means
            // none of them is what happened, so it leans back to knowing nothing (one in nine)
            double post = mass[keyIndex(d.forward, d.strafe)] / zt;
            double fitQ = bestFit.chi2 < FIT_OK ? 1 : Math.exp(-(bestFit.chi2 - FIT_OK) / FIT_SCALE);
            d.confidence = fitQ * post + (1 - fitQ) * MISFIT_BASE;
        } else {
            double pf = axisConfidence(d.forwardImpulse, bestFit.sdF / scale), ps = axisConfidence(d.strafeImpulse, bestFit.sdS / scale);
            double fitQ = bestFit.chi2 < 12 ? 1 : Math.exp(-(bestFit.chi2 - 12) / 24); // the model itself not fitting
            d.confidence = pBest * pf * ps * fitQ;
        }
        // carry the winner forward, snapped onto what was seen
        PlayerSim end = simulate(start, o, bestFit);
        state.copyFrom(end);
        double rx = o.x - end.x, rz = o.z - end.z;
        d.rx = rx;
        d.ry = o.y - end.y;
        d.rz = rz;
        state.x = o.x;
        state.y = o.y;
        state.z = o.z;
        state.vx += 0.5 * rx / o.ticks;
        state.vz += 0.5 * rz / o.ticks;
        // and vertically: a missed vertical push (knockback skipped, a restart mid-air) otherwise stays in the
        // carried velocity for the rest of the arc
        if (!o.onGround) state.vy += 0.7 * (o.y - end.y) / o.ticks;
        state.onGround = o.onGround;
        if (o.onGround && state.vy > 0.2) state.vy = 0;
        lastYaw = o.yaw;
        lastFwd = bestFit.fz;
        lastStrafe = bestFit.sx;
        lastKeyF = d.forward;
        lastKeyS = d.strafe;
        if (bestFit.discrete) lastClientNorm = bestFit.clientNorm;
        d.work = work;
        return d;
    }

    /** Cost of an interval's tick count after an {@link #evaluate}: hypotheses weighed, the best one's fit and posterior. */
    private static final class Eval {
        Fit bestFit;
        double cost, pBest;
        boolean jumpObservable;
        int ticks;
        /** Posterior over the keys held at the interval's end, index {@link #keyIndex}, summing to 1. */
        final double[] keyMass = new double[9];
    }

    static int keyIndex(int f, int s) {
        return (f + 1) * 3 + (s + 1);
    }

    /**
     * -Dostinato.spectate.continuous=true: fit the keys as one continuous impulse held over the interval, the first
     * decoder (A/B only). Otherwise the keys are read as keys: every W/A/S/D combination, held all along or pressed
     * on some tick of the interval after holding the previous interval's keys, each scored against what was seen.
     */
    static final boolean CONTINUOUS = Boolean.getBoolean("ostinato.spectate.continuous");
    /** Chance a player changes keys on a given tick (bench carpet bots ~0.4, people far less). */
    static double KEY_HAZARD = Double.parseDouble(System.getProperty("ostinato.spectate.keyHazard", "0.4"));
    /**
     * Confidence calibration. Key readings are weighed against each other with their chi-square divided by
     * KEY_TEMP (SIGMA is the packet's noise, but the model's own error is larger, so raw chi-square differences
     * overstate how sure a reading is). A winner whose chi-square is past FIT_OK is a model miss, and its confidence
     * leans over FIT_SCALE toward MISFIT_BASE, how often such misses still read the keys right. Fitted on bench
     * replays (TruthReplayTest); system properties override them for refits.
     */
    static double KEY_TEMP = Double.parseDouble(System.getProperty("ostinato.spectate.temp", "1"));
    static double FIT_OK = Double.parseDouble(System.getProperty("ostinato.spectate.fitOk", "12"));
    static double FIT_SCALE = Double.parseDouble(System.getProperty("ostinato.spectate.fitScale", "24"));
    static double MISFIT_BASE = Double.parseDouble(System.getProperty("ostinato.spectate.misfitBase", "0.5"));
    /** Extra cost of an analog (non-key) impulse, tried only when no key reading fits. */
    static final double ANALOG_PRIOR = 10;

    /** Extra cost of an interval length other than the arrival-time guess, when only game-thread timing is known. */
    static final double TICKS_PRIOR = 9;
    /** Network-thread gaps scatter about this much (in ticks) around a whole number of server ticks. */
    static final double RECV_SIGMA = 0.1;

    /**
     * Server ticks since the previous update, from timing alone. The network stamp's burst count is exact when every
     * server tick sent this client something, so it is taken when the clock agrees with it; otherwise the clock,
     * rounded. Without a stamp, game-thread arrival, with players' usual 2-tick spacing favoured.
     */
    public static int guessTicks(Obs o, boolean mainClock) {
        if (!mainClock && !Double.isNaN(o.dtRecv)) {
            if (o.bursts >= 1 && o.bursts <= MAX_TICKS && Math.abs(o.bursts - o.dtRecv) < 0.5) return o.bursts;
            return (int) Math.max(1, Math.round(o.dtRecv));
        }
        if (Double.isNaN(o.dtMain)) return o.ticks;
        int t = (int) Math.round(o.dtMain);
        if (Math.abs(o.dtMain - 2) < 0.75) t = 2;
        return Math.max(1, t);
    }

    /** -2 log prior of an interval of {@code tc} ticks given its timing, relative to the guess. */
    static double timingCost(Obs o, int tc, int guess) {
        if (Double.isNaN(o.dtRecv)) return tc == guess ? 0 : TICKS_PRIOR;
        return timingNll(o, tc) - timingNll(o, guess);
    }

    /**
     * Network-thread gaps sit within {@link #RECV_SIGMA} of the true tick count but now and then far off (a server
     * hiccup, a packet held in a socket buffer): a narrow normal plus a broad floor. Measured on the local bench: 99%
     * of gaps round to the true count, residual sd 0.08 ticks.
     */
    private static double timingNll(Obs o, int tc) {
        double z = (o.dtRecv - tc) / RECV_SIGMA;
        double tail = o.bursts == tc ? 0.003 : 0.02; // the burst count agreeing makes an outlier rarer
        double l = (1 - tail) * Math.exp(-z * z / 2) / (2.5066 * RECV_SIGMA) + tail / MAX_TICKS;
        return -2 * Math.log(l);
    }

    /** The timing alone settles the length: the network gap sits on a whole tick count and the burst count agrees. */
    private static boolean timingSure(Obs o, int guess) {
        if (Double.isNaN(o.dtRecv)) return false;
        return o.bursts == guess && Math.abs(o.dtRecv - guess) < 0.3;
    }

    private Eval evaluate(PlayerSim start, Obs o, double scale) {
        if (CONTINUOUS) return evaluateContinuous(start, o, scale);
        Eval disc = evaluateKeys(start, o, scale);
        if (disc.bestFit.chi2 <= ANALOG_PRIOR) return disc;
        // no key reading fits: maybe not keys at all (a controller mod, a speed we do not know); the analog fit joins
        // as one more hypothesis at a price
        Eval an = evaluateContinuous(start, o, scale);
        double ca = an.cost + ANALOG_PRIOR;
        if (ca >= disc.cost) {
            double w = Math.exp(-(ca - disc.cost) / 2), z = 1 + w;
            for (int k = 0; k < 9; k++) disc.keyMass[k] = (disc.keyMass[k] + w * an.keyMass[k]) / z;
            return disc;
        }
        double w = Math.exp(-(disc.cost - ca) / 2), z = 1 + w;
        for (int k = 0; k < 9; k++) an.keyMass[k] = (an.keyMass[k] + w * disc.keyMass[k]) / z;
        an.cost = ca;
        an.jumpObservable |= disc.jumpObservable;
        return an;
    }

    /** The impulse of keys f, s at the sneak/use scale: a client normalises diagonals before its 0.98, a bot after. */
    private static double[] impulse(int f, int s, double scale, boolean clientNorm) {
        double fz = f, sx = s;
        if (f != 0 && s != 0 && clientNorm) {
            fz *= Math.sqrt(0.5);
            sx *= Math.sqrt(0.5);
        }
        return new double[]{sx * scale, fz * scale}; // a bot's longer-than-one diagonal is normalised in tickImpulse
    }

    private Eval evaluateKeys(PlayerSim start, Obs o, double scale) {
        for (int i = 0; i < o.ticks; i++) {
            yaws[i] = lastYaw + wrap(o.yaw - lastYaw) * (i + 1) / o.ticks;
        }
        int n = o.ticks;
        int kbHyps = o.kb == null ? 1 : n + 1;
        double keep = -2 * Math.log(1 - KEY_HAZARD), change = -2 * Math.log(KEY_HAZARD / 8);
        double[] prev = impulse(lastKeyF, lastKeyS, scale, lastClientNorm);
        boolean prevDiag = lastKeyF != 0 && lastKeyS != 0;
        Fit best = null;
        double[] costs = new double[512];
        int[] keyOf = new int[512];
        int m = 0;
        boolean jumpObservable = false;
        for (int pass = 0; pass < 2; pass++) {
            boolean sprint = pass == 0 ? o.sprintFlag : !o.sprintFlag;
            if (pass == 1 && best != null && best.chi2 < 6) break; // the flag's own reading fits: no need to doubt it
            for (int kb = 0; kb < kbHyps; kb++) {
                int kbAt = o.kb == null ? -1 : kb;
                // the previous keys held throughout, without a jump: also tells which ticks could jump
                Fit hold = keyFit(start, o, sprint, -1, kbAt, 0, prev, prev, lastKeyF, lastKeyS, lastClientNorm);
                int grounded = groundMask;
                jumpObservable |= grounded != 0;
                for (int j = -1; j < n; j++) {
                    if (j >= 0 && (grounded & (1 << j)) == 0) continue;
                    double base = (pass == 1 ? 6 : 0) + (j >= 0 ? 1.5 : 0);
                    // held: the previous keys all along
                    Fit h = j < 0 ? hold : keyFit(start, o, sprint, j, kbAt, 0, prev, prev, lastKeyF, lastKeyS, lastClientNorm);
                    h.prior = base + n * keep + (prevDiag ? 0 : 0);
                    if (m < costs.length) {
                        costs[m] = h.chi2 + h.prior;
                        keyOf[m++] = keyIndex(lastKeyF, lastKeyS);
                    }
                    if (best == null || h.chi2 + h.prior < best.chi2 + best.prior) best = h;
                    // or new keys pressed at tick c (0: from the interval's start)
                    for (int c = 0; c < n; c++) {
                        for (int f = -1; f <= 1; f++) {
                            for (int sk = -1; sk <= 1; sk++) {
                                if (f == lastKeyF && sk == lastKeyS) continue;
                                boolean diag = f != 0 && sk != 0;
                                for (int v = 0; v < (diag ? 2 : 1); v++) {
                                    boolean cn = diag ? v == 0 : lastClientNorm;
                                    double[] k = impulse(f, sk, scale, cn);
                                    Fit fit = keyFit(start, o, sprint, j, kbAt, c, prev, k, f, sk, cn);
                                    fit.prior = base + change + (n - 1) * keep + (diag ? 1.39 : 0);
                                    if (m < costs.length) {
                                        costs[m] = fit.chi2 + fit.prior;
                                        keyOf[m++] = keyIndex(f, sk);
                                    }
                                    if (fit.chi2 + fit.prior < best.chi2 + best.prior) best = fit;
                                }
                            }
                        }
                    }
                }
            }
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < m; i++) min = Math.min(min, costs[i]);
        Eval e = new Eval();
        double z = 0;
        for (int i = 0; i < m; i++) {
            double w = Math.exp(-(costs[i] - min) / (2 * KEY_TEMP));
            z += w;
            e.keyMass[keyOf[i]] += w;
        }
        for (int k = 0; k < 9; k++) e.keyMass[k] /= z;
        e.bestFit = best;
        e.cost = min;
        e.pBest = e.keyMass[keyIndex(best.keyF, best.keyS)];
        e.jumpObservable = jumpObservable;
        return e;
    }

    private Fit keyFit(PlayerSim start, Obs o, boolean sprint, int jump, int kbAt, int changeAt, double[] pre, double[] k, int f, int s, boolean clientNorm) {
        Fit fit = new Fit();
        fit.discrete = true;
        fit.sprint = sprint;
        fit.jumpAt = jump;
        fit.kbAt = kbAt;
        fit.changeAt = changeAt;
        fit.psx = pre[0];
        fit.pfz = pre[1];
        fit.sx = k[0];
        fit.fz = k[1];
        fit.keyF = f;
        fit.keyS = s;
        fit.clientNorm = clientNorm;
        PlayerSim e = simulate(start, o, fit);
        fit.chi2 = (sq(o.x - e.x) + sq(o.y - e.y) + sq(o.z - e.z)) / (SIGMA * SIGMA);
        return fit;
    }

    private Eval evaluateContinuous(PlayerSim start, Obs o, double scale) {
        for (int i = 0; i < o.ticks; i++) {
            yaws[i] = lastYaw + wrap(o.yaw - lastYaw) * (i + 1) / o.ticks;
        }
        int kbHyps = o.kb == null ? 1 : o.ticks + 1;
        Fit bestFit = null;
        double[] costs = new double[48];
        Fit[] fits = new Fit[48];
        int n = 0;
        boolean jumpObservable = false;
        for (int pass = 0; pass < 2; pass++) {
            boolean sprint = pass == 0 ? o.sprintFlag : !o.sprintFlag;
            if (pass == 1 && bestFit != null && bestFit.chi2 < 6) break; // the flag's own reading fits: no need to doubt it
            for (int k = 0; k < kbHyps; k++) {
                int kbAt = o.kb == null ? -1 : k;
                // no jump first; a jump is tried on each tick that run stood on the ground at its start
                Fit f0 = fit(start, o, sprint, -1, kbAt);
                int grounded = groundMask;
                jumpObservable |= grounded != 0;
                for (int j = -1; j < o.ticks; j++) {
                    if (j >= 0 && (grounded & (1 << j)) == 0) continue;
                    if (n >= fits.length) break;
                    Fit f = j < 0 ? f0 : fit(start, o, sprint, j, kbAt);
                    f.prior = (pass == 1 ? 6 : 0) + (j >= 0 ? 1.5 : 0);
                    costs[n] = f.chi2 + f.prior;
                    fits[n++] = f;
                    if (bestFit == null || f.chi2 + f.prior < bestFit.chi2 + bestFit.prior) bestFit = f;
                }
            }
        }
        // posterior of the winner over every hypothesis tried; those that decode to the same keys are not rivals
        double min = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) min = Math.min(min, costs[i]);
        double z = 0, pBest = 0;
        Eval e = new Eval();
        for (int i = 0; i < n; i++) {
            double w = Math.exp(-(costs[i] - min) / 2);
            z += w;
            if (fits[i] == bestFit || sameKeys(fits[i], bestFit, scale)) pBest += w;
            e.keyMass[keyIndex(key(fits[i].fz / scale), key(fits[i].sx / scale))] += w;
        }
        for (int k = 0; k < 9; k++) e.keyMass[k] /= z;
        e.bestFit = bestFit;
        e.cost = min;
        e.pBest = pBest / z; // of this tick count's hypotheses; step() weighs the tick counts against each other
        e.jumpObservable = jumpObservable;
        return e;
    }

    private boolean sameKeys(Fit a, Fit b, double scale) {
        return (a.jumpAt >= 0) == (b.jumpAt >= 0) && a.sprint == b.sprint && key(a.fz / scale) == key(b.fz / scale) && key(a.sx / scale) == key(b.sx / scale);
    }

    private static int key(double impulse) {
        return impulse > KEY ? 1 : impulse < -KEY ? -1 : 0;
    }

    /** Probability the fitted axis is on the side of its threshold it reads as, from its standard deviation. */
    private static double axisConfidence(double v, double sd) {
        if (!(sd > 1e-9)) return 0.5;
        double a = Math.abs(v), m = Math.min(Math.abs(a - KEY), a < KEY ? KEY : Double.MAX_VALUE);
        return phi(m / sd);
    }

    private static double phi(double x) { // normal CDF, Zelen-Severo
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double p = 1 - 0.3989422804 * Math.exp(-x * x / 2) * t * (0.31938153 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? p : 1 - p;
    }

    private void restart(Obs o) {
        if (primed && o.ticks >= 1 && o.ticks <= MAX_TICKS) {
            // the last move's displacement is the velocity it moved with; vanilla then applies friction and gravity
            // to make the next tick's (LivingEntity.travel): carrying the raw displacement over would add a whole
            // tick of gravity's worth of error to every move after a restart until the player lands
            double fr = o.onGround ? 0.6 * 0.91 : 0.91;
            state.vx = (o.x - state.x) / o.ticks * fr;
            state.vy = o.onGround ? 0 : ((o.y - state.y) / o.ticks - 0.08) * 0.98;
            state.vz = (o.z - state.z) / o.ticks * fr;
        } else {
            state.vx = state.vy = state.vz = 0;
        }
        if (o.kb != null) {
            state.vx = o.kb[0];
            state.vy = o.kb[1];
            state.vz = o.kb[2];
        }
        state.x = o.x;
        state.y = o.y;
        state.z = o.z;
        state.onGround = o.onGround;
        state.sprinting = o.sprintFlag;
        state.collidedH = false;
        state.jumpTicks = 0;
        lastYaw = o.yaw;
        lastFwd = lastStrafe = 0;
        lastKeyF = lastKeyS = 0;
        primed = !o.special;
    }

    private static final class Fit {
        boolean sprint;
        int kbAt, jumpAt;
        double sx, fz, chi2, prior, sdS, sdF;
        /** Key readings: the keys, the tick they were pressed on (ticks before it hold the previous impulse psx/pfz). */
        boolean discrete, clientNorm;
        int keyF, keyS, changeAt;
        double psx, pfz;
    }

    private Fit fit(PlayerSim start, Obs o, boolean sprint, int jump, int kbAt) {
        Fit f = new Fit();
        f.sprint = sprint;
        f.jumpAt = jump;
        f.kbAt = kbAt;
        // two starting points: last interval's impulse (people hold keys) and none
        double[] r = new double[3];
        double bestChi = Double.MAX_VALUE, bx = 0, bz = 0;
        double[][] seeds = {{lastStrafe, lastFwd}, {0, 0}};
        double[] jac = new double[6];
        for (double[] seed : seeds) {
            double sx = seed[0], fz = seed[1];
            double chi = residual(start, o, sprint, jump, kbAt, sx, fz, r);
            for (int it = 0; it < 4; it++) {
                double eps = 0.08;
                double[] r1 = new double[3], r2 = new double[3];
                residual(start, o, sprint, jump, kbAt, sx + eps, fz, r1);
                residual(start, o, sprint, jump, kbAt, sx, fz + eps, r2);
                for (int a = 0; a < 3; a++) {
                    jac[a] = -(r1[a] - r[a]) / eps; // d pos / d sx
                    jac[3 + a] = -(r2[a] - r[a]) / eps; // d pos / d fz
                }
                double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
                for (int a = 0; a < 3; a++) {
                    a11 += jac[a] * jac[a];
                    a12 += jac[a] * jac[3 + a];
                    a22 += jac[3 + a] * jac[3 + a];
                    b1 += jac[a] * r[a];
                    b2 += jac[3 + a] * r[a];
                }
                double lam = 1e-9 + 1e-6 * (a11 + a22);
                a11 += lam;
                a22 += lam;
                double det = a11 * a22 - a12 * a12;
                if (Math.abs(det) < 1e-18) break;
                double dx = (a22 * b1 - a12 * b2) / det, dz = (a11 * b2 - a12 * b1) / det;
                double nx = sx + dx, nz = fz + dz;
                double len = Math.sqrt(nx * nx + nz * nz);
                if (len > 1) { // vanilla normalises anything longer: past the rim nothing changes
                    nx /= len;
                    nz /= len;
                }
                double[] rn = new double[3];
                double chiN = residual(start, o, sprint, jump, kbAt, nx, nz, rn);
                if (chiN > chi) break; // no better: keep what we had
                boolean small = Math.abs(nx - sx) + Math.abs(nz - fz) < 2e-3;
                sx = nx;
                fz = nz;
                chi = chiN;
                System.arraycopy(rn, 0, r, 0, 3);
                // standard deviations of the fit from the curvature, for the confidence
                double inv11 = a22 / det, inv22 = a11 / det;
                f.sdS = SIGMA * Math.sqrt(Math.max(0, inv11));
                f.sdF = SIGMA * Math.sqrt(Math.max(0, inv22));
                if (small) break;
            }
            if (chi < bestChi) {
                bestChi = chi;
                bx = sx;
                bz = fz;
            }
            if (bestChi < 1) break; // the seed already explains it
        }
        f.sx = bx;
        f.fz = bz;
        f.chi2 = bestChi;
        if (f.sdS == 0 && f.sdF == 0) f.sdS = f.sdF = 1; // never moved: the fit says nothing
        return f;
    }

    /** Sum of squared residuals over SIGMA, observed less simulated; r receives the residual. */
    private double residual(PlayerSim start, Obs o, boolean sprint, int jump, int kbAt, double sx, double fz, double[] r) {
        PlayerSim e = simulate(start, o, sprint, jump, kbAt, sx, fz);
        r[0] = o.x - e.x;
        r[1] = o.y - e.y;
        r[2] = o.z - e.z;
        return (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]) / (SIGMA * SIGMA);
    }

    private PlayerSim scratch;

    /** Which ticks of the last simulated run began on the ground, one bit each. */
    private int groundMask;

    private PlayerSim simulate(PlayerSim start, Obs o, Fit f) {
        return f.discrete ? simulate(start, o, f.sprint, f.jumpAt, f.kbAt, f.changeAt, f.psx, f.pfz, f.sx, f.fz)
                : simulate(start, o, f.sprint, f.jumpAt, f.kbAt, 0, 0, 0, f.sx, f.fz);
    }

    private PlayerSim simulate(PlayerSim start, Obs o, boolean sprint, int jump, int kbAt, double sx, double fz) {
        return simulate(start, o, sprint, jump, kbAt, 0, 0, 0, sx, fz);
    }

    private PlayerSim simulate(PlayerSim start, Obs o, boolean sprint, int jump, int kbAt, int changeAt, double psx, double pfz, double sx, double fz) {
        if (scratch == null || scratch.world() != start.world()) scratch = new PlayerSim(start.world());
        PlayerSim s = scratch.copyFrom(start);
        s.sprinting = sprint;
        groundMask = 0;
        for (int i = 0; i < o.ticks; i++) {
            if (s.onGround) groundMask |= 1 << i;
            if (kbAt == i) {
                s.vx = o.kb[0];
                s.vy = o.kb[1];
                s.vz = o.kb[2];
            }
            boolean pre = i < changeAt;
            s.tickImpulse(yaws[i], pre ? psx : sx, pre ? pfz : fz, sprint, sprint, jump == i);
            work++;
        }
        if (kbAt == o.ticks) { // landed after the move: only the carried velocity knows
            s.vx = o.kb[0];
            s.vy = o.kb[1];
            s.vz = o.kb[2];
        }
        return s;
    }

    private static float wrap(float deg) {
        float d = deg % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static double sq(double v) {
        return v * v;
    }
}
