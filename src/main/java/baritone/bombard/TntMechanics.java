/*
 * Ostinato: new file (Bombard TNT combat). Not part of upstream Baritone.
 */
package baritone.bombard;

/**
 * The TNT rules Bombard plans with, each read from the decompiled 26.3 server (see SCHERZO_BOMBARD_HANDOFF.md §3).
 * Game-free. The engine ({@code IPredictivePhysics}) simulates TNT in terrain; this holds what the planner needs
 * without a world: the free-air tick, the impulses each primitive gives, and which primitives a version supports.
 */
public final class TntMechanics {

    // PrimedTnt.tick: gravity, move, drag, ground bounce, fuse - 1, discard (client) / explode (server) at fuse <= 0
    public static final double GRAVITY = 0.04, DRAG = (double) 0.98f, GROUND_XZ = 0.7, GROUND_Y = -0.5;
    public static final int DEFAULT_FUSE = 80;
    /** Vanilla's only TNT power; 26.3 does not sync it, the explode packet's radius reports it. */
    public static final float POWER = 4;
    public static final double SIZE = 0.98;
    /** PrimedTnt.explode: at {@code getY(0.0625)}, a sixteenth of the box up. */
    public static final double BLAST_Y = SIZE * 0.0625;
    /** ServerLevel.explode sends the packet to players within 64 blocks. */
    public static final double PACKET_RANGE = 64;
    /** TntBlock.prime from a block: centre of the block, a hop up and a random sideways nudge of this size. */
    public static final double SPAWN_VY = 0.2, SPAWN_VH = 0.02;
    /** TntBlock.wasExploded: a TNT block caught in a blast primes with {@code nextInt(20) + 10}. */
    public static final int CHAIN_FUSE_MIN = 10, CHAIN_FUSE_MAX = 29;

    // FishingHook.pullEntity: tnt.v += 0.1 (owner - hook), the hook held at tnt.getY(0.8); 5 durability a pull
    public static final double ROD_PULL = 0.1, HOOK_Y = SIZE * 0.8;
    public static final int ROD_PULL_DURABILITY = 5;
    /** FishingHook.shouldStopFishing: the hook goes when its owner is further than this. */
    public static final double ROD_RANGE = 32;

    // Player.stabAttack -> causeExtraKnockback: push(-sin(yaw) k, 0.1, cos(yaw) k), k = 0.4, then k = knockback / 2
    public static final double SPEAR_PUSH = 0.4, PUSH_VY = 0.1;
    /** AttackRange of a spear: the server's jab ray. */
    public static final double SPEAR_MIN = 2.0, SPEAR_MAX = 4.5, SPEAR_MARGIN = 0.125;
    /** Each push slows the attacker: velocity x (0.6, 1, 0.6), sprint off. */
    public static final double ATTACKER_SLOW = 0.6;

    private TntMechanics() {}

    /** What a game version supports. Bombard does nothing with a primitive marked off. */
    public record Support(String version, boolean rod, boolean spear, boolean chain, String reason) {
        public boolean any() {
            return rod || spear || chain;
        }
    }

    /**
     * Only 26.3 is verified (unobfuscated server source read rule by rule). Anything else is off until its source is
     * read: spears do not exist before 1.21.11, and the hook and blast rules have changed between versions.
     */
    public static Support support(String version) {
        if ("26.3".equals(version)) return new Support(version, true, true, true, "verified against 26.3 server source");
        return new Support(version, false, false, false, "mechanics not verified for " + version);
    }

    /** One free-air tick (no block in the way): the state after it, {x, y, z, vx, vy, vz}. */
    public static double[] step(double[] s) {
        return step(s, Double.NEGATIVE_INFINITY);
    }

    /**
     * One tick over a flat floor at {@code floorY} and nothing else: a fall that reaches it stops on it (the
     * collision zeroes vy), and a body on the floor after the move is damped by the ground bounce after the drag.
     */
    public static double[] step(double[] s, double floorY) {
        double vy = s[4] - GRAVITY;
        double requestedY = vy;
        double x = s[0] + s[3], y = s[1] + vy, z = s[2] + s[5];
        boolean ground = false;
        if (vy < 0 && y <= floorY) {
            y = floorY;
            vy = 0;
            ground = true;
        }
        double dy = y - s[1];
        double moved = s[3] * s[3] + dy * dy + s[5] * s[5];
        double requested = s[3] * s[3] + requestedY * requestedY + s[5] * s[5];
        // Entity.move suppresses a tiny displacement when a larger requested movement was clipped by a floor.
        if (!(moved > 1.0E-7 || requested - moved < 1.0E-7)) {
            x = s[0]; y = s[1]; z = s[2];
        }
        double vx = s[3] * DRAG, vz = s[5] * DRAG;
        vy *= DRAG;
        if (ground) {
            vx *= GROUND_XZ;
            vy *= GROUND_Y;
            vz *= GROUND_XZ;
        }
        return new double[]{x, y, z, vx, vy, vz};
    }

    /** Velocity a rod reel adds to a hooked TNT at {@code tnt} feet, pulled toward the owner's feet. */
    public static double[] rodPull(double tx, double ty, double tz, double ox, double oy, double oz) {
        return new double[]{ROD_PULL * (ox - tx), ROD_PULL * (oy - (ty + HOOK_Y)), ROD_PULL * (oz - tz)};
    }

    /** Velocity a spear jab adds to a TNT: the base push and, with knockback, a second one along the same yaw. */
    public static double[] spearPush(float yawDeg, double knockback) {
        double r = Math.toRadians(yawDeg);
        double sx = -Math.sin(r), sz = Math.cos(r);
        double k = SPEAR_PUSH, vy = PUSH_VY;
        double extra = knockback / 2;
        if (extra > 0) {
            k += extra;
            vy += PUSH_VY;
        }
        return new double[]{sx * k, vy, sz * k};
    }

    /** Ticks until a TNT with this fuse explodes: each tick takes one off and it goes at 0 or below, so never under 1. */
    public static int ticksLeft(int fuse) {
        return Math.max(1, fuse);
    }

    /**
     * A TNT primed {@code since} ticks ago with this fuse left could have been lit by a blast (10..29 at priming).
     * The fuse alone proves nothing: an 80-fuse TNT first seen late shows a short fuse too.
     */
    public static boolean chainLit(int fuse, int since) {
        int atPriming = fuse + since;
        return since >= 0 && atPriming >= CHAIN_FUSE_MIN && atPriming <= CHAIN_FUSE_MAX;
    }

    /** Distance at which a blast of this power still damages: vanilla's radius is twice the power. */
    public static double damageRadius(float power) {
        return power * 2.0;
    }
}
