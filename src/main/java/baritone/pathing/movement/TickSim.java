package baritone.pathing.movement;

/**
 * Ostinato: a tiny step-by-step estimate of how many ticks a straight move really takes, from vanilla player
 * movement constants (ground friction, walk and sprint acceleration, air control, gravity, drag). It replaces the
 * fixed per-block tables in ActionCosts only when {@code tickAccurateCosts} is on. Everything here is a pure
 * function of a few doubles and runs once per {@link CalculationContext}, never per node, so it adds no heap.
 * <p>
 * One tick, as the player sees it: position += velocity, then horizontal velocity *= drag (0.546 on a normal
 * block, 0.91 in the air) and vertical velocity = (vy - 0.08) * 0.98. Input acceleration is added before the move:
 * 0.098 b/t walking on the ground (0.1 speed * 0.98 forward input), 0.1274 sprinting, 0.02 / 0.026 in the air.
 */
public final class TickSim {

    public static final double GROUND_DRAG = 0.6 * 0.91;
    public static final double AIR_DRAG = 0.91;
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double FORWARD_INPUT = 0.98;
    public static final double WALK_ACCEL = 0.1 * FORWARD_INPUT;
    public static final double SPRINT_ACCEL = 0.13 * FORWARD_INPUT;
    public static final double AIR_ACCEL = 0.02 * FORWARD_INPUT;
    public static final double AIR_SPRINT_ACCEL = 0.026 * FORWARD_INPUT;
    /** Half the player's width: the leading edge touches a block face this far before the centre reaches it. */
    public static final double HALF_WIDTH = 0.3;
    private static final int MAX_TICKS = 200;

    private TickSim() {}

    /** Ground speed in blocks/tick reached after holding forward long enough (walk 0.2159, sprint 0.2806). */
    public static double steadyGroundSpeed(boolean sprint) {
        return (sprint ? SPRINT_ACCEL : WALK_ACCEL) / (1 - GROUND_DRAG);
    }

    /**
     * Ticks to cover {@code distance} on flat ground starting at horizontal speed {@code v0}. Fractional on the
     * last tick so the result is continuous. {@code v0 = steadyGroundSpeed} is keep-momentum, {@code 0} is from
     * standstill.
     */
    public static double groundTicks(double distance, double v0, boolean sprint) {
        double a = sprint ? SPRINT_ACCEL : WALK_ACCEL;
        double x = 0, v = v0 * GROUND_DRAG; // v0 is the last tick's step; drag ran after it
        for (int t = 0; t < MAX_TICKS; t++) {
            double step = v + a;
            if (x + step >= distance) {
                return t + (distance - x) / step;
            }
            x += step;
            v = step * GROUND_DRAG;
        }
        return MAX_TICKS;
    }

    /** Extra ticks a ground move of {@code distance} costs from standstill versus already moving. */
    public static double standstillPenalty(double distance, boolean sprint) {
        return groundTicks(distance, 0, sprint) - groundTicks(distance, steadyGroundSpeed(sprint), sprint);
    }

    /**
     * Ticks to hop up one block and end centred on it, already walking at steady speed. Space is hit on entering
     * the move; the body is blocked by the step face until the feet clear y = 1, then glides on with air control.
     */
    public static double ascendTicks(boolean sprint) {
        double a = sprint ? AIR_SPRINT_ACCEL : AIR_ACCEL;
        double x = 0, y = 0, vx = steadyGroundSpeed(sprint) * GROUND_DRAG, vy = JUMP_VELOCITY;
        double face = 0.5 - HALF_WIDTH;
        for (int t = 0; t < MAX_TICKS; t++) {
            vx += t == 0 ? (sprint ? SPRINT_ACCEL : WALK_ACCEL) : a; // the jump tick still has ground control
            double nx = x + vx, ny = y + vy;
            if (ny < 1 && nx > face && x <= face) {
                nx = face; // pushing against the step until the feet clear its top
                vx = 0;
            }
            if (ny <= 1 && vy < 0 && nx >= face) {
                return t + 1 + (nx >= 1 ? 0 : groundTicks(1 - nx, vx, sprint)); // landed on top
            }
            x = nx;
            y = ny;
            vx *= AIR_DRAG;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
        }
        return MAX_TICKS;
    }

    /**
     * Ticks for the walk-off-and-drop-one-block part of a descend after the {@link #walkOffTicks} ground part:
     * falling one block while air control carries the body the last 0.2 to the centre of the lower block.
     */
    public static double descendLandTicks(boolean sprint) {
        double a = sprint ? AIR_SPRINT_ACCEL : AIR_ACCEL;
        double x = 0.5 + HALF_WIDTH, y = 0, vx = steadyGroundSpeed(sprint) * GROUND_DRAG, vy = 0;
        for (int t = 0; t < MAX_TICKS; t++) {
            vx += a;
            double nx = x + vx;
            double ny = y + vy;
            if (ny <= -1) {
                double frac = (y + 1) / (y - ny); // part of this tick spent falling
                double xLand = x + vx * frac;
                if (xLand >= 1) {
                    return t + frac;
                }
                // on the ground, finish the last bit at landing speed
                return t + frac + groundTicks(1 - xLand, vx, sprint);
            }
            x = Math.min(nx, 1); // braking with the keys at the centre
            y = ny;
            vx *= AIR_DRAG;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
        }
        return MAX_TICKS;
    }

    /** Ground ticks from the centre to the point (0.8 blocks) where the body starts to drop off the edge. */
    public static double walkOffTicks(boolean sprint) {
        return groundTicks(0.5 + HALF_WIDTH, steadyGroundSpeed(sprint), sprint);
    }

    /** Ostinato: horizontal kick vanilla adds when a sprinting player jumps (blocks/tick). */
    public static final double SPRINT_JUMP_BOOST = 0.2;

    /**
     * Ostinato: ticks for a flat parkour jump of {@code dist} blocks (centre of the take-off block to the centre of
     * the landing one) entered at steady sprint: run to the edge, sprint-jump (with the 0.2 kick), glide with air
     * sprint control until the feet are back at take-off height, then sprint the rest to the centre. If the glide
     * passes the centre the time is cut where it crosses it (the brake is free, the executor already does it).
     */
    public static double sprintJumpTicks(int dist) {
        double v0 = steadyGroundSpeed(true);
        double t = groundTicks(0.5, v0, true);
        double target = dist - 0.5;
        double x = 0, y = 0, vx = v0 * GROUND_DRAG + SPRINT_ACCEL + SPRINT_JUMP_BOOST, vy = JUMP_VELOCITY;
        for (int k = 0; k < MAX_TICKS; k++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= target) {
                return t + k + (target - x) / vx;
            }
            if (ny <= 0 && vy < 0) {
                double frac = y / (y - ny);
                double xLand = x + vx * frac;
                return t + k + frac + groundTicks(target - xLand, vx, true);
            }
            x = nx;
            y = ny;
            vx = vx * AIR_DRAG + AIR_SPRINT_ACCEL;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
        }
        return MAX_TICKS;
    }
}
