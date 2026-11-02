/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.api.physics;

/**
 * Ostinato: one moving thing at one tick, without Minecraft types: what a prediction starts from. Read one from a live
 * entity with {@link IPredictivePhysics#capture}, or build one (tests, hypotheticals) with {@link #builder}. The
 * defaults are vanilla 26.3's for a player with no effects or equipment.
 */
public final class BodyState {

    /** Which vanilla tick a body runs; each names the class whose tick the engine copies. */
    public enum Type {
        /** Player (LocalPlayer / ServerPlayer input and travel). */
        PLAYER,
        /** Any other LivingEntity, driven by the input it is given (no AI). */
        LIVING,
        /** PrimedTnt. */
        PRIMED_TNT,
        /** FallingBlockEntity (server side: landing and placing are reported, not drop/break rules). */
        FALLING_BLOCK,
        /** AbstractArrow (arrow, spectral arrow). */
        ARROW,
        /** ThrownTrident (AbstractArrow with water inertia 0.99). */
        TRIDENT,
        /** ThrowableProjectile: snowball, egg, ender pearl (gravity 0.03), potions (0.05), experience bottle (0.07). */
        THROWABLE,
        /** AbstractHurtingProjectile: fireballs, wither skulls, wind charges (no gravity, acceleration along motion). */
        HURTING_PROJECTILE
    }

    public static final int SPRINTING = 1, SNEAKING = 2, SWIMMING = 4, FALL_FLYING = 8, FLYING = 16, NO_GRAVITY = 32,
            FALL_IMMUNE = 64, MAY_FLY = 128, SLOW_FALLING = 256, DOLPHINS_GRACE = 512, IN_GROUND = 1024, WEAVING = 2048;
    /** Read from an entity other than the local player: the client's interpolated position and synced velocity. */
    public static final int CLIENT_VIEW = 4096;
    /**
     * {@link #explosionPower} is vanilla's default because the live value was not readable, not because it was read.
     * 26.3's {@code PrimedTnt.explosionPower} is written only by NBT loading and read only by its own {@code explode()}:
     * it has no synched-data entry and no getter, so a client cannot observe an override (a command or a data pack can
     * set one). {@link IPredictivePhysics#capture} sets this for a primed TNT; a caller that knows the power does not.
     */
    public static final int POWER_ASSUMED = 8192;

    public final Type type;
    /** Entity id when read from a live entity, else -1; part of the cache key only. */
    public final int entityId;
    public final double x, y, z, vx, vy, vz;
    public final float width, height, eyeHeight, yaw, pitch;
    public final boolean onGround;
    public final double fallDistance;
    public final int flags;

    /** Per-tick gravity before effects: the GRAVITY attribute for living bodies, the type's constant otherwise. */
    public final double gravity;
    /** MOVEMENT_SPEED attribute value (sprinting included), JUMP_STRENGTH, STEP_HEIGHT. */
    public final float speed, jumpStrength, stepHeight;
    public final float safeFallDistance, fallDamageMultiplier, knockbackResistance, explosionKnockbackResistance;
    public final float bounciness, airDragModifier, frictionModifier, movementEfficiency, waterMovementEfficiency, sneakingSpeed;
    /** Effect amplifier + 1, 0 for none. */
    public final int jumpBoost, levitation, resistance;
    /** Armour points, toughness, and enchantment protection points against fall and explosion damage. */
    public final float armor, toughness, fallProtection, blastProtection;
    public final float health, absorption;
    /** PrimedTnt fuse ticks and explosion power; projectile inertia overrides are the type's. */
    public final int fuse;
    public final float explosionPower;
    /** Projectiles: velocity kept per tick in air and in water (arrow 0.99/0.6, trident 0.99/0.99, thrown 0.99/0.8, fireball 0.95/0.8, wind charge 1/1). */
    public final float drag, waterDrag;
    /** Hurting projectiles: acceleration along their motion per tick (AbstractHurtingProjectile.accelerationPower). */
    public final double acceleration;
    /** Ticks lived (Entity.tickCount): a projectile's entity-hit margin grows with it. */
    public final int age;

