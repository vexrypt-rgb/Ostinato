package baritone.bombard;

/**
 * Vanilla 26.3 FishingHook free flight from the mean launch (triangular noise excluded), swept against PPE TNT
 * positions the way ProjectileUtil.getHitResultOnMoveVector does: hook tick k tests the pre-gravity segment
 * [p(k-1), p(k-1) + v(k-1)] against the TNT box inflated by computeMargin (tickCount k).
 */
public final class RodCast {
    public record Solution(float yaw, float pitch, int ticks) {}
    public record Point(double x, double y, double z) {}
    private static final double GRAVITY = (double) 0.03f, DRAG = 0.92, HALF = TntMechanics.SIZE / 2;
    /** Hook ticks searched; computeMargin reaches its 0.3 cap at tick 8. */
    public static final int MAX_TICKS = 8;
    /** PPE horizon needed: each hook tick k is also checked one tick late (send/spawn latency), plus one tick of aim lead. */
    public static final int HORIZON = MAX_TICKS + 3; // Ostinato: LATE_MAX (2) spawn delay plus one tick of aim lead
    /**
     * Ostinato: ticks between the client decision (PPE index 0) and the server hook's spawn. Traced 2026-11-02 in the
     * rod fixture (BOMBARDTRACE): 1 or 2 depending on client/server tick phase, never 0. The old {0,1} window let
     * a pitch that only hits at delay 0-1 through; at delay 2 the hook passed over the falling TNT.
     */
    public static final int LATE_MIN = 1, LATE_MAX = 2;
    /** Box shrink covering ~0.94% per-component launch noise over ~6 blocks plus PPE drift. */
    public static final double SHRINK = 0.08;
    private RodCast() {}

    /** Hook position after {@code ticks} moves; tick 0 is the spawn point. */
    public static Point point(double ex, double ey, double ez, float yaw, float pitch, int ticks) {
        double y = Math.toRadians(yaw), elevation = Math.toRadians(-pitch);
        double horizontal = 0.6 * Math.cos(elevation) + 0.5;
        double vertical = Math.tan(elevation) * horizontal;
        double sum = (1 - Math.pow(DRAG, ticks)) / (1 - DRAG);
        double drop = GRAVITY * (ticks - DRAG * sum) / (1 - DRAG);
        double range = 0.3 + horizontal * sum;
        return new Point(ex - Math.sin(y) * range, ey + vertical * sum - drop, ez + Math.cos(y) * range);
    }

    /** ProjectileUtil.computeMargin for the hook's k-th tick (tickCount is incremented before checkCollision). */
    static double margin(int tick) {
        return Math.max(0.0f, Math.min(0.3f, (float)(tick - 2) / 20.0f));
    }

    /**
     * First hook tick that surely enters the TNT box whether the hook's k-th tick meets PPE tick k or k+1, or -1.
     * Sure means the first tick whose segment reaches the box grown by {@code shrink} also crosses the box shrunk
     * by it, starting outside the grown box; a graze is not a hit.
     */
    public static int hits(double ex, double ey, double ez, float yaw, float pitch, float[] tntPath, int stride, double shrink) {
        if (tntPath == null || stride != 1) return -1;
        int sure = -1;
        for (int late = LATE_MIN; late <= LATE_MAX; late++) { // Ostinato: measured spawn delay
            int at = first(ex, ey, ez, yaw, pitch, tntPath, late, shrink);
            if (at < 0) return -1;
            sure = Math.max(sure, at);
        }
        return sure;
    }

    private static int first(double ex, double ey, double ez, float yaw, float pitch, float[] path, int late, double shrink) {
        double y = Math.toRadians(yaw), elevation = Math.toRadians(-pitch);
        double h = 0.6 * Math.cos(elevation) + 0.5;
        double px = ex - Math.sin(y) * 0.3, py = ey, pz = ez + Math.cos(y) * 0.3;
        double vx = -Math.sin(y) * h, vy = Math.tan(elevation) * h, vz = Math.cos(y) * h;
        for (int k = 1; k <= MAX_TICKS; k++) {
            int i = (k + late) * 3;
            if (i + 2 >= path.length) return -1;
            double tx = path[i], ty = path[i + 1], tz = path[i + 2], m = margin(k);
            double outer = HALF + m + shrink, inner = HALF + m - shrink;
            if (enters(px, py, pz, vx, vy, vz, tx, ty, tz, outer)) {
                boolean outside = !inside(px, py, pz, tx, ty, tz, outer);
                return outside && inner > 0 && enters(px, py, pz, vx, vy, vz, tx, ty, tz, inner) ? k : -1;
            }
            vy -= GRAVITY;
            px += vx; py += vy; pz += vz;
            vx *= DRAG; vy *= DRAG; vz *= DRAG;
        }
        return -1;
    }

