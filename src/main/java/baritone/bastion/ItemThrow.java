package baritone.bastion;

/**
 * Where a dropped item lands (vanilla Player#drop + ItemEntity#tick, no random spread):
 * spawn at eye y - 0.3, v = look * 0.3 with vy += 0.1; each tick vy -= 0.04, move, then v *= 0.98
 * (horizontal *= 0.6 * slipperiness * 0.98 once on the ground). Pure logic for aiming throws into a trap hole.
 */
public final class ItemThrow {
    private ItemThrow() {}

    /** Top surface y at a column (block floor + 1), e.g. hole bottom vs the rim. */
    public interface Floor { double top(double x, double z); }

    /** Final resting x, y, z after up to 100 ticks. yaw/pitch in degrees, Minecraft convention. */
    public static double[] land(double x, double eyeY, double z, float yaw, float pitch, Floor floor) {
        double y = eyeY - 0.3;
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double vx = -Math.sin(yr) * Math.cos(pr) * 0.3, vz = Math.cos(yr) * Math.cos(pr) * 0.3, vy = -Math.sin(pr) * 0.3 + 0.1;
        boolean ground = false;
        for (int t = 0; t < 100; t++) {
            vy -= 0.04;
            double nx = x + vx, nz = z + vz, ny = y + vy;
            double top = floor.top(nx, nz);
            if (y >= floor.top(x, z) - 1e-6 && top > y + 0.01) { nx = x; nz = z; vx = 0; vz = 0; top = floor.top(x, z); } // hit a wall: stop sideways
            if (ny <= top) { ny = top; vy = 0; ground = true; } else ground = false;
            x = nx; y = ny; z = nz;
            double f = ground ? 0.6 * 0.98 : 0.98;
            vx *= f; vz *= f; vy *= 0.98;
            if (ground && Math.abs(vx) < 1e-3 && Math.abs(vz) < 1e-3) break;
        }
        return new double[]{x, y, z};
    }

    /** Pitch (degrees, -30..90) whose landing is closest to (tx, tz) along the yaw toward it; yaw is returned in [0]. */
    public static float[] aim(double x, double eyeY, double z, double tx, double tz, Floor floor) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
        float best = 45; double bd = Double.MAX_VALUE;
        for (float p = -30; p <= 90; p += 1) {
            double[] l = land(x, eyeY, z, yaw, p, floor);
            double d = (l[0] - tx) * (l[0] - tx) + (l[2] - tz) * (l[2] - tz);
            if (d < bd) { bd = d; best = p; }
        }
        return new float[]{yaw, best, (float) Math.sqrt(bd)};
    }
}
