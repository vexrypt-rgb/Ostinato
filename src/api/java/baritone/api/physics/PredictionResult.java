/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.api.physics;

import java.util.EnumSet;
import java.util.List;

/**
 * Ostinato: the answer to one {@link PredictionRequest}. Scalar fields are immutable; arrays belong to the delivered answer. Every answer says how far it got
 * ({@link #horizonTicks}), what was worked out ({@link #fidelity}), how old the world it ran on was
 * ({@link #snapshotAgeTicks}), which {@link Assumption assumptions} it rests on, and a {@link Confidence} derived
 * from those by a fixed rule (no made-up numbers).
 */
public final class PredictionResult {

    public enum Status {
        /** Ran the whole horizon, or to the event it was asked to stop at. */
        OK,
        /** Stopped early ({@link #failure} says why); what it has is valid up to {@link #horizonTicks}. */
        PARTIAL,
        FAILED,
        CANCELLED,
        /** The deadline passed before it ran. */
        EXPIRED,
        /** Not queued: the queue was full of more urgent work. */
        REJECTED
    }

    /**
     * How far to trust the answer, from the assumptions it hit: EXACT when every mechanic on its path is modelled and
     * nothing random is involved (the rest is client/server desync and the stated input); BOUNDED when the only
     * uncertainty is vanilla randomness, reported as ranges; APPROXIMATE when an assumption that can change the
     * outcome was hit; UNKNOWN when there is no answer.
     */
    public enum Confidence { EXACT, BOUNDED, APPROXIMATE, UNKNOWN }

    public enum Assumption {
        /** Reached cells outside the captured region; stopped there. */
        OUTSIDE_SNAPSHOT(true),
        /** The region includes chunks the client has not loaded (read as empty). */
        UNLOADED_CHUNK(true),
        /** Touched a block whose collision depends on who touches it or on a block entity (scaffolding, powder snow, shulker box, moving piston); read without that context. */
        CONTEXT_SHAPE(true),
        /** Started inside a collision box (vanilla collides against voxel boundaries then; the engine ignores the box). */
        PENETRATING(true),
        /** Collidable entities (boats, shulkers) near the path were held where they were at capture. */
        ENTITY_COLLIDERS_FROZEN(true),
        /** Was in flowing fluid; the current's push is not modelled. */
        FLUID_CURRENT(true),
        /** Touched a block whose effect on motion is not modelled (portals, end gateways, big dripleaf). */
        UNMODELLED_BLOCK_EFFECT(true),
        /** A cobweb-like block was in the swept box but not under the end box; treated as touched. */
        INSIDE_EFFECT_APPROX(true),
        /** The world changed in a copied section after the copy was taken: the answer is for the world as it was. */
        STALE_SNAPSHOT(true),
        /** The subject is not the local player: position is the client's interpolated view, velocity from deltas. */
        CLIENT_STATE(true),
        /** A sneaking subject on the ground: vanilla's edge back-off is not modelled. */
        SNEAK_EDGE(true),
        /** Projectile or explosion targets were met after the capture tick, held where they were (they may move or act meanwhile). */
        TARGETS_FROZEN(true),
        /** A living subject's pose (box size) and held input do not change over the horizon. */
        INPUT_HELD(false),
        /** Chain-lit TNT fuses (10..29 ticks) are reported as a range. */
        RANDOM_FUSE(false),
        /** Explosion block breaking is random per ray; reported as certain/possible sets. */
        RANDOM_BLOCK_DAMAGE(false),
        /** Work was cut down under load (path or bounds dropped); the physics is not. */
        DEGRADED(false),
        /** Enchantment protection assumes vanilla enchantment data. */
        VANILLA_ENCHANTMENTS(false),
        /**
         * A primed TNT's blast power was not readable from the client (26.3 syncs its fuse and block state, not its
         * power), so vanilla's 4 was used. Every vanilla-primed TNT is 4, so this does not by itself change the
         * outcome; it is reported so a consumer can tell an observed power from an assumed one.
         */
        TNT_POWER_ASSUMED(false);

        /** Whether hitting it can change the outcome (makes the answer APPROXIMATE). */
        public final boolean changesOutcome;

        Assumption(boolean changesOutcome) {
            this.changesOutcome = changesOutcome;
        }

        public long bit() {
            return 1L << ordinal();
        }
    }

    public enum Failure {
        NONE, NO_WORLD, NOT_MAIN_THREAD, UNSUPPORTED, OUT_OF_SNAPSHOT, DEADLINE, QUEUE_FULL, CANCELLED, INTERNAL
    }

    /** The first landing of a falling subject. */
    public static final class Landing {
        public final int tick;
        public final double x, y, z;
        /** The block landed on (vanilla's getOnPosLegacy). */
        public final int blockX, blockY, blockZ;
        public final double fallDistance;
        /** Health lost (after Resistance and Feather Falling; fall damage ignores armour), 0 when safe. */
        public final float damage;
        public final boolean lethal;
        /** Landed in water, on a bouncing block, or on a block that cancels or cuts fall damage. */
        public final boolean bounced, inFluid;
        /** First tick the landing block's top was within {@link PredictionRequest#reach} of the eye; -1 never. */
        public final int reachTick;

