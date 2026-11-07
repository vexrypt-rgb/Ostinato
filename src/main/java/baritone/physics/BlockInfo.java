/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

/**
 * Ostinato: what the engine needs to know about one block state, read once on the game thread (see
 * {@code baritone.physics.mc.TerrainCapture}) and shared, immutable, by every snapshot after. Boxes and coordinates
 * are relative to the cell.
 */
public final class BlockInfo {

    // flags
    public static final int AIR = 1;
    /** Collision shape is exactly the unit cube (vanilla's Shapes.block()). */
    public static final int FULL_CUBE = 1 << 1;
    public static final int CLIMBABLE = 1 << 2;
    public static final int SUPPRESSES_BOUNCE = 1 << 3;
    /** BlockTags.FALL_DAMAGE_RESETTING. */
    public static final int FALL_RESET = 1 << 4;
    /** BlockTags.FENCES; WALLS or a fence gate (the getOnPos special case). */
    public static final int FENCE = 1 << 5;
    public static final int WALL_OR_GATE = 1 << 6;
    /** Collision shape leaves the unit cube (BlockStateBase.hasLargeCollisionShape). */
    public static final int LARGE = 1 << 7;
    /** Collision depends on the entity or a block entity; read without it. */
    public static final int CONTEXT = 1 << 8;
    /** Blocks.WATER or Blocks.BUBBLE_COLUMN (their own speed factor is used as is). */
    public static final int WATER_BLOCK = 1 << 9;
    public static final int TNT = 1 << 10;
    /** An effect on what touches it that the engine does not model (portals, gateways, big dripleaf). */
    public static final int UNMODELLED = 1 << 11;
    /** Moving piston (only checked on BlockCollisions' edge cells, as vanilla does). */
    public static final int MOVING_PISTON = 1 << 12;
    /** BlockTags.CAN_GLIDE_THROUGH: not climbable while fall flying. */
    public static final int GLIDE_THROUGH = 1 << 13;

    // what landing on it does (Block.fallOn overrides)
    public static final byte FALL_NORMAL = 0, FALL_HAY = 1, FALL_HONEY = 2, FALL_SLIME = 3, FALL_NONE = 4, FALL_DRIPSTONE = 5;
    // what being inside / standing on it does to motion
    public static final byte EFFECT_NONE = 0, EFFECT_COBWEB = 1, EFFECT_POWDER_SNOW = 2, EFFECT_BERRY = 3, EFFECT_HONEY = 4,
            EFFECT_BUBBLE_UP = 5, EFFECT_BUBBLE_DOWN = 6, EFFECT_SLIME = 7;
    public static final byte FLUID_NONE = 0, FLUID_WATER = 1, FLUID_LAVA = 2;

    private static final double[] NONE = new double[0];
    public static final BlockInfo AIR_INFO = new BlockInfo(AIR, 0.6f, 1, 1, 0, 0, FALL_NORMAL, EFFECT_NONE, FLUID_NONE, 0, 0, false,
            -1, NONE, NONE, "air");

    public final int flags;
    public final float friction, speedFactor, jumpFactor, bounce, fallReduction;
    public final byte fall, effect, fluid;
    /** FluidState.getOwnHeight and getAmount (8 for a source or falling fluid); whether it is a falling column. */
    public final float fluidOwnHeight;
    public final int fluidAmount;
    public final boolean fluidFalling;
    /** max(block, fluid) explosion resistance; negative for air with no fluid (vanilla's Optional.empty()). */
    public final float resistance;
    /** Collision boxes, 6 doubles each (minX minY minZ maxX maxY maxZ). */
    public final double[] boxes;
    /** VoxelShape.getCoords(Y) of the collision shape, ascending; what step-up candidates are made from. */
    public final double[] yCoords;
    public final String name;

    public BlockInfo(int flags, float friction, float speedFactor, float jumpFactor, float bounce, float fallReduction,
                     byte fall, byte effect, byte fluid, float fluidOwnHeight, int fluidAmount, boolean fluidFalling,
                     float resistance, double[] boxes, double[] yCoords, String name) {
        this.flags = flags;
        this.friction = friction;
        this.speedFactor = speedFactor;
        this.jumpFactor = jumpFactor;
        this.bounce = bounce;
        this.fallReduction = fallReduction;
        this.fall = fall;
        this.effect = effect;
        this.fluid = fluid;
        this.fluidOwnHeight = fluidOwnHeight;
        this.fluidAmount = fluidAmount;
        this.fluidFalling = fluidFalling;
        this.resistance = resistance;
        this.boxes = boxes;
        this.yCoords = yCoords;
        this.name = name;
    }

    public boolean is(int flag) {
        return (flags & flag) != 0;
    }

    public boolean hasCollision() {
        return boxes.length != 0;
    }

    /** A fluid that is not a still source (it has a current vanilla pushes with). */
    public boolean flowing() {
        return fluid != FLUID_NONE && (fluidAmount < 8 || fluidFalling);
    }

