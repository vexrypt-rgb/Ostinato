package baritone.pathing.kinematic;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocation-light copy of vanilla 1.16 player movement (LivingEntity.travel + Entity.move) for
 * look-ahead search. Covers walking, sprinting, jumping, step-up, block collision, ladders/vines and still water via
 * {@link #tickWater}; no lava, currents, sneaking or potion effects.
 */
public final class PlayerSim {

    /** Supplies collision boxes as {minX, minY, minZ, maxX, maxY, maxZ} and ground slipperiness. */
    public interface World {
        void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out);

        float slipperiness(int x, int y, int z);

        /** Slime: a landing bounces back up, walking on it is slowed. */
        default boolean bouncy(int x, int y, int z) {
            return false;
        }

        /** Still or flowing water fills this cell (flow is not simulated). */
        default boolean water(int x, int y, int z) {
            return false;
        }

        /** Ladder, vine or anything else the player can hang on to. */
        default boolean climbable(int x, int y, int z) {
            return false;
        }
    }

    public static final double HALF_WIDTH = 0.3f; // vanilla sizes are floats: the box edge lands exactly on block faces
    public static final double HEIGHT = 1.8f;
    public static final double STEP = 0.6;
    private static final double CLIMB_LIMIT = 0.15, CLIMB_SPEED = 0.2;

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, collidedH, swimming;
    /** Vanilla's jump cooldown: holding jump re-jumps only every 10 ticks. */
    public int jumpTicks;

    private final World world;
    private final List<double[]> boxes = new ArrayList<>();

    public PlayerSim(World world) {
        this.world = world;
    }

    public PlayerSim copyFrom(PlayerSim o) {
        x = o.x; y = o.y; z = o.z; vx = o.vx; vy = o.vy; vz = o.vz;
        onGround = o.onGround; sprinting = o.sprinting; swimming = o.swimming; collidedH = o.collidedH; jumpTicks = o.jumpTicks;
        return this;
    }

    /**
     * One tick with forward held (optionally sprinting/jumping) facing {@code yawDeg}.
     */
    public void tick(float yawDeg, boolean forward, boolean sprint, boolean jump) {
        tick(yawDeg, forward ? 1 : 0, sprint, jump);
    }

    /** As above with the forward key's impulse: 1 forward, 0 none, -1 back. */
    public void tick(float yawDeg, int input, boolean sprint, boolean jump) {
        tick(yawDeg, input, 0, sprint, jump);
    }

    /** With a strafe key too: +1 is A (left), -1 is D (right). Vanilla normalises a diagonal so strafing adds no speed. */
    public void tick(float yawDeg, int input, int strafe, boolean sprint, boolean jump) {
        boolean forward = input > 0;
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        // Vanilla: the key starts a sprint; only losing forward input or a wall stops it.
        sprinting = forward && !collidedH && (sprint || sprinting);
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        if (jumpTicks > 0) jumpTicks--;
        if (!jump) jumpTicks = 0;
        if (jump && onGround && jumpTicks == 0) {
            jumpTicks = 10;
            vy = 0.42;
            if (sprinting) {
                vx -= sin * 0.2;
                vz += cos * 0.2;
            }
        }
        float blockSlip = onGround ? world.slipperiness(floor(x), floor(y - 0.5000001), floor(z)) : 1.0f;
        double slip = onGround ? blockSlip * 0.91 : 0.91;
        double speed;
        if (onGround) {
            double move = sprinting ? 0.13 : 0.1;
            speed = move * (0.21600002 / (blockSlip * blockSlip * blockSlip));
        } else {
            speed = sprinting ? 0.026 : 0.02;
        }
        if (input != 0 || strafe != 0) {
            double fz = 0.98 * input, fx = 0.98 * strafe;
            double len = Math.sqrt(fx * fx + fz * fz);
            if (len > 1) {
                fx /= len;
                fz /= len;
            }
            fx *= speed;
            fz *= speed;
            vx += fx * cos - fz * sin;
            vz += fz * cos + fx * sin;
        }
        if (climbable()) { // LivingEntity.handleOnClimbableSpeed
            vx = Math.max(-CLIMB_LIMIT, Math.min(CLIMB_LIMIT, vx));
            vz = Math.max(-CLIMB_LIMIT, Math.min(CLIMB_LIMIT, vz));
            vy = Math.max(vy, -CLIMB_LIMIT);
        }
        move(vx, vy, vz);
        if ((collidedH || jump) && climbable()) {
            vy = CLIMB_SPEED; // pressing into the wall or holding jump climbs
        }
        vy = (vy - 0.08) * 0.98;
        vx *= slip;
        vz *= slip;
    }

    /** Water at the feet, the body's middle or the eyes: any of them puts vanilla into its fluid branch. */
    public boolean inWater() {
        return world.water(floor(x), floor(y + 0.1), floor(z));
    }

    public boolean eyesInWater() {
        return world.water(floor(x), floor(y + 1.62 - 0.11), floor(z));
    }

    /**
     * One tick in water (vanilla LivingEntity.travelInWater). Sprinting with the eyes under makes the swim pose,
     * where vertical speed chases the look direction; otherwise JUMP rises and SNEAK sinks at 0.04 a tick.
     * Climbing out: walking into a bank with free space above gives the 0.3 hop.
     */
    public void tickWater(float yawDeg, float pitchDeg, boolean forward, boolean sprint, boolean jump, boolean sneak) {
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        sprinting = forward && !collidedH && (sprint || sprinting);
        // vanilla: the swim pose starts with the eyes under, and lasts while sprinting in water, even with the head out
        swimming = sprinting && (swimming ? inWater() : eyesInWater());
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        if (swimming) {
            double look = -Math.sin(Math.toRadians(pitchDeg));
            double k = look < -0.2 ? 0.085 : 0.06;
            if (look <= 0 || jump || world.water(floor(x), floor(y + 1 - 0.1), floor(z))) {
                vy += (look - vy) * k;
            }
        }
        if (forward) {
            double f = 0.98 * 0.02;
            vx += -sin * f;
            vz += cos * f;
        }
        if (jump) vy += 0.04;
        if (sneak) vy -= 0.04;
        double startY = y;
        move(vx, vy, vz);
        double drag = sprinting ? 0.9 : 0.8;
        vx *= drag;
        vz *= drag;
        vy *= 0.8;
        if (!sprinting) {
            vy -= 0.08 / 16.0;
        }
        if (collidedH) {
            double[] r = collide(x, y, z, vx, vy + 0.6 - y + startY, vz);
            if (r[0] == vx && r[1] == vy + 0.6 - y + startY && r[2] == vz && !world.water(floor(x + vx), floor(y + vy + 0.6 - y + startY), floor(z + vz))) {
                vy = 0.3;
            }
        }
    }

    /** Whether the cell the player's feet are in can be hung on to. */
    public boolean climbable() {
        return world.climbable(floor(x), floor(y), floor(z));
    }

    private void move(double dx, double dy, double dz) {
        double ox = dx, oy = dy, oz = dz;
        double[] r = collide(x, y, z, dx, dy, dz);
        boolean groundAfter = oy != r[1] && oy < 0;
        if ((onGround || groundAfter) && (ox != r[0] || oz != r[2])) {
            // step-up: retry lifted by STEP, then settle back down
            double[] up = collide(x, y, z, dx, STEP, dz);
            double[] upOnly = collide(x, y, z, 0, STEP, 0);
            if (upOnly[1] < STEP) {
                double[] alt = collide(x, y, z, dx, upOnly[1], dz);
                if (alt[0] * alt[0] + alt[2] * alt[2] > up[0] * up[0] + up[2] * up[2]) up = alt;
            }
            double[] down = collide(x + up[0], y + up[1], z + up[2], 0, -up[1] + oy, 0);
            up[1] += down[1];
            if (up[0] * up[0] + up[2] * up[2] > r[0] * r[0] + r[2] * r[2]) r = up;
        }
        x += r[0];
        y += r[1];
        z += r[2];
        collidedH = Math.abs(ox - r[0]) >= 1e-5 || Math.abs(oz - r[2]) >= 1e-5; // vanilla approximatelyEquals
        onGround = oy != r[1] && oy < 0;
        if (ox != r[0]) vx = 0;
        if (oz != r[2]) vz = 0;
        if (oy != r[1]) vy = 0;
        // vanilla SlimeBlock: onLanded reverses the fall, onEntityWalk slows slow-moving walkers (never sneaking here)
        boolean slime = world.bouncy(floor(x), floor(y - 0.2), floor(z));
        if (slime && onGround && oy < 0) vy = -oy;
        if (slime && onGround && Math.abs(vy) < 0.1) {
            double k = 0.4 + Math.abs(vy) * 0.2;
            vx *= k;
            vz *= k;
        }
    }

    /** Vanilla axis order: Y, then the larger horizontal axis first. */
    private double[] collide(double px, double py, double pz, double dx, double dy, double dz) {
        double minX = px - HALF_WIDTH, maxX = px + HALF_WIDTH, minY = py, maxY = py + HEIGHT, minZ = pz - HALF_WIDTH, maxZ = pz + HALF_WIDTH;
        boxes.clear();
        world.collect(Math.min(minX, minX + dx) - 1e-7, Math.min(minY, minY + dy) - 1e-7, Math.min(minZ, minZ + dz) - 1e-7,
                Math.max(maxX, maxX + dx) + 1e-7, Math.max(maxY, maxY + dy) + 1e-7, Math.max(maxZ, maxZ + dz) + 1e-7, boxes);
        dy = clip(1, dy, minX, minY, minZ, maxX, maxY, maxZ);
        minY += dy; maxY += dy;
        if (Math.abs(dx) < Math.abs(dz)) {
            dz = clip(2, dz, minX, minY, minZ, maxX, maxY, maxZ);
            minZ += dz; maxZ += dz;
            dx = clip(0, dx, minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            dx = clip(0, dx, minX, minY, minZ, maxX, maxY, maxZ);
            minX += dx; maxX += dx;
            dz = clip(2, dz, minX, minY, minZ, maxX, maxY, maxZ);
        }
        return new double[]{dx, dy, dz};
    }

    private double clip(int axis, double d, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (d == 0) return 0;
        double[] me = {minX, minY, minZ, maxX, maxY, maxZ};
        int a = axis, b = (axis + 1) % 3, c = (axis + 2) % 3;
        for (double[] bx : boxes) {
            if (bx[3 + b] <= me[b] + 1e-7 || bx[b] >= me[3 + b] - 1e-7) continue;
            if (bx[3 + c] <= me[c] + 1e-7 || bx[c] >= me[3 + c] - 1e-7) continue;
            if (d > 0 && bx[a] >= me[3 + a] - 1e-7) {
                d = Math.min(d, bx[a] - me[3 + a]);
            } else if (d < 0 && bx[3 + a] <= me[a] + 1e-7) {
                d = Math.max(d, bx[3 + a] - me[a]);
            }
        }
        return d;
    }

    public static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
