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
    }

    /** The keys decoded for one interval (held the same over all its ticks). */
    public static final class Decoded {
        public int forward, strafe; // +1 W / A, -1 S / D
        public boolean jump, jumpObservable, sprint, sneak, using, kb, reset;
        /** Fitted impulse, in units of a full press. */
        public double forwardImpulse, strafeImpulse;
        /** Distance between the fitted and the observed position, metres. */
        public double residual;
        public double confidence;
        public int ticks;
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
        start.jumpStrength = o.jumpStrength;
        start.jumpBoost = o.jumpBoost;
        for (int i = 0; i < o.ticks; i++) {
            yaws[i] = lastYaw + wrap(o.yaw - lastYaw) * (i + 1) / o.ticks;
        }
        double scale = 0.98 * (o.usingFlag ? 0.2 : 1) * (o.sneakFlag ? 0.3 : 1);
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
        // posterior of the winner over every hypothesis tried
        double min = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) min = Math.min(min, costs[i]);
        double z = 0, pBest = 0;
        for (int i = 0; i < n; i++) {
            double w = Math.exp(-(costs[i] - min) / 2);
            z += w;
            if (fits[i] == bestFit) pBest = w;
        }
        // hypotheses that decode to the same keys are not rivals: sum them in
        for (int i = 0; i < n; i++) {
            if (fits[i] != bestFit && sameKeys(fits[i], bestFit, scale)) pBest += Math.exp(-(costs[i] - min) / 2);
        }
        pBest /= z;
        d.jump = bestFit.jumpAt >= 0;
        d.jumpObservable = jumpObservable;
        d.sprint = bestFit.sprint;
        d.kb = o.kb != null;
        d.forwardImpulse = bestFit.fz / scale;
        d.strafeImpulse = bestFit.sx / scale;
        d.forward = key(d.forwardImpulse);
        d.strafe = key(d.strafeImpulse);
        d.residual = Math.sqrt(bestFit.chi2) * SIGMA;
        double pf = axisConfidence(d.forwardImpulse, bestFit.sdF / scale), ps = axisConfidence(d.strafeImpulse, bestFit.sdS / scale);
        double fitQ = bestFit.chi2 < 12 ? 1 : Math.exp(-(bestFit.chi2 - 12) / 24); // the model itself not fitting
        d.confidence = pBest * pf * ps * fitQ;
        // carry the winner forward, snapped onto what was seen
        PlayerSim end = simulate(start, o, bestFit.sprint, bestFit.jumpAt, bestFit.kbAt, bestFit.sx, bestFit.fz);
        state.copyFrom(end);
        double rx = o.x - end.x, rz = o.z - end.z;
        state.x = o.x;
        state.y = o.y;
        state.z = o.z;
        state.vx += 0.5 * rx / o.ticks;
        state.vz += 0.5 * rz / o.ticks;
        state.onGround = o.onGround;
        if (o.onGround && state.vy > 0.2) state.vy = 0;
        lastYaw = o.yaw;
        lastFwd = bestFit.fz;
        lastStrafe = bestFit.sx;
        d.work = work;
        return d;
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
            state.vx = (o.x - state.x) / o.ticks;
            state.vy = (o.y - state.y) / o.ticks;
            state.vz = (o.z - state.z) / o.ticks;
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
        primed = !o.special;
    }

    private static final class Fit {
        boolean sprint;
        int kbAt, jumpAt;
        double sx, fz, chi2, prior, sdS, sdF;
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

    private PlayerSim simulate(PlayerSim start, Obs o, boolean sprint, int jump, int kbAt, double sx, double fz) {
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
            s.tickImpulse(yaws[i], sx, fz, sprint, sprint, jump == i);
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