    @Override
    public String toString() {
        return name;
    }

    /** For tests and hypothetical worlds: a solid block of these boxes with plain properties. */
    public static BlockInfo solid(String name, double... boxes) {
        return new Builder(name).boxes(boxes).build();
    }

    public static final class Builder {
        private final String name;
        private int flags;
        private float friction = 0.6f, speedFactor = 1, jumpFactor = 1, bounce, fallReduction;
        private byte fall = FALL_NORMAL, effect = EFFECT_NONE, fluid = FLUID_NONE;
        private float fluidOwnHeight;
        private int fluidAmount;
        private boolean fluidFalling;
        private float resistance = -1;
        private double[] boxes = NONE;

        public Builder(String name) {
            this.name = name;
        }

        public Builder flags(int f) { this.flags |= f; return this; }
        public Builder friction(float f) { this.friction = f; return this; }
        public Builder speedFactor(float f) { this.speedFactor = f; return this; }
        public Builder jumpFactor(float f) { this.jumpFactor = f; return this; }
        public Builder bounce(float b) { this.bounce = b; return this; }
        public Builder fallReduction(float r) { this.fallReduction = r; return this; }
        public Builder fall(byte f) { this.fall = f; return this; }
        public Builder effect(byte e) { this.effect = e; return this; }
        public Builder fluid(byte type, float ownHeight, int amount, boolean falling) {
            this.fluid = type;
            this.fluidOwnHeight = ownHeight;
            this.fluidAmount = amount;
            this.fluidFalling = falling;
            return this;
        }
        public Builder resistance(float r) { this.resistance = r; return this; }
        public Builder boxes(double... b) {
            if (b.length % 6 != 0) throw new IllegalArgumentException("boxes");
            this.boxes = b;
            return this;
        }

        public BlockInfo build() {
            int f = flags;
            if (boxes.length == 6 && boxes[0] == 0 && boxes[1] == 0 && boxes[2] == 0 && boxes[3] == 1 && boxes[4] == 1 && boxes[5] == 1) f |= FULL_CUBE;
            for (int i = 0; i < boxes.length; i += 6) {
                if (boxes[i] < 0 || boxes[i + 1] < 0 || boxes[i + 2] < 0 || boxes[i + 3] > 1 || boxes[i + 4] > 1 || boxes[i + 5] > 1) f |= LARGE;
            }
            if (boxes.length == 0 && fluid == FLUID_NONE && (f & ~AIR) == 0 && resistance < 0) f |= AIR;
            return new BlockInfo(f, friction, speedFactor, jumpFactor, bounce, fallReduction, fall, effect, fluid,
                    fluidOwnHeight, fluidAmount, fluidFalling, resistance, boxes, yCoordsOf(boxes), name);
        }

        /** The Y coordinates of a box-built shape (what vanilla's merged VoxelShape would partition on). */
        static double[] yCoordsOf(double[] boxes) {
            if (boxes.length == 0) return NONE;
            double[] ys = new double[boxes.length / 3];
            int n = 0;
            for (int i = 0; i < boxes.length; i += 6) {
                ys[n++] = boxes[i + 1];
                ys[n++] = boxes[i + 4];
            }
            java.util.Arrays.sort(ys, 0, n);
            int m = 0;
            for (int i = 0; i < n; i++) if (m == 0 || ys[i] != ys[m - 1]) ys[m++] = ys[i];
            return java.util.Arrays.copyOf(ys, m);
        }
    }

    /** For the capture: a block whose Y partition coordinates are vanilla's own (they can include grid points between boxes). */
    public BlockInfo withYCoords(double[] ys) {
        return new BlockInfo(flags, friction, speedFactor, jumpFactor, bounce, fallReduction, fall, effect, fluid,
                fluidOwnHeight, fluidAmount, fluidFalling, resistance, boxes, ys, name);
    }

    /** For the capture: this block with the collision shape it has at one position (offset or context-read shapes). */
    public BlockInfo withShape(double[] b, double[] ys) {
        int f = flags & ~(FULL_CUBE | LARGE);
        if (b.length == 6 && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1) f |= FULL_CUBE;
        for (int i = 0; i < b.length; i += 6) {
            if (b[i] < 0 || b[i + 1] < 0 || b[i + 2] < 0 || b[i + 3] > 1 || b[i + 4] > 1 || b[i + 5] > 1) f |= LARGE;
        }
        return new BlockInfo(f, friction, speedFactor, jumpFactor, bounce, fallReduction, fall, effect, fluid,
                fluidOwnHeight, fluidAmount, fluidFalling, resistance, b, ys, name);
    }

    /** Same block, same collision boxes: what a section copy may treat as one palette entry. */
    public boolean sameAs(BlockInfo o) {
        return o == this || o != null && o.name == name && o.flags == flags && java.util.Arrays.equals(o.boxes, boxes);
    }
}
