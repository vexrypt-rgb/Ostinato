package baritone.bastion;

import baritone.pathing.kinematic.PlayerSim;

/**
 * Where a dropped item lands (26.3 LivingEntity#createItemStackToBeDropped + ItemEntity#tick, no random spread):
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
        // the throw in vanilla's floats and Mth sine table; its random spread (0.02 sideways, 0.1 up or down) is left out
        float yr = yaw * ((float) Math.PI / 180), pr = pitch * ((float) Math.PI / 180);
        float sinX = PlayerSim.msin(pr), cosX = PlayerSim.mcos(pr), sinY = PlayerSim.msin(yr), cosY = PlayerSim.mcos(yr);
        double vx = -sinY * cosX * 0.3f, vz = cosY * cosX * 0.3f, vy = -sinX * 0.3f + 0.1f;
        boolean ground = false;
        for (int t = 0; t < 100; t++) {
            vy -= 0.04;
            double nx = x + vx, nz = z + vz, ny = y + vy;
            double top = floor.top(nx, nz);
            if (y >= floor.top(x, z) - 1e-6 && top > y + 0.01) { nx = x; nz = z; vx = 0; vz = 0; top = floor.top(x, z); } // hit a wall: stop sideways
            if (ny <= top) { ny = top; vy = 0; ground = true; } else ground = false;
            x = nx; y = ny; z = nz;
            float f = ground ? 0.98f * 0.6f : 0.98f;
            vx *= f; vz *= f; vy *= 0.98f;
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
