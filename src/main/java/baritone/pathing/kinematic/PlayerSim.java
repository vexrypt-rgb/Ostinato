package baritone.pathing.kinematic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Allocation-light copy of vanilla 26.3 player movement for look-ahead search: LocalPlayer's input and sprint rules,
 * LivingEntity.aiStep and travelInAir / travelInWater, Entity.move with its step-up candidates, supporting block and
 * collision restitution, and the block effects applied after the travel. Covers walking, sprinting, jumping, step-up,
 * block collision, ladders/vines and still water via {@link #tickWater}, and the blocks that slow a walker or a jump
 * (soul sand, honey, with honey's slide down its sides) or bounce a landing (slime, beds); no lava, currents, sneaking
 * or the swimming pose's lower box. Of the potion effects it follows Speed, Slowness and Jump Boost
 * ({@link #speedScale}, {@link #jumpBoost}); nothing that changes gravity. Vanilla's floats stay floats and every
 * rotation goes through vanilla's sine table, so in the cases it covers a tick aims at vanilla's bits rather than a close
 * double (checked against PPE and decompiled values, not yet against a live client).
 * <p>
 * Ostinato 2.0: this was the 1.16 model until the physics-263 change (docs/ostinato2/DECISIONS.md D12).
 */
public final class PlayerSim {

    /** Supplies collision boxes as {minX, minY, minZ, maxX, maxY, maxZ} and ground slipperiness. */
    public interface World {
        void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out);

        float slipperiness(int x, int y, int z);

        /** Slime: walking on it is slowed, and (through {@link #bounce}) a landing bounces back up. */
        default boolean bouncy(int x, int y, int z) {
            return false;
        }

        /** Block.getBounceRestitution: 1 for slime, 0.75 for beds and shelf mushrooms in 26.3, else 0. */
        default float bounce(int x, int y, int z) {
            return bouncy(x, y, z) ? 1.0f : 0.0f;
        }

        /** Still or flowing water fills this cell (flow is not simulated). */
        default boolean water(int x, int y, int z) {
            return false;
        }

        /** Ladder, vine or anything else the player can hang on to. */
        default boolean climbable(int x, int y, int z) {
            return false;
        }

        /** Block.getSpeedFactor: 0.4 for soul sand and honey, which slow whoever stands in or on them. */
        default float speedFactor(int x, int y, int z) {
            return 1;
        }

        /** Block.getJumpFactor: 0.5 for honey, which halves a jump off it. */
        default float jumpFactor(int x, int y, int z) {
            return 1;
        }

        /** Honey: falling past its side slides. */
        default boolean sticky(int x, int y, int z) {
            return false;
        }

        /**
         * Ostinato: adds the boxes of one cell, in world coordinates and in the order {@link #collect} lists them, and
         * returns true; false if this world cannot list a single cell. That keeps searches over it out of native code,
         * and leaves the player without a supporting block: friction then comes from under the player's centre.
         */
        default boolean cellBoxes(int x, int y, int z, List<double[]> out) {
            return false;
        }
    }

    public static final double HALF_WIDTH = 0.3f; // vanilla sizes are floats: the box edge lands exactly on block faces
    public static final double HEIGHT = 1.8f;
    /** Attributes.STEP_HEIGHT, a float. */
    public static final float STEP = 0.6f;
    private static final double CLIMB_LIMIT = 0.15f, CLIMB_SPEED = 0.2, GRAVITY = 0.08;
    /** Player's movement speed attribute and the sprint's multiplier on it, both floats widened as vanilla stores them. */
    private static final double WALK_SPEED = 0.1f, SPRINT_BONUS = 0.3f;
    /** A honey block's collision box stops a sixteenth short of its cell: how far out its side is felt, and how high. */
    private static final double HONEY_REACH = 0.4375 + HALF_WIDTH, HONEY_TOP = 0.9375;
    /** LivingEntity.computeModifiedFriction with no modifier: the horizontal air drag and the vertical drag. */
    static final float AIR_DRAG = modifiedFriction(0.91f), VERTICAL_DRAG = modifiedFriction(0.98f);
    /** Entity.getBlockPosBelowThatAffectsMyMovement and getOnPosLegacy: how far under the feet they look. */
    private static final double BELOW_AFFECTING = 0.500001f, ON_POS_LEGACY = 0.2f;
    /**
     * LocalPlayer.isHorizontalCollisionMinor: a wall met within 8 degrees of where the input pushes leaves the sprint
     * on. Vanilla compares the angle's acos with 8 degrees; this compares its cosine with cos(8 degrees).
     */
    static final double MINOR_COS = StrictMath.cos(0.13962633907794952);

    /** Mth's 65536-entry sine table, built with StrictMath so the native port builds the same one. */
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) StrictMath.sin(i / 10430.378350470453);
    }

    /** Mth.sin. */
    public static float msin(double r) {
        return SIN[(int) ((long) (r * 10430.378350470453) & 0xFFFFL)];
    }

    /** Mth.cos. */
    public static float mcos(double r) {
        return SIN[(int) ((long) (r * 10430.378350470453 + 16384.0) & 0xFFFFL)];
    }

    /** LivingEntity.computeModifiedFriction with a modifier of 1. */
    static float modifiedFriction(float friction) {
        return Math.max(0.0f, Math.min(1.0f, 1.0f - (1.0f - friction) * 1.0f));
    }

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, collidedH, swimming;
    /** Entity.minorHorizontalCollision: the wall met last tick was within 8 degrees of where the input pushed. */
    public boolean minorCollision;
    /** Entity.mainSupportingBlockPos (at supX/Y/Z when {@link #supported}) and Entity.onGroundNoBlocks. */
    public boolean supported, onGroundNoBlocks;
    public int supX, supY, supZ;
    /** Vanilla's jump cooldown: holding jump re-jumps only every 10 ticks. */
    public int jumpTicks;
    /**
     * What effects and attributes change about the player: ground speed as a multiple of the plain one (Speed,
     * Slowness), the strength of a jump, and what a jump gets on top of that (Jump Boost, 0.1 a level). Air control
     * is not scaled, as in vanilla. Left as they are, nothing changes.
     */
    public double speedScale = 1, jumpStrength = 0.42, jumpBoost;
    /** How much of a slowing block's drag is taken away, 0 to 1 (Soul Speed). */
    public double movementEfficiency;

    private final World world;
    private final List<double[]> boxes = new ArrayList<>();
    private final List<double[]> cell = new ArrayList<>();
    private float[] candidates = new float[8];
    /** This tick's xxa, zza and yaw in radians, for the minor-collision test inside {@link #move}. */
    private double inX, inZ;
    private float yawRad;
    /** Where this tick's move started, and whether it took z before x: the path honey is felt along. */
    private double fromX, fromY, fromZ;
    private boolean zFirst;

    public PlayerSim(World world) {
        this.world = world;
    }

    public World world() {
        return world;
    }

    public PlayerSim copyFrom(PlayerSim o) {
        x = o.x; y = o.y; z = o.z; vx = o.vx; vy = o.vy; vz = o.vz;
        onGround = o.onGround; sprinting = o.sprinting; swimming = o.swimming; collidedH = o.collidedH; jumpTicks = o.jumpTicks;
        minorCollision = o.minorCollision; supported = o.supported; onGroundNoBlocks = o.onGroundNoBlocks;
        supX = o.supX; supY = o.supY; supZ = o.supZ;
        speedScale = o.speedScale; jumpStrength = o.jumpStrength; jumpBoost = o.jumpBoost; movementEfficiency = o.movementEfficiency;
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

    /**
     * With a strafe key too: +1 is A (left), -1 is D (right). The keys go through {@link #keyInput}, so a diagonal is
     * full length and strafing adds no speed.
     */
    public void tick(float yawDeg, int input, int strafe, boolean sprint, boolean jump) {
        float[] in = keyInput(input, strafe);
        tickImpulse(yawDeg, in[0], in[1], input > 0, sprint, jump);
    }

    /**
     * The keys as xxa (+left) and zza (+forward) hold them after LocalPlayer.applyInput, in floats: KeyboardInput's
     * normalised key vector, then modifyInput's 0.98 and its square-movement correction. No item or sneak slow-down.
     */
    public static float[] keyInput(int input, int strafe) {
        return keyInput(input, strafe, false, false);
    }

    /**
     * As above with modifyInput's slow-downs, each a float scale after the 0.98: using an item (UseEffects' default
     * 0.2), then sneaking (the sneaking speed attribute's default 0.3).
     */
    public static float[] keyInput(int input, int strafe, boolean using, boolean sneaking) {
        float mx = strafe, mz = input;
        float dist = (float) Math.sqrt(mx * mx + mz * mz);
        if (dist < 1.0E-4f) return new float[]{0.0f, 0.0f};
        mx = mx / dist;
        mz = mz / dist;
        mx *= 0.98f;
        mz *= 0.98f;
        if (using) {
            mx *= 0.2f;
            mz *= 0.2f;
        }
        if (sneaking) {
            mx *= 0.3f;
            mz *= 0.3f;
        }
        float len = (float) Math.sqrt(mx * mx + mz * mz);
        if (len <= 0.0f) return new float[]{mx, mz};
        float inv = 1.0f / len;
        float dx = mx * inv, dz = mz * inv;
        float ax = Math.abs(dx), az = Math.abs(dz);
        float tan = az > ax ? ax / az : az / ax;
        float toSquare = (float) Math.sqrt((float) (1.0f + tan * tan));
        float m = Math.min(len * toSquare, 1.0f);
        return new float[]{dx * m, dz * m};
    }

    /**
     * Ostinato: one tick from the input as vanilla's xxa/zza hold it once the keys are read (after the 0.98 input
     * friction, item-use and sneak slow-downs, square-movement correction): {@code strafeImpulse} +left, {@code
     * forwardImpulse} +forward. For decoding someone else's movement, where the impulse is fitted, not pressed.
     * {@link #tick(float, int, int, boolean, boolean)} is this with {@link #keyInput}, bit for bit.
     */
    public void tickImpulse(float yawDeg, double strafeImpulse, double forwardImpulse, boolean sprint, boolean jump) {
        tickImpulse(yawDeg, strafeImpulse, forwardImpulse, forwardImpulse > 1.0E-5f, sprint, jump);
    }

    /**
     * Ostinato: as above, with whether a sprint may hold this tick given by the caller instead of read off the forward
     * impulse. A server-side bot (VexBot) sprints while it strafes; a vanilla client stops.
     */
    public void tickImpulse(float yawDeg, double strafeImpulse, double forwardImpulse, boolean forward, boolean sprint, boolean jump) {
        // LivingEntity.aiStep: a player's horizontal speed under 0.003 stops as a whole, the vertical on its own
        if (vx * vx + vz * vz < 9.0E-6) {
            vx = 0;
            vz = 0;
        }
        if (Math.abs(vy) < 0.003) vy = 0;
        // LocalPlayer.aiStep: the key starts a sprint; losing forward input, or a wall met more than 8 degrees off, stops it
        sprinting = forward && !(collidedH && !minorCollision) && (sprint || sprinting);
        inX = strafeImpulse;
        inZ = forwardImpulse;
        yawRad = yawDeg * ((float) Math.PI / 180);
        if (jumpTicks > 0) jumpTicks--;
        if (!jump) jumpTicks = 0;
        if (jump && onGround && jumpTicks == 0) {
            jumpTicks = 10;
            jumpFromGround();
        }
        // LivingEntity.travelInAir
        float blockFriction = onGround ? modifiedFriction(world.slipperiness(supportedX(), floor(y - BELOW_AFFECTING), supportedZ())) : 1.0f;
        float speed;
        if (onGround) { // getFrictionInfluencedSpeed, with Player.getSpeed: the attribute as a float
            float move = (float) (WALK_SPEED * speedScale * (sprinting ? 1.0 + SPRINT_BONUS : 1.0));
            speed = (double) blockFriction > 0.6 ? move * (0.21600002f / (blockFriction * blockFriction * blockFriction)) : move;
        } else { // Player.getFlyingSpeed
            speed = sprinting ? 0.025999999f : 0.02f;
        }
        moveRelative(speed, strafeImpulse, forwardImpulse);
        if (climbable()) { // LivingEntity.handleOnClimbable
            vx = Math.max(-CLIMB_LIMIT, Math.min(CLIMB_LIMIT, vx));
            vz = Math.max(-CLIMB_LIMIT, Math.min(CLIMB_LIMIT, vz));
            vy = Math.max(vy, -CLIMB_LIMIT);
        }
        move(vx, vy, vz);
        if ((collidedH || jump) && climbable()) {
            vy = CLIMB_SPEED; // pressing into the wall or holding jump climbs
        }
        vy = (vy - GRAVITY) * (double) VERTICAL_DRAG;
        float friction = blockFriction * AIR_DRAG;
        vx *= friction;
        vz *= friction;
        afterTravel();
    }

    /** LivingEntity.jumpFromGround. */
    private void jumpFromGround() {
        float power = (float) jumpStrength * jumpFactor() + (float) jumpBoost;
        if (power <= 1.0E-5f) return;
        vy = Math.max(power, vy);
        if (sprinting) {
            vx += (double) (-msin(yawRad)) * 0.2;
            vz += (double) mcos(yawRad) * 0.2;
        }
    }

    /** Entity.moveRelative and getInputVector: the input, cut to length 1, scaled and turned by the yaw. */
    private void moveRelative(float speed, double ix, double iz) {
        double lenSq = ix * ix + iz * iz;
        double mx = 0.0, mz = 0.0; // vanilla adds its zero vector too, which turns a -0.0 speed into 0.0
        if (lenSq >= 1.0E-7) {
            boolean zero = false;
            if (lenSq > 1.0) {
                double dist = Math.sqrt(lenSq);
                if (dist < (double) 1.0E-5f) {
                    zero = true;
                } else {
                    ix = ix / dist;
                    iz = iz / dist;
                }
            }
            if (!zero) {
                ix *= speed;
                iz *= speed;
                double sin = msin(yawRad), cos = mcos(yawRad);
                mx = ix * cos - iz * sin;
                mz = iz * cos + ix * sin;
            }
        }
        vx += mx;
        vz += mz;
    }

    /**
     * LivingEntity.applyEffectsFromBlocks, after the travel: a slime block slows a walker that is barely moving up or
     * down, and a honey block's side catches a faller.
     */
    private void afterTravel() {
        if (onGround && world.bouncy(supportedX(), legacyY(), supportedZ())) { // SlimeBlock.stepOn
            double ay = Math.abs(vy);
            if (ay < 0.1) {
                double k = 0.4 + ay * 0.2;
                vx *= k;
                vz *= k;
            }
        }
        honeySlide();
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
     * Climbing out: walking into a bank with free space above gives the 0.3 hop. With the head out of the water
     * (vanilla's shallow water) a sprint neither starts nor holds.
     */
    public void tickWater(float yawDeg, float pitchDeg, boolean forward, boolean sprint, boolean jump, boolean sneak) {
        boolean wet = inWater(), under = eyesInWater();
        // Entity.updateSwimming, in baseTick: from last tick's sprint
        swimming = swimming ? sprinting && wet : sprinting && under && world.water(floor(x), floor(y), floor(z));
        // LocalPlayer.aiStep: shallow water stops a run sprint; a swim sprint ends out of the water or with no input
        boolean shallow = wet && !under;
        if (!sprinting && forward && !shallow && !(sneak && !under) && sprint) sprinting = true;
        if (sprinting) {
            if (swimming) {
                if (!wet || !forward && !onGround && !sneak) sprinting = false;
            } else if (shallow || !forward || collidedH && !minorCollision) {
                sprinting = false;
            }
        }
        if (wet && sneak) vy += (double) -0.04f; // LocalPlayer.aiStep: goDownInWater, before LivingEntity.aiStep
        if (vx * vx + vz * vz < 9.0E-6) {
            vx = 0;
            vz = 0;
        }
        if (Math.abs(vy) < 0.003) vy = 0;
        float[] in = keyInput(forward ? 1 : 0, 0);
        inX = in[0];
        inZ = in[1];
        yawRad = yawDeg * ((float) Math.PI / 180);
        if (jump) vy += (double) 0.04f; // LivingEntity.jumpInLiquid
        if (swimming) { // Player.travel
            double look = -msin(pitchDeg * ((float) Math.PI / 180));
            double k = look < -0.2 ? 0.085 : 0.06;
            if (look <= 0 || jump || world.water(floor(x), floor(y + 1 - 0.1), floor(z))) {
                vy += (look - vy) * k;
            }
        }
        boolean falling = vy <= 0.0;
        double startY = y;
        float slowDown = sprinting ? 0.9f : 0.8f;
        moveRelative(0.02f, inX, inZ);
        move(vx, vy, vz);
        if (collidedH && climbable()) vy = CLIMB_SPEED;
        vx *= slowDown;
        vy *= (double) 0.8f;
        vz *= slowDown;
        if (!sprinting) { // LivingEntity.getFluidFallingAdjustedMovement
            vy = falling && Math.abs(vy - 0.005) >= 0.003 && Math.abs(vy - GRAVITY / 16.0) < 0.003 ? -0.003 : vy - GRAVITY / 16.0;
        }
        if (collidedH) { // LivingEntity.jumpOutOfFluid
            double dy = vy + (double) 0.6f - y + startY;
            if (free(x + vx, y + dy, z + vz) && !liquidIn(x + vx, y + dy, z + vz)) vy = 0.3f;
        }
        afterTravel();
    }

    /** CollisionGetter.noCollision for the player's box with its feet here: no block box overlaps it. */
    private boolean free(double px, double py, double pz) {
        double minX = px - HALF_WIDTH, maxX = px + HALF_WIDTH, minY = py, maxY = py + HEIGHT;
        double minZ = pz - HALF_WIDTH, maxZ = pz + HALF_WIDTH;
        boxes.clear();
        world.collect(minX, minY, minZ, maxX, maxY, maxZ, boxes);
        for (double[] b : boxes) {
            if (b[0] < maxX && b[3] > minX && b[1] < maxY && b[4] > minY && b[2] < maxZ && b[5] > minZ) return false;
        }
        return true;
    }

    /** Level.containsAnyLiquid over the player's box with its feet here. */
    private boolean liquidIn(double px, double py, double pz) {
        int x0 = floor(px - HALF_WIDTH), x1 = ceil(px + HALF_WIDTH) - 1;
        int y0 = floor(py), y1 = ceil(py + HEIGHT) - 1;
        int z0 = floor(pz - HALF_WIDTH), z1 = ceil(pz + HALF_WIDTH) - 1;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = z0; cz <= z1; cz++) {
                    if (world.water(cx, cy, cz)) return true;
                }
            }
        }
        return false;
    }

    /** Whether the cell the player's feet are in can be hung on to. */
    public boolean climbable() {
        return world.climbable(floor(x), floor(y), floor(z));
    }

    /** Entity.move. */
    private void move(double dx, double dy, double dz) {
        fromX = x;
        fromY = y;
        fromZ = z;
        zFirst = Math.abs(dx) < Math.abs(dz);
        double[] r = collideStep(dx, dy, dz);
        double mLen = r[0] * r[0] + r[1] * r[1] + r[2] * r[2];
        if (mLen > 1.0E-7 || dx * dx + dy * dy + dz * dz - mLen < 1.0E-7) {
            x += r[0];
            y += r[1];
            z += r[2];
        }
        boolean xColl = !equal(dx, r[0]), zColl = !equal(dz, r[2]);
        collidedH = xColl || zColl;
        boolean vColl = dy != r[1];
        onGround = vColl && dy < 0.0;
        checkSupportingBlock(r[0], r[2]);
        minorCollision = collidedH && minor(r[0], r[2]);
        if (Math.abs(dy) > 0.0 && vColl || collidedH) {
            // restituteMovementAfterCollisions: a player has no bounce of its own (its restitution is 0, which leaves
            // the sign of the zero vanilla writes), and only a landing takes the block's
            double rest = 0.0;
            if (xColl) vx = -vx * rest;
            if (zColl) vz = -vz * rest;
            if (vColl) {
                double cvy = vy;
                if (onGround) rest = -cvy <= GRAVITY ? 0.0 : Math.max(rest, world.bounce(supportedX(), legacyY(), supportedZ()));
                double comp = 0.0, drag = 1.0;
                if (rest > 0.0) {
                    double portion = r[1] / cvy;
                    comp = portion * GRAVITY;
                    drag = 1.0 + portion * ((double) VERTICAL_DRAG - 1.0);
                }
                vy = (comp - cvy) * drag * rest;
            }
        }
        // Entity.move ends by scaling the horizontal speed with the block's factor, in the air as on the ground
        float drag = speedFactor();
        if (drag != 1) {
            vx *= drag;
            vz *= drag;
        }
    }

    /** Entity.collide: the move as collided, or stepped up by the lowest candidate height that gets further. */
    private double[] collideStep(double dx, double dy, double dz) {
        double[] r = dx * dx + dy * dy + dz * dz == 0.0 ? new double[]{dx, dy, dz} : collide(x, y, z, dx, dy, dz);
        boolean groundAfter = dy != r[1] && dy < 0.0;
        if ((groundAfter || onGround) && (dx != r[0] || dz != r[2])) {
            double gy = groundAfter ? y + r[1] : y;
            int n = stepCandidates(gy, dx, dz, groundAfter, (float) r[1]);
            double before = r[0] * r[0] + r[2] * r[2];
            for (int i = 0; i < n; i++) {
                double[] s = collide(x, gy, z, dx, candidates[i], dz);
                if (s[0] * s[0] + s[2] * s[2] > before) {
                    s[1] -= y - gy;
                    return s;
                }
            }
        }
        return r;
    }

    /**
     * Entity.collectCandidateStepUpHeights: the heights over the grounded feet of every box edge in the step-up area,
     * from 0 to {@link #STEP}, without the one the move itself ended at, sorted.
     */
    private int stepCandidates(double gy, double dx, double dz, boolean groundAfter, float skip) {
        double minX = x - HALF_WIDTH + Math.min(dx, 0), maxX = x + HALF_WIDTH + Math.max(dx, 0);
        double minZ = z - HALF_WIDTH + Math.min(dz, 0), maxZ = z + HALF_WIDTH + Math.max(dz, 0);
        double minY = groundAfter ? gy : gy + (double) -1.0E-5f, maxY = gy + HEIGHT + (double) STEP;
        boxes.clear();
        world.collect(minX, minY, minZ, maxX, maxY, maxZ, boxes);
        int n = 0;
        for (double[] b : boxes) {
            // BlockCollisions: only shapes the area really overlaps
            if (!(b[0] < maxX && b[3] > minX && b[1] < maxY && b[4] > minY && b[2] < maxZ && b[5] > minZ)) continue;
            for (int k = 1; k <= 4; k += 3) {
                float rel = (float) (b[k] - gy);
                if (rel < 0.0f || rel == skip || rel > STEP) continue;
                boolean dup = false;
                for (int j = 0; j < n && !dup; j++) dup = Float.floatToIntBits(candidates[j]) == Float.floatToIntBits(rel);
                if (dup) continue;
                if (n == candidates.length) candidates = Arrays.copyOf(candidates, n * 2);
                candidates[n++] = rel;
            }
        }
        Arrays.sort(candidates, 0, n);
        return n;
    }

    /** Entity.checkSupportingBlock after a move of (mx, mz). */
    private void checkSupportingBlock(double mx, double mz) {
        if (onGround) {
            boolean found = findSupport(x - HALF_WIDTH, z - HALF_WIDTH, x + HALF_WIDTH, z + HALF_WIDTH);
            if (!found && !onGroundNoBlocks) found = findSupport(x - HALF_WIDTH - mx, z - HALF_WIDTH - mz, x + HALF_WIDTH - mx, z + HALF_WIDTH - mz);
            supported = found;
            onGroundNoBlocks = !found;
        } else {
            onGroundNoBlocks = false;
            supported = false;
        }
    }

    /**
     * CollisionGetter.findSupportingBlock over the thin box under the feet: of the cells whose boxes overlap it, the
     * one whose centre is nearest the feet, ties to the greater BlockPos (y, then z, then x).
     */
    private boolean findSupport(double minX, double minZ, double maxX, double maxZ) {
        double minY = y - 1.0E-6, maxY = y;
        boolean found = false;
        double best = Double.MAX_VALUE;
        for (int cx = floor(minX); cx <= floor(maxX); cx++) {
            for (int cy = floor(minY) - 1; cy <= floor(maxY); cy++) { // -1: fences and walls stick up 1.5
                for (int cz = floor(minZ); cz <= floor(maxZ); cz++) {
                    cell.clear();
                    if (!world.cellBoxes(cx, cy, cz, cell)) return false;
                    boolean hit = false;
                    for (int i = 0; i < cell.size() && !hit; i++) {
                        double[] b = cell.get(i);
                        hit = b[0] < maxX && b[3] > minX && b[1] < maxY && b[4] > minY && b[2] < maxZ && b[5] > minZ;
                    }
                    if (!hit) continue;
                    double ddx = cx + 0.5 - x, ddy = cy + 0.5 - y, ddz = cz + 0.5 - z;
                    double dist = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (dist < best || dist == best && (!found || compare(supX, supY, supZ, cx, cy, cz) < 0)) {
                        supX = cx;
                        supY = cy;
                        supZ = cz;
                        best = dist;
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    /** Vec3i.compareTo: y, then z, then x. */
    private static int compare(int ax, int ay, int az, int bx, int by, int bz) {
        if (ay != by) return Integer.compare(ay, by);
        if (az != bz) return Integer.compare(az, bz);
        return Integer.compare(ax, bx);
    }

    /**
     * Entity.getOnPos: the supporting block's column, else the feet's. (Vanilla keeps the supporting block's own height
     * under fences, walls and gates, which only tells apart cells nothing here reads.)
     */
    private int supportedX() {
        return supported ? supX : floor(x);
    }

    private int supportedZ() {
        return supported ? supZ : floor(z);
    }

    /** Entity.getOnPosLegacy's height: where landing and walking effects come from. */
    private int legacyY() {
        return floor(y - ON_POS_LEGACY);
    }

    /** LocalPlayer.isHorizontalCollisionMinor for a move of (mx, mz) with this tick's input. */
    private boolean minor(double mx, double mz) {
        double s = msin(yawRad), c = mcos(yawRad);
        double gx = inX * c - inZ * s, gz = inZ * c + inX * s;
        double a = gx * gx + gz * gz, m = mx * mx + mz * mz;
        if (a < (double) 1.0E-5f || m < (double) 1.0E-5f) return false;
        double cos = (gx * mx + gz * mz) / Math.sqrt(a * m);
        return cos > MINOR_COS && cos <= 1.0; // past 1 vanilla's acos is NaN, which is not minor
    }

    /** Entity.getBlockSpeedFactor with Player's movement efficiency: the block the feet are in, else the one under them. */
    private float speedFactor() {
        int cx = floor(x), cy = floor(y), cz = floor(z);
        float f = world.speedFactor(cx, cy, cz);
        if (f == 1.0f && !world.water(cx, cy, cz)) f = world.speedFactor(supportedX(), floor(y - BELOW_AFFECTING), supportedZ());
        return f == 1.0f ? f : f + (float) movementEfficiency * (1.0f - f);
    }

    /** Entity.getBlockJumpFactor: the block the feet are in, else the one under them. */
    private float jumpFactor() {
        float here = world.jumpFactor(floor(x), floor(y), floor(z));
        float below = world.jumpFactor(supportedX(), floor(y - BELOW_AFFECTING), supportedZ());
        return here == 1.0f ? below : here;
    }

    /**
     * HoneyBlock.entityInside, after the travel: a faller whose box reaches into a honey block's cell from the side,
     * where the block itself stops a sixteenth short, slides down it at 0.05 a tick. Vanilla works back to the speed
     * before gravity and drag to decide, and sets the speed that 0.05 becomes after them. The cells are those
     * Entity.checkInsideBlocks visits: every cell the box passed through on this tick's move, one axis at a time in
     * collide's order. A slide sets a speed that no longer passes the test, so the first honey cell that passes is
     * the only one that counts.
     */
    private void honeySlide() {
        if (onGround) return;
        double oldVy = vy / (double) 0.98f + 0.08;
        if (oldVy >= -0.08) return;
        double dx = x - fromX, dy = y - fromY, dz = z - fromZ;
        boolean hit;
        if (dx * dx + dy * dy + dz * dz > 0.0) {
            double px = fromX, py = fromY + dy, pz = fromZ;
            hit = dy != 0.0 && honeyLeg(px, py, pz, 0, dy, 0, oldVy);
            if (!hit && zFirst) {
                hit = dz != 0.0 && honeyLeg(px, py, pz + dz, 0, 0, dz, oldVy);
                hit = hit || dx != 0.0 && honeyLeg(px + dx, py, pz + dz, dx, 0, 0, oldVy);
            } else if (!hit) {
                hit = dx != 0.0 && honeyLeg(px + dx, py, pz, dx, 0, 0, oldVy);
                hit = hit || dz != 0.0 && honeyLeg(px + dx, py, pz + dz, 0, 0, dz, oldVy);
            }
        } else {
            honeyLeg(x, y, z, 0, 0, 0, oldVy);
        }
    }

    /**
     * One leg of the move, ending with the feet at (tx, ty, tz) after moving (mx, my, mz) along one axis: the cells the
     * box, a little shrunk, touched on the way, as BlockGetter.forEachBlockIntersectedBetween finds them.
     */
    private boolean honeyLeg(double tx, double ty, double tz, double mx, double my, double mz, double oldVy) {
        double e = 1.0E-5f;
        double minX = tx - HALF_WIDTH + e, maxX = tx + HALF_WIDTH - e;
        double minY = ty + e, maxY = ty + HEIGHT - e;
        double minZ = tz - HALF_WIDTH + e, maxZ = tz + HALF_WIDTH - e;
        if (mx * mx + my * my + mz * mz >= (double) (1.0E-5f * 1.0E-5f)) { // the box where the leg started, too
            minX = Math.min(minX, minX - mx); maxX = Math.max(maxX, maxX - mx);
            minY = Math.min(minY, minY - my); maxY = Math.max(maxY, maxY - my);
            minZ = Math.min(minZ, minZ - mz); maxZ = Math.max(maxZ, maxZ - mz);
        }
        for (int cx = floor(minX); cx <= floor(maxX); cx++) {
            for (int cz = floor(minZ); cz <= floor(maxZ); cz++) {
                // HoneyBlock.isSlidingDown's own test, from where the player ended up: most ticks no cell passes it
                if (Math.abs(cx + 0.5 - x) + 1e-7 <= HONEY_REACH && Math.abs(cz + 0.5 - z) + 1e-7 <= HONEY_REACH) continue;
                for (int cy = floor(minY); cy <= floor(maxY); cy++) {
                    if (y > cy + HONEY_TOP - 1e-7 || !world.sticky(cx, cy, cz)) continue;
                    if (oldVy < -0.13) {
                        double k = -0.05 / oldVy;
                        vx *= k;
                        vz *= k;
                    }
                    vy = (-0.05 - 0.08) * (double) 0.98f;
                    return true;
                }
            }
        }
        return false;
    }

    /** Mth.equal. */
    static boolean equal(double a, double b) {
        return Math.abs(b - a) < (double) 1.0E-5f;
    }

    private static int ceil(double v) {
        int i = (int) v;
        return v > i ? i + 1 : i;
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

    /**
     * Ostinato: the block cell the feet stand in, as {@code IPlayerContext.playerFeet} counts it. A landing on soul sand,
     * a chest, farmland or a lantern rests a little below the cell's floor, and still belongs to that cell.
     */
    public static int feetCell(double y) {
        return floor(y + 0.1251);
    }

    /**
     * Ostinato: whether feet at {@code y} stand in the planner's node at height {@code nodeY}: its feet cell, or a low
     * top (candle, bed, slab, 3+ layers of snow: 0.25 to 0.875 high) in the cell below, which the planner counts as
     * the node above it.
     */
    public static boolean nodeHeight(double y, int nodeY) {
        return feetCell(y) == nodeY || y < nodeY && y >= nodeY - 0.75 - 1e-9;
    }

    public static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
