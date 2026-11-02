/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.api.physics;

import java.util.List;

/**
 * Ostinato: one question for the {@link IPredictivePhysics predictive physics engine}. Immutable; equal requests
 * (same {@link #key}) made against the same world snapshot share one computation.
 */
public final class PredictionRequest {

    /** Longest horizon accepted (one minute of game time); bounds every per-request buffer. */
    public static final int MAX_HORIZON = 1200;
    /** Strongest explosion accepted (vanilla's strongest natural one is 6); bounds the ray work. */
    public static final float MAX_POWER = 16;
    public static final int MAX_TARGETS = 256;

    public enum Kind {
        /** Tick the subject forward: its path, first collision, landing, and for primed TNT its explosion. */
        TRAJECTORY,
        /** As TRAJECTORY, stopping at the first landing, reporting fall distance and damage. */
        FALL,
        /** As TRAJECTORY for a projectile, stopping at the first block or target it hits. */
        PROJECTILE,
        /** One explosion at a given point against the {@link #targets}; no subject. */
        EXPLOSION
    }

    /** Most urgent first. EMERGENCY and COMBAT may run inline on the calling (game) thread within its budget. */
    public enum Priority { EMERGENCY, COMBAT, MOVEMENT, COMPANION, PLANNING }

    /**
     * What is worked out. The physics is the same vanilla tick at every level; the levels differ in what is recorded
     * and which costly extras run.
     */
    public enum Fidelity {
        /** End state and first event only; no path; explosion exposure, no block bounds. */
        SCREENING,
        /** Plus the path every 4 ticks. */
        STANDARD,
        /** Plus the path every tick, explosion block-break bounds and chain-lit TNT. */
        HIGH,
        /** HIGH, and kept for the live validator to compare against what the world then does. */
        VALIDATION
    }

    /** What a living subject's controls do each tick: vanilla key states, held for the whole horizon. */
    public static final class Input {
        public static final Input NONE = new Input(0, 0, false, false, false, Float.NaN, Float.NaN);
        /** -1..1, as the movement keys give them (forward/back, left/right). */
        public final float forward, strafe;
        public final boolean jump, sprint, sneak;
        /** Look angles to hold; NaN keeps the subject's. */
        public final float yaw, pitch;

        public Input(float forward, float strafe, boolean jump, boolean sprint, boolean sneak, float yaw, float pitch) {
            this.forward = forward;
            this.strafe = strafe;
            this.jump = jump;
            this.sprint = sprint;
            this.sneak = sneak;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        int hash() {
            int h = Float.floatToIntBits(forward) * 31 + Float.floatToIntBits(strafe);
            h = h * 31 + (jump ? 1 : 0) + (sprint ? 2 : 0) + (sneak ? 4 : 0);
            return h * 31 + Float.floatToIntBits(yaw) * 17 + Float.floatToIntBits(pitch);
        }
    }

    public final Kind kind;
    /** Null only for EXPLOSION. */
    public final BodyState subject;
    public final int horizonTicks;
    public final Priority priority;
    public final Fidelity fidelity;
    /** System.nanoTime() after which the answer is useless; 0 for none. */
    public final long deadlineNanos;
    /** Who asked, for telemetry. */
    public final String consumer;
    public final Input input;
    /** Living bodies an explosion (the EXPLOSION point, or a primed TNT subject's) is assessed against. */
    public final List<BodyState> targets;
    /** EXPLOSION centre and power; whether it is a TNT-like (block-breaking) explosion. */
    public final double ex, ey, ez;
    public final float power;
    public final boolean breaksBlocks;
    /** World difficulty, 0 peaceful .. 3 hard, for explosion damage to players. */
    public final int difficulty;
    /** Distance from the eye to a landing block within which a clutch block/bucket can be placed (FALL). */
    public final float reach;

    private PredictionRequest(Builder b) {
        kind = b.kind;
        subject = b.subject;
        horizonTicks = b.horizonTicks;
        priority = b.priority;
        fidelity = b.fidelity;
        deadlineNanos = b.deadlineNanos;
        consumer = b.consumer;
        input = b.input;
        targets = List.copyOf(b.targets);
        ex = b.ex; ey = b.ey; ez = b.ez;
        power = b.power;
        breaksBlocks = b.breaksBlocks;
        difficulty = b.difficulty;
        reach = b.reach;
    }

    /** Identity of the question: requests with equal keys against the same snapshot get the same answer. */
    public boolean sameSimulation(PredictionRequest other) {
        if (kind != other.kind || horizonTicks != other.horizonTicks || fidelity != other.fidelity
                || difficulty != other.difficulty || breaksBlocks != other.breaksBlocks
                || Double.compare(ex, other.ex) != 0 || Double.compare(ey, other.ey) != 0 || Double.compare(ez, other.ez) != 0
                || Float.compare(power, other.power) != 0 || Float.compare(reach, other.reach) != 0
                || (subject == null ? other.subject != null : !subject.sameAs(other.subject))
                || Float.compare(input.forward, other.input.forward) != 0 || Float.compare(input.strafe, other.input.strafe) != 0
                || Float.compare(input.yaw, other.input.yaw) != 0 || Float.compare(input.pitch, other.input.pitch) != 0
                || input.jump != other.input.jump || input.sprint != other.input.sprint || input.sneak != other.input.sneak
                || targets.size() != other.targets.size()) return false;
        for (int i = 0; i < targets.size(); i++) if (!targets.get(i).sameAs(other.targets.get(i))) return false;
        return true;
    }