    private BodyState(Builder b) {
        type = b.type;
        entityId = b.entityId;
        x = b.x; y = b.y; z = b.z;
        vx = b.vx; vy = b.vy; vz = b.vz;
        width = b.width; height = b.height; eyeHeight = b.eyeHeight;
        yaw = b.yaw; pitch = b.pitch;
        onGround = b.onGround;
        fallDistance = b.fallDistance;
        flags = b.flags;
        gravity = b.gravity;
        speed = b.speed; jumpStrength = b.jumpStrength; stepHeight = b.stepHeight;
        safeFallDistance = b.safeFallDistance; fallDamageMultiplier = b.fallDamageMultiplier;
        knockbackResistance = b.knockbackResistance; explosionKnockbackResistance = b.explosionKnockbackResistance;
        bounciness = b.bounciness; airDragModifier = b.airDragModifier; frictionModifier = b.frictionModifier;
        movementEfficiency = b.movementEfficiency; waterMovementEfficiency = b.waterMovementEfficiency;
        sneakingSpeed = b.sneakingSpeed;
        jumpBoost = b.jumpBoost; levitation = b.levitation; resistance = b.resistance;
        armor = b.armor; toughness = b.toughness; fallProtection = b.fallProtection; blastProtection = b.blastProtection;
        health = b.health; absorption = b.absorption;
        fuse = b.fuse; explosionPower = b.explosionPower;
        drag = b.drag; waterDrag = b.waterDrag; acceleration = b.acceleration; age = b.age;
    }