    private static boolean inside(double px, double py, double pz, double tx, double ty, double tz, double r) {
        double cy = ty + HALF;
        return Math.abs(px - tx) < r && Math.abs(py - cy) < r && Math.abs(pz - tz) < r;
    }

    /** Slab test of p + t*v, t in [0, 1], against the cube of half-width r centred on the TNT. */
    private static boolean enters(double px, double py, double pz, double vx, double vy, double vz,
                                  double tx, double ty, double tz, double r) {
        double lo = 0, hi = 1;
        double[] p = {px, py, pz}, v = {vx, vy, vz}, c = {tx, ty + HALF, tz};
        for (int a = 0; a < 3; a++) {
            double min = c[a] - r, max = c[a] + r;
            if (Math.abs(v[a]) < 1e-7) {
                if (p[a] < min || p[a] > max) return false;
                continue;
            }
            double t0 = (min - p[a]) / v[a], t1 = (max - p[a]) / v[a];
            if (t0 > t1) { double s = t0; t0 = t1; t1 = s; }
            lo = Math.max(lo, t0); hi = Math.min(hi, t1);
            if (lo > hi) return false;
        }
        return true;
    }

    /**
     * Earliest sure hit over aim points at PPE ticks 1..MAX_TICKS (midway between tick t and t+1, matching the
     * latency window). Pitch is bisected so the mean arc crosses the aim point's height at its horizontal distance.
     */
    public static Solution solve(double ex, double ey, double ez, float[] tntPath, int stride) {
        if (tntPath == null || stride != 1) return null;
        Solution best = null;
        for (int t = 1; t <= MAX_TICKS && (t + 1) * 3 + 2 < tntPath.length; t++) {
            int a = t * 3, b = a + 3;
            double tx = (tntPath[a] + tntPath[b]) / 2, tz = (tntPath[a + 2] + tntPath[b + 2]) / 2;
            double ty = (tntPath[a + 1] + tntPath[b + 1]) / 2 + HALF;
            double dist = Math.hypot(tx - ex, tz - ez);
            float yaw = (float)Math.toDegrees(Math.atan2(-(tx - ex), tz - ez));
            double lo = -70, hi = 70;
            for (int k = 0; k < 30; k++) {
                double pitch = (lo + hi) / 2;
                double at = heightAt(ey, (float)pitch, dist);
                if (Double.isNaN(at) || at < ty) hi = pitch; // too low or too short: aim higher (more negative)
                else lo = pitch;
            }
            float pitch = (float)((lo + hi) / 2);
            int tick = hits(ex, ey, ez, yaw, pitch, tntPath, stride, SHRINK);
            if (tick > 0 && (best == null || tick < best.ticks)) best = new Solution(yaw, pitch, tick);
        }
        return best == null ? null : centre(ex, ey, ez, tntPath, best);
    }

    /**
     * The hit found aims at an earlier TNT position than the hook meets, so it sits at the edge of the pitch window;
     * move to the middle of the contiguous window so look overshoot and drift still land inside it.
     */
    private static Solution centre(double ex, double ey, double ez, float[] path, Solution seed) {
        float lo = seed.pitch, hi = seed.pitch;
        while (lo - 0.5f >= -70 && hits(ex, ey, ez, seed.yaw, lo - 0.5f, path, 1, SHRINK) > 0) lo -= 0.5f;
        while (hi + 0.5f <= 70 && hits(ex, ey, ez, seed.yaw, hi + 0.5f, path, 1, SHRINK) > 0) hi += 0.5f;
        float mid = (lo + hi) / 2;
        int tick = hits(ex, ey, ez, seed.yaw, mid, path, 1, SHRINK);
        return tick > 0 ? new Solution(seed.yaw, mid, tick) : seed;
    }

    /** {@link #solve} for the TNT {@code ticks} later: where to look now so the next tick's held rotation hits. */
    public static Solution lead(double ex, double ey, double ez, float[] tntPath, int stride, int ticks) {
        if (tntPath == null || stride != 1 || ticks < 0 || tntPath.length <= 3 * ticks) return null;
        return solve(ex, ey, ez, java.util.Arrays.copyOfRange(tntPath, 3 * ticks, tntPath.length), stride);
    }

    /** Mean arc height when the hook has travelled {@code dist} horizontally from the eye; NaN if it never does. */
    static double heightAt(double ey, float pitch, double dist) {
        double elevation = Math.toRadians(-pitch), h = 0.6 * Math.cos(elevation) + 0.5;
        double sum = (dist - 0.3) / h;
        if (sum <= 0) return ey;
        if (sum >= 1 / (1 - DRAG)) return Double.NaN;
        double n = Math.log(1 - sum * (1 - DRAG)) / Math.log(DRAG);
        return ey + Math.tan(elevation) * h * sum - GRAVITY * (n - DRAG * sum) / (1 - DRAG);
    }
}
