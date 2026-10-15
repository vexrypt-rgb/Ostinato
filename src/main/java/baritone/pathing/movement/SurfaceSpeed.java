package baritone.pathing.movement;

/**
 * Walk time from the block underfoot. Soul sand is priced by the caller (about half speed, not the raw 0.4),
 * so it is left alone here. Ice and blue ice are cheaper; honey and other slow floors are not.
 * A normal stone floor returns 1, so a path that never touches those blocks is unchanged.
 */
public final class SurfaceSpeed {

    private static final double NORMAL_DRAG = 1 - 0.6 * 0.91;

    private SurfaceSpeed() {}

    public static double one(float speedFactor, float friction, boolean alreadySoulSand) {
        double m = 1;
        if (!alreadySoulSand) {
            if (speedFactor > 1.001f) {
                m /= speedFactor;
            } else if (speedFactor > 0.05f && speedFactor < 0.999f) {
                m /= speedFactor;
            }
        }
        if (friction > 0.8f) {
            double drag = 1 - friction * 0.91;
            if (drag > 0.01) {
                m *= drag / NORMAL_DRAG;
            }
        }
        return m;
    }

    /** Half the step is on each floor. */
    public static double pair(float srcSpeed, float srcFriction, boolean srcSoul,
                              float destSpeed, float destFriction, boolean destSoul) {
        return 0.5 * (one(srcSpeed, srcFriction, srcSoul) + one(destSpeed, destFriction, destSoul));
    }
}