    public long key() {
        long h = mix(kind.ordinal(), (long) horizonTicks << 8 | fidelity.ordinal());
        if (subject != null) h = mix(h, bodyHash(subject));
        h = mix(h, input.hash());
        for (BodyState t : targets) h = mix(h, bodyHash(t));
        h = mix(h, Double.doubleToLongBits(ex));
        h = mix(h, Double.doubleToLongBits(ey));
        h = mix(h, Double.doubleToLongBits(ez));
        h = mix(h, (long) Float.floatToIntBits(power) << 32 | Float.floatToIntBits(reach) & 0xFFFFFFFFL);
        h = mix(h, difficulty * 2L + (breaksBlocks ? 1 : 0));
        return h;
    }

    /** Every field of a body, so two bodies that can behave differently do not share a key. */
    private static long bodyHash(BodyState s) {
        long h = s.type.ordinal() * 31L + s.entityId;
        h = mix(h, Double.doubleToLongBits(s.x));
        h = mix(h, Double.doubleToLongBits(s.y));
        h = mix(h, Double.doubleToLongBits(s.z));
        h = mix(h, Double.doubleToLongBits(s.vx));
        h = mix(h, Double.doubleToLongBits(s.vy));
        h = mix(h, Double.doubleToLongBits(s.vz));
        h = mix(h, Double.doubleToLongBits(s.fallDistance));
        h = mix(h, Double.doubleToLongBits(s.gravity));
        h = mix(h, Double.doubleToLongBits(s.acceleration));
        h = mix(h, (long) s.flags << 32 | (s.onGround ? 1 : 0));
        h = mix(h, (long) s.fuse << 32 | s.age & 0xFFFFFFFFL);
        h = mix(h, (long) s.jumpBoost << 40 | (long) s.levitation << 20 | s.resistance);
        float[] f = {s.width, s.height, s.eyeHeight, s.yaw, s.pitch, s.speed, s.jumpStrength, s.stepHeight, s.safeFallDistance,
                s.fallDamageMultiplier, s.knockbackResistance, s.explosionKnockbackResistance, s.bounciness, s.airDragModifier,
                s.frictionModifier, s.movementEfficiency, s.waterMovementEfficiency, s.sneakingSpeed, s.armor, s.toughness,
                s.fallProtection, s.blastProtection, s.health, s.absorption, s.explosionPower, s.drag, s.waterDrag};
        for (float v : f) h = mix(h, Float.floatToIntBits(v));
        return h;
    }

    private static long mix(long h, long v) {
        h = (h ^ v) * 0x9E3779B97F4A7C15L;
        return h ^ h >>> 29;
    }

    public static Builder trajectory(BodyState subject, int horizonTicks) {
        return new Builder(Kind.TRAJECTORY, subject, horizonTicks);
    }

    public static Builder fall(BodyState subject, int horizonTicks) {
        return new Builder(Kind.FALL, subject, horizonTicks);
    }

    public static Builder projectile(BodyState subject, int horizonTicks) {
        return new Builder(Kind.PROJECTILE, subject, horizonTicks);
    }

    public static Builder explosion(double x, double y, double z, float power, boolean breaksBlocks) {
        if (!(power >= 0 && power <= MAX_POWER)) throw new IllegalArgumentException("power " + power);
        Builder b = new Builder(Kind.EXPLOSION, null, 0);
        b.ex = x;
        b.ey = y;
        b.ez = z;
        b.power = power;
        b.breaksBlocks = breaksBlocks;
        return b;
    }

    public static final class Builder {
        private final Kind kind;
        private final BodyState subject;
        private final int horizonTicks;
        private Priority priority = Priority.MOVEMENT;
        private Fidelity fidelity = Fidelity.STANDARD;
        private long deadlineNanos;
        private String consumer = "?";
        private Input input = Input.NONE;
        private List<BodyState> targets = List.of();
        private double ex, ey, ez;
        private float power;
        private boolean breaksBlocks;
        private int difficulty = 2;
        private float reach = 4.5f;

        private Builder(Kind kind, BodyState subject, int horizonTicks) {
            if (kind != Kind.EXPLOSION && subject == null) throw new IllegalArgumentException("subject");
            if (horizonTicks < 0 || horizonTicks > MAX_HORIZON) throw new IllegalArgumentException("horizon " + horizonTicks);
            this.kind = kind;
            this.subject = subject;
            this.horizonTicks = horizonTicks;
        }

        public Builder priority(Priority p) { this.priority = p; return this; }
        public Builder fidelity(Fidelity f) { this.fidelity = f; return this; }
        /** Give up after this many nanoseconds from now. */
        public Builder deadlineIn(long nanos) { this.deadlineNanos = System.nanoTime() + nanos; return this; }
        public Builder consumer(String c) { this.consumer = c; return this; }
        public Builder input(Input in) { this.input = in; return this; }
        public Builder targets(List<BodyState> t) { this.targets = t; return this; }
        public Builder difficulty(int d) { this.difficulty = d; return this; }
        public Builder reach(float r) { this.reach = r; return this; }

        public PredictionRequest build() {
            java.util.Objects.requireNonNull(priority, "priority");
            java.util.Objects.requireNonNull(fidelity, "fidelity");
            java.util.Objects.requireNonNull(input, "input");
            if (targets.size() > MAX_TARGETS) throw new IllegalArgumentException("too many targets");
            if (difficulty < 0 || difficulty > 3) throw new IllegalArgumentException("difficulty");
            if (!Double.isFinite(ex) || !Double.isFinite(ey) || !Double.isFinite(ez)) throw new IllegalArgumentException("centre");
            if (!(reach >= 0 && Float.isFinite(reach))) throw new IllegalArgumentException("reach");
            if (subject != null && !(subject.explosionPower >= 0 && subject.explosionPower <= MAX_POWER)) throw new IllegalArgumentException("subject power");
            return new PredictionRequest(this);
        }
    }
}