        public Landing(int tick, double x, double y, double z, int blockX, int blockY, int blockZ, double fallDistance,
                       float damage, boolean lethal, boolean bounced, boolean inFluid, int reachTick) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.fallDistance = fallDistance;
            this.damage = damage;
            this.lethal = lethal;
            this.bounced = bounced;
            this.inFluid = inFluid;
            this.reachTick = reachTick;
        }

        /** Ticks of control left before impact (0: it lands this tick). */
        public int ticksToImpact() {
            return tick;
        }
    }

    /** What a projectile hit first: a block face, or one of the request's targets. */
    public static final class Hit {
        public final int tick;
        public final double x, y, z;
        /** -1 for a block. */
        public final int targetIndex;
        public final int blockX, blockY, blockZ;
        /** Face index in Minecraft's Direction order (down, up, north, south, west, east); -1 for a target. */
        public final int face;
        /** Speed at impact (blocks/tick). */
        public final double speed;

        public Hit(int tick, double x, double y, double z, int targetIndex, int blockX, int blockY, int blockZ, int face, double speed) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.targetIndex = targetIndex;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.face = face;
            this.speed = speed;
        }
    }

    /** One explosion's effect on one target. */
    public static final class Exposure {
        /** ServerExplosion.getSeenPercent, 0..1. */
        public final float exposure;
        /** Health lost after difficulty, armour, Resistance and Blast Protection. */
        public final float damage;
        public final boolean lethal;
        /** Velocity added (blocks/tick). */
        public final double kx, ky, kz;

        public Exposure(float exposure, float damage, boolean lethal, double kx, double ky, double kz) {
            this.exposure = exposure;
            this.damage = damage;
            this.lethal = lethal;
            this.kx = kx;
            this.ky = ky;
            this.kz = kz;
        }
    }

    /** One explosion: where, when, and what it does. */
    public static final class Explosion {
        public final int tick;
        public final double x, y, z;
        public final float power;
        /** One per request target, same order. */
        public final List<Exposure> targets;
        /** Blocks broken whatever the rays roll, and blocks some roll can break (a superset); packed like BlockPos.asLong. Null when not worked out. */
        public final long[] certainBlocks, possibleBlocks;
        /** Primed TNT blocks within reach of the explosion (they ignite with a 10..29 tick fuse); packed. */
        public final long[] chainTnt;

        public Explosion(int tick, double x, double y, double z, float power, List<Exposure> targets,
                         long[] certainBlocks, long[] possibleBlocks, long[] chainTnt) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.power = power;
            this.targets = List.copyOf(targets);
            this.certainBlocks = certainBlocks == null ? null : certainBlocks.clone();
            this.possibleBlocks = possibleBlocks == null ? null : possibleBlocks.clone();
            this.chainTnt = chainTnt.clone();
        }
    }

    public final Status status;
    public final Confidence confidence;
    public final long assumptions;
    public final Failure failure;
    public final PredictionRequest.Fidelity fidelity;
    /** Ticks actually simulated. */
    public final int horizonTicks;
    /** Game ticks between the world snapshot and the request (the oldest section used). */
    public final int snapshotAgeTicks;
    public final long computeNanos, queueNanos;

    /** State after the last simulated tick. */
    public final double x, y, z, vx, vy, vz;
    public final boolean onGround;
    /** Positions (x, y, z per entry) every {@link #pathStride} ticks starting at tick 0; null when not recorded. */
    public final float[] path;
    public final int pathStride;
    /** First tick with a horizontal or vertical collision; -1 none. */
    public final int firstCollisionTick;
    public final Landing landing;
    public final Hit hit;
    public final Explosion explosion;

    private PredictionResult(Status status, long assumptions, Failure failure, PredictionRequest.Fidelity fidelity,
                             int horizonTicks, int snapshotAgeTicks, long computeNanos, long queueNanos,
                             double x, double y, double z, double vx, double vy, double vz, boolean onGround,
                             float[] path, int pathStride, int firstCollisionTick, Landing landing, Hit hit, Explosion explosion) {
        this.status = status;
        this.assumptions = assumptions;
        this.failure = failure;
        this.fidelity = fidelity;
        this.horizonTicks = horizonTicks;
        this.snapshotAgeTicks = snapshotAgeTicks;
        this.computeNanos = computeNanos;
        this.queueNanos = queueNanos;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.onGround = onGround;
        this.path = path == null ? null : path.clone();
        this.pathStride = pathStride;
        this.firstCollisionTick = firstCollisionTick;
        this.landing = landing;
        this.hit = hit;
        this.explosion = explosion == null ? null : new Explosion(explosion.tick, explosion.x, explosion.y, explosion.z,
                explosion.power, explosion.targets, explosion.certainBlocks, explosion.possibleBlocks, explosion.chainTnt);
        this.confidence = confidenceOf(status, assumptions);
    }

    /** The fixed rule behind {@link #confidence}. */
    public static Confidence confidenceOf(Status status, long assumptions) {
        if (status != Status.OK && status != Status.PARTIAL) return Confidence.UNKNOWN;
        boolean random = false;
        for (Assumption a : Assumption.values()) {
            if ((assumptions & a.bit()) == 0) continue;
            if (a.changesOutcome) return Confidence.APPROXIMATE;
            if (a == Assumption.RANDOM_FUSE || a == Assumption.RANDOM_BLOCK_DAMAGE) random = true;
        }
        return random ? Confidence.BOUNDED : Confidence.EXACT;
    }

    public boolean ok() {
        return status == Status.OK || status == Status.PARTIAL;
    }

    public boolean has(Assumption a) {
        return (assumptions & a.bit()) != 0;
    }

    public EnumSet<Assumption> assumptionSet() {
        EnumSet<Assumption> s = EnumSet.noneOf(Assumption.class);
        for (Assumption a : Assumption.values()) if (has(a)) s.add(a);
        return s;
    }

    /** A result with no answer. */
    public static PredictionResult failed(Status status, Failure failure, PredictionRequest.Fidelity fidelity, long queueNanos) {
        return new PredictionResult(status, 0, failure, fidelity, 0, 0, 0, queueNanos, 0, 0, 0, 0, 0, 0, false,
                null, 0, -1, null, null, null);
    }

    /** The same answer with the time it waited and the snapshot age filled in (results are built before both are known). */
    public PredictionResult withTiming(long queueNanos, int snapshotAgeTicks, long extraAssumptions) {
        return new PredictionResult(status, assumptions | extraAssumptions, failure, fidelity, horizonTicks, snapshotAgeTicks,
                computeNanos, queueNanos, x, y, z, vx, vy, vz, onGround, path, pathStride, firstCollisionTick, landing, hit, explosion);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(status).append('/').append(confidence).append(' ').append(fidelity).append(" h=").append(horizonTicks)
                .append(String.format(" end(%.3f %.3f %.3f)", x, y, z));
        if (failure != Failure.NONE) sb.append(" failure=").append(failure);
        if (landing != null) sb.append(String.format(" land@%d fall=%.2f dmg=%.1f%s", landing.tick, landing.fallDistance, landing.damage, landing.lethal ? " LETHAL" : ""));
        if (hit != null) sb.append(String.format(" hit@%d(%.2f %.2f %.2f)%s", hit.tick, hit.x, hit.y, hit.z, hit.targetIndex >= 0 ? " target" + hit.targetIndex : ""));
        if (explosion != null) sb.append(String.format(" boom@%d(%.2f %.2f %.2f)", explosion.tick, explosion.x, explosion.y, explosion.z));
        if (assumptions != 0) sb.append(' ').append(assumptionSet());
        return sb.toString();
    }

    /** Builds results; for the engine. */
    public static final class Builder {
        private Status status = Status.OK;
        private long assumptions;
        private Failure failure = Failure.NONE;
        private final PredictionRequest.Fidelity fidelity;
        private int horizonTicks, snapshotAgeTicks;
        private long computeNanos;
        private double x, y, z, vx, vy, vz;
        private boolean onGround;
        private float[] path;
        private int pathStride;
        private int firstCollisionTick = -1;
        private Landing landing;
        private Hit hit;
        private Explosion explosion;

        public Builder(PredictionRequest.Fidelity fidelity) {
            this.fidelity = fidelity;
        }

        public Builder partial(Failure why) { this.status = Status.PARTIAL; this.failure = why; return this; }
        public Builder fail(Failure why) { this.status = Status.FAILED; this.failure = why; return this; }
        public Builder assume(long bits) { this.assumptions |= bits; return this; }
        public Builder assume(Assumption a) { this.assumptions |= a.bit(); return this; }
        public Builder horizon(int ticks) { this.horizonTicks = ticks; return this; }
        public Builder computeNanos(long n) { this.computeNanos = n; return this; }
        public Builder end(double x, double y, double z, double vx, double vy, double vz, boolean onGround) {
            this.x = x; this.y = y; this.z = z; this.vx = vx; this.vy = vy; this.vz = vz; this.onGround = onGround;
            return this;
        }
        public Builder path(float[] path, int stride) { this.path = path; this.pathStride = stride; return this; }
        public Builder firstCollision(int tick) { if (firstCollisionTick < 0) firstCollisionTick = tick; return this; }
        public Builder landing(Landing l) { this.landing = l; return this; }
        public Builder hit(Hit h) { this.hit = h; return this; }
        public Builder explosion(Explosion e) { this.explosion = e; return this; }

        public PredictionResult build() {
            return new PredictionResult(status, assumptions, failure, fidelity, horizonTicks, snapshotAgeTicks, computeNanos, 0,
                    x, y, z, vx, vy, vz, onGround, path, pathStride, firstCollisionTick, landing, hit, explosion);
        }
    }
}
