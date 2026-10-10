package baritone.pathing.kinematic;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocation-light copy of vanilla 1.16 player movement (LivingEntity.travel + Entity.move) for
 * look-ahead search. Covers walking, sprinting, jumping, step-up, block collision, slime, and the blocks that
 * slow a walk or a jump (soul sand, honey). Of the potion effects it follows Speed, Slowness and Jump Boost
 * ({@link #speedScale}, {@link #jumpBoost}); nothing that changes gravity. No fluids, ladders or sneaking.
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

        /** What the block does to the pace of a walk over or through it: 0.4 for soul sand and honey. */
        default float speedFactor(int x, int y, int z) {
            return 1;
        }

        /** What the block does to a jump off it: 0.5 for honey. */
        default float jumpFactor(int x, int y, int z) {
            return 1;
        }

        /** Honey: a fall down its side is slowed to a slide. */
        default boolean sticky(int x, int y, int z) {
            return false;
        }
    }

    public static final double HALF_WIDTH = 0.3f; // vanilla sizes are floats: the box edge lands exactly on block faces
    public static final double HEIGHT = 1.8f;
    public static final double STEP = 0.6;
    /** Honey is 1/16 short of a full block on every side: how far from its middle the slide starts, and its top. */
    private static final double HONEY_REACH = 0.4375 + HALF_WIDTH, HONEY_TOP = 0.9375;

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, collidedH;
    /** Vanilla's jump cooldown: holding jump re-jumps only every 10 ticks. */
    public int jumpTicks;
    /** Ground speed against the plain walk (Speed, Slowness), and what Jump Boost adds to the jump. */
    public double speedScale = 1, jumpBoost;

    private final World world;
    private final List<double[]> boxes = new ArrayList<>();

    public PlayerSim(World world) {
        this.world = world;
    }

    public PlayerSim copyFrom(PlayerSim o) {
        x = o.x; y = o.y; z = o.z; vx = o.vx; vy = o.vy; vz = o.vz;
        onGround = o.onGround; sprinting = o.sprinting; collidedH = o.collidedH; jumpTicks = o.jumpTicks;
        speedScale = o.speedScale; jumpBoost = o.jumpBoost;
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
            vy = 0.42 * jumpFactor() + jumpBoost;
            if (sprinting) {
                vx -= sin * 0.2;
                vz += cos * 0.2;
            }
        }
        float blockSlip = onGround ? world.slipperiness(floor(x), floor(y - 0.5000001), floor(z)) : 1.0f;
        double slip = onGround ? blockSlip * 0.91 : 0.91;
        double speed;
        if (onGround) {
            double move = (sprinting ? 0.13 : 0.1) * speedScale;
            speed = move * (0.21600002 / (blockSlip * blockSlip * blockSlip));
        } else {
            speed = sprinting ? 0.026 : 0.02;
        }
        if (input != 0) {
            double f = 0.98 * speed * input;
            vx += -sin * f;
            vz += cos * f;
        }
        move(vx, vy, vz);
        vy = (vy - 0.08) * 0.98;
        vx *= slip;
        vz *= slip;
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
        honeySlide();
        // vanilla Entity.move ends by scaling the horizontal speed by the block's factor
        float drag = speedFactor();
        if (drag != 1) {
            vx *= drag;
            vz *= drag;
        }
    }

    /** Vanilla Entity.getSpeedFactor: the block the feet are in, else the one half a block below them. */
    private float speedFactor() {
        int cx = floor(x), cy = floor(y), cz = floor(z);
        float f = world.speedFactor(cx, cy, cz);
        int below = floor(y - 0.5000001);
        return f != 1 || below == cy ? f : world.speedFactor(cx, below, cz);
    }

    /** Vanilla Entity.getJumpFactor: the same two blocks, feet first. */
    private float jumpFactor() {
        int cx = floor(x), cy = floor(y), cz = floor(z);
        float f = world.jumpFactor(cx, cy, cz);
        int below = floor(y - 0.5000001);
        return f != 1 || below == cy ? f : world.jumpFactor(cx, below, cz);
    }

    /**
     * Vanilla HoneyBlock.onEntityCollision: falling faster than gravity alone, below the block's top and past its
     * side, the fall is held to a slide and the horizontal speed shrinks with it.
     */
    private void honeySlide() {
        if (onGround || vy >= -0.08) return;
        for (int cx = floor(x - HALF_WIDTH + 1e-5); cx <= floor(x + HALF_WIDTH - 1e-5); cx++) {
            for (int cz = floor(z - HALF_WIDTH + 1e-5); cz <= floor(z + HALF_WIDTH - 1e-5); cz++) {
                if (Math.abs(cx + 0.5 - x) + 1e-7 <= HONEY_REACH && Math.abs(cz + 0.5 - z) + 1e-7 <= HONEY_REACH) continue;
                for (int cy = floor(y + 1e-5); cy <= floor(y + HEIGHT - 1e-5); cy++) {
                    if (y > cy + HONEY_TOP - 1e-7 || !world.sticky(cx, cy, cz)) continue;
                    if (vy < -0.13) {
                        double k = -0.05 / vy;
                        vx *= k;
                        vz *= k;
                    }
                    vy = -0.05;
                    return;
                }
            }
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
