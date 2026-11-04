/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

/**
 * Will an attack sent this tick connect where the server judges it? Game-free.
 * <p>
 * A sword's hit is the client's: the attack packet names the entity under our crosshair and the server only checks
 * it is within reach plus 3 blocks of its own box for it (26.3 {@code ServerGamePacketListenerImpl.handleAttack},
 * verified). A spear's jab is the server's: the client sends {@code STAB} with no target, before this tick's move
 * packet, and the server casts {@code PiercingWeapon.attack} from our last sent eye and look against where it has
 * every entity (26.3 {@code MultiPlayerGameMode.piercingAttack}, {@code Minecraft.tick} order, verified). The ray is
 * the segment from {@code eye + look * minReach} to {@code eye + look * (maxReach + max(0, movement . look))}, and
 * an entity is hit when that segment crosses its box grown by the weapon's hitbox margin
 * ({@code ProjectileUtil.getHitEntitiesAlong}, verified). So a jab is judged against the opponent as it will be
 * when the packet lands: {@link Prediction} at the snapshot's lead.
 */
public final class AttackFeasibility {

    /**
     * SURE: the segment crosses the box wherever inside the {@code bound} the opponent is. LIKELY: it crosses the box
     * at the predicted point shrunk by the {@code spread}. POSSIBLE: it crosses the box somewhere inside the bound.
     * MISS: no position the bound allows is crossed. UNKNOWN: nothing is predicted.
     */
    public enum Verdict { SURE, LIKELY, POSSIBLE, MISS, UNKNOWN }

    private AttackFeasibility() {}

    /**
     * The verdict for a segment from {@code (ex,ey,ez) + look * from} to {@code + look * to} against an opponent of
     * the given width and height predicted at {@code p}, its box grown by {@code margin}. {@code look} must be unit.
     */
    public static Verdict ray(double ex, double ey, double ez, double lx, double ly, double lz, double from, double to,
                              double margin, Prediction p, double width, double height) {
        if (p == null) return Verdict.UNKNOWN;
        double x0 = ex + lx * from, y0 = ey + ly * from, z0 = ez + lz * from;
        double x1 = ex + lx * to, y1 = ey + ly * to, z1 = ez + lz * to;
        double half = width / 2 + margin;
        // feet anywhere in [y - down, y + up]: the box common to all of them, and the box any of them reaches
        double sureLo = p.y() + p.up() - margin, sureHi = p.y() - p.down() + height + margin;
        double sureHalf = half - p.bound();
        if (sureHalf > 0 && sureHi > sureLo && crosses(x0, y0, z0, x1, y1, z1,
                p.x() - sureHalf, sureLo, p.z() - sureHalf, p.x() + sureHalf, sureHi, p.z() + sureHalf)) return Verdict.SURE;
        double likelyHalf = half - p.spread();
        if (likelyHalf > 0 && crosses(x0, y0, z0, x1, y1, z1,
                p.x() - likelyHalf, p.y() - margin, p.z() - likelyHalf, p.x() + likelyHalf, p.y() + height + margin, p.z() + likelyHalf)) return Verdict.LIKELY;
        double anyHalf = half + p.bound();
        if (crosses(x0, y0, z0, x1, y1, z1,
                p.x() - anyHalf, p.y() - p.down() - margin, p.z() - anyHalf, p.x() + anyHalf, p.y() + p.up() + height + margin, p.z() + anyHalf)) return Verdict.POSSIBLE;
        return Verdict.MISS;
    }

    /**
     * Distance along the look from the eye to where the segment first enters the box at the predicted point (grown by
     * {@code margin}), or NaN when it does not. A segment starting inside enters at {@code from}.
     */
    public static double entry(double ex, double ey, double ez, double lx, double ly, double lz, double from, double to,
                               double margin, Prediction p, double width, double height) {
        if (p == null) return Double.NaN;
        double half = width / 2 + margin;
        double t = enter(ex + lx * from, ey + ly * from, ez + lz * from, lx * (to - from), ly * (to - from), lz * (to - from),
                p.x() - half, p.y() - margin, p.z() - half, p.x() + half, p.y() + height + margin, p.z() + half);
        return Double.isNaN(t) ? Double.NaN : from + t * (to - from);
    }

    /** The segment from p0 to p1 crosses, or starts inside, the box. */
    static boolean crosses(double x0, double y0, double z0, double x1, double y1, double z1,
                           double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return !Double.isNaN(enter(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0, minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** Slab test: the fraction of {@code d} at which the segment enters the box (0 when it starts inside), or NaN. */
    static double enter(double x, double y, double z, double dx, double dy, double dz,
                        double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double lo = 0, hi = 1;
        double[] o = {x, y, z}, d = {dx, dy, dz}, mn = {minX, minY, minZ}, mx = {maxX, maxY, maxZ};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < mn[a] || o[a] > mx[a]) return Double.NaN;
                continue;
            }
            double t1 = (mn[a] - o[a]) / d[a], t2 = (mx[a] - o[a]) / d[a];
            if (t1 > t2) {
                double s = t1;
                t1 = t2;
                t2 = s;
            }
            lo = Math.max(lo, t1);
            hi = Math.min(hi, t2);
            if (lo > hi) return Double.NaN;
        }
        return lo;
    }

    /** A unit look vector from yaw and pitch in degrees, as vanilla's {@code Entity.calculateViewVector}. */
    public static double[] look(float pitchDeg, float yawDeg) {
        double xr = Math.toRadians(pitchDeg), yr = Math.toRadians(-yawDeg);
        double xc = Math.cos(xr);
        return new double[]{Math.sin(yr) * xc, -Math.sin(xr), Math.cos(yr) * xc};
    }
}