    /** Full simulation identity; hashes alone never establish equivalence. */
    public boolean sameAs(BodyState other) {
        return other != null &&
                type == other.type
                && entityId == other.entityId
                && Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0
                && Double.compare(vx, other.vx) == 0
                && Double.compare(vy, other.vy) == 0
                && Double.compare(vz, other.vz) == 0
                && Float.compare(width, other.width) == 0
                && Float.compare(height, other.height) == 0
                && Float.compare(eyeHeight, other.eyeHeight) == 0
                && Float.compare(yaw, other.yaw) == 0
                && Float.compare(pitch, other.pitch) == 0
                && onGround == other.onGround
                && Double.compare(fallDistance, other.fallDistance) == 0
                && flags == other.flags
                && Double.compare(gravity, other.gravity) == 0
                && Float.compare(speed, other.speed) == 0
                && Float.compare(jumpStrength, other.jumpStrength) == 0
                && Float.compare(stepHeight, other.stepHeight) == 0
                && Float.compare(safeFallDistance, other.safeFallDistance) == 0
                && Float.compare(fallDamageMultiplier, other.fallDamageMultiplier) == 0
                && Float.compare(knockbackResistance, other.knockbackResistance) == 0
                && Float.compare(explosionKnockbackResistance, other.explosionKnockbackResistance) == 0
                && Float.compare(bounciness, other.bounciness) == 0
                && Float.compare(airDragModifier, other.airDragModifier) == 0
                && Float.compare(frictionModifier, other.frictionModifier) == 0
                && Float.compare(movementEfficiency, other.movementEfficiency) == 0
                && Float.compare(waterMovementEfficiency, other.waterMovementEfficiency) == 0
                && Float.compare(sneakingSpeed, other.sneakingSpeed) == 0
                && jumpBoost == other.jumpBoost
                && levitation == other.levitation
                && resistance == other.resistance
                && Float.compare(armor, other.armor) == 0
                && Float.compare(toughness, other.toughness) == 0
                && Float.compare(fallProtection, other.fallProtection) == 0
                && Float.compare(blastProtection, other.blastProtection) == 0
                && Float.compare(health, other.health) == 0
                && Float.compare(absorption, other.absorption) == 0
                && fuse == other.fuse
                && Float.compare(explosionPower, other.explosionPower) == 0
                && Float.compare(drag, other.drag) == 0
                && Float.compare(waterDrag, other.waterDrag) == 0
                && Double.compare(acceleration, other.acceleration) == 0
                && age == other.age;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public boolean isLiving() {
        return type == Type.PLAYER || type == Type.LIVING;
    }

    public boolean isProjectile() {
        return type == Type.ARROW || type == Type.TRIDENT || type == Type.THROWABLE || type == Type.HURTING_PROJECTILE;
    }

    public Builder toBuilder() {
        Builder b = new Builder(type);
        b.entityId = entityId;
        b.x = x; b.y = y; b.z = z;
        b.vx = vx; b.vy = vy; b.vz = vz;
        b.width = width; b.height = height; b.eyeHeight = eyeHeight;
        b.yaw = yaw; b.pitch = pitch;
        b.onGround = onGround;
        b.fallDistance = fallDistance;
        b.flags = flags;
        b.gravity = gravity;
        b.speed = speed; b.jumpStrength = jumpStrength; b.stepHeight = stepHeight;
        b.safeFallDistance = safeFallDistance; b.fallDamageMultiplier = fallDamageMultiplier;
        b.knockbackResistance = knockbackResistance; b.explosionKnockbackResistance = explosionKnockbackResistance;
        b.bounciness = bounciness; b.airDragModifier = airDragModifier; b.frictionModifier = frictionModifier;
        b.movementEfficiency = movementEfficiency; b.waterMovementEfficiency = waterMovementEfficiency;
        b.sneakingSpeed = sneakingSpeed;
        b.jumpBoost = jumpBoost; b.levitation = levitation; b.resistance = resistance;
        b.armor = armor; b.toughness = toughness; b.fallProtection = fallProtection; b.blastProtection = blastProtection;
        b.health = health; b.absorption = absorption;
        b.fuse = fuse; b.explosionPower = explosionPower;
        b.drag = drag; b.waterDrag = waterDrag; b.acceleration = acceleration; b.age = age;
        return b;
    }

    /** A body of {@code type} with that type's vanilla size and gravity. */
    public static Builder builder(Type type) {
        return new Builder(type);
    }

    @Override
    public String toString() {
        return String.format("%s#%d(%.3f %.3f %.3f | %.4f %.4f %.4f%s)", type, entityId, x, y, z, vx, vy, vz, onGround ? " ground" : "");
    }

    public static final class Builder {
        private final Type type;
        private int entityId = -1;
        private double x, y, z, vx, vy, vz;
        private float width, height, eyeHeight, yaw, pitch;
        private boolean onGround;
        private double fallDistance;
        private int flags;
        private double gravity;
        private float speed = 0.1f, jumpStrength = 0.42f, stepHeight = 0.6f;
        private float safeFallDistance = 3, fallDamageMultiplier = 1, knockbackResistance, explosionKnockbackResistance;
        private float bounciness, airDragModifier = 1, frictionModifier = 1, movementEfficiency, waterMovementEfficiency;
        private float sneakingSpeed = 0.3f;
        private int jumpBoost, levitation, resistance;
        private float armor, toughness, fallProtection, blastProtection;
        private float health = 20, absorption;
        private int fuse = 80;
        private float explosionPower = 4;
        private float drag, waterDrag;
        private double acceleration;
        private int age;

        private Builder(Type type) {
            this.type = type;
            switch (type) {
                case PLAYER, LIVING -> { width = 0.6f; height = 1.8f; eyeHeight = 1.62f; gravity = 0.08; }
                case PRIMED_TNT, FALLING_BLOCK -> { width = 0.98f; height = 0.98f; eyeHeight = 0.15f; gravity = 0.04; stepHeight = 0; }
                case ARROW -> { width = 0.5f; height = 0.5f; eyeHeight = 0.13f; gravity = 0.05; stepHeight = 0; drag = 0.99f; waterDrag = 0.6f; }
                case TRIDENT -> { width = 0.5f; height = 0.5f; eyeHeight = 0.13f; gravity = 0.05; stepHeight = 0; drag = 0.99f; waterDrag = 0.99f; }
                case THROWABLE -> { width = 0.25f; height = 0.25f; eyeHeight = 0.2125f; gravity = 0.03; stepHeight = 0; drag = 0.99f; waterDrag = 0.8f; }
                case HURTING_PROJECTILE -> { width = 1; height = 1; eyeHeight = 0.85f; gravity = 0; stepHeight = 0; drag = 0.95f; waterDrag = 0.8f; acceleration = 0.1; }
            }
        }

        public Builder entityId(int id) { this.entityId = id; return this; }
        public Builder pos(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        public Builder vel(double vx, double vy, double vz) { this.vx = vx; this.vy = vy; this.vz = vz; return this; }
        public Builder size(float width, float height, float eyeHeight) { this.width = width; this.height = height; this.eyeHeight = eyeHeight; return this; }
        public Builder rot(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; return this; }
        public Builder onGround(boolean onGround) { this.onGround = onGround; return this; }
        public Builder fallDistance(double fallDistance) { this.fallDistance = fallDistance; return this; }
        public Builder flags(int flags) { this.flags = flags; return this; }
        public Builder flag(int flag, boolean on) { this.flags = on ? flags | flag : flags & ~flag; return this; }
        public Builder gravity(double gravity) { this.gravity = gravity; return this; }
        public Builder speed(float speed) { this.speed = speed; return this; }
        public Builder jumpStrength(float v) { this.jumpStrength = v; return this; }
        public Builder stepHeight(float v) { this.stepHeight = v; return this; }
        public Builder fall(float safeFallDistance, float fallDamageMultiplier) { this.safeFallDistance = safeFallDistance; this.fallDamageMultiplier = fallDamageMultiplier; return this; }
        public Builder knockbackResistance(float kb, float explosionKb) { this.knockbackResistance = kb; this.explosionKnockbackResistance = explosionKb; return this; }
        public Builder bounciness(float v) { this.bounciness = v; return this; }
        public Builder dragModifiers(float air, float friction) { this.airDragModifier = air; this.frictionModifier = friction; return this; }
        public Builder efficiency(float movement, float water) { this.movementEfficiency = movement; this.waterMovementEfficiency = water; return this; }
        public Builder sneakingSpeed(float v) { this.sneakingSpeed = v; return this; }
        public Builder effects(int jumpBoost, int levitation, int resistance) { this.jumpBoost = jumpBoost; this.levitation = levitation; this.resistance = resistance; return this; }
        public Builder armor(float armor, float toughness, float fallProtection, float blastProtection) { this.armor = armor; this.toughness = toughness; this.fallProtection = fallProtection; this.blastProtection = blastProtection; return this; }
        public Builder health(float health, float absorption) { this.health = health; this.absorption = absorption; return this; }
        public Builder fuse(int fuse, float power) { this.fuse = fuse; this.explosionPower = power; return this; }
        public Builder drag(float air, float water, double acceleration) { this.drag = air; this.waterDrag = water; this.acceleration = acceleration; return this; }
        public Builder age(int ticks) { this.age = ticks; return this; }

        public BodyState build() {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)
                    || !Double.isFinite(gravity) || !Double.isFinite(acceleration)
                    || !Double.isFinite(fallDistance)) throw new IllegalArgumentException("non-finite body state");
            if (!(width > 0 && width <= 32 && height > 0 && height <= 64)
                    || Math.abs(vx) > 256 || Math.abs(vy) > 256 || Math.abs(vz) > 256
                    || !(stepHeight >= 0 && stepHeight <= 16))
                throw new IllegalArgumentException("body exceeds supported collision envelope");
            return new BodyState(this);
        }
    }
}
