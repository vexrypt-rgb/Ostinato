package baritone.api.movement;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * Travel intent for {@link IMovementEngine}. Converts to a Baritone {@link Goal} when needed.
 * Follow goals keep an entity reference for Tungsten follow; Baritone falls back to {@link GoalNear}.
 */
public final class MovementGoal {

    public enum Kind {
        BLOCK,
        GET_TO_BLOCK,
        XZ,
        Y,
        NEAR,
        FOLLOW_ENTITY,
        CUSTOM
    }

    private final Kind kind;
    private final BlockPos block;
    private final int x;
    private final int y;
    private final int z;
    private final int range;
    private final Entity entity;
    private final double maintainDistance;
    private final Goal custom;

    private MovementGoal(Kind kind, BlockPos block, int x, int y, int z, int range,
                         Entity entity, double maintainDistance, Goal custom) {
        this.kind = kind;
        this.block = block;
        this.x = x;
        this.y = y;
        this.z = z;
        this.range = range;
        this.entity = entity;
        this.maintainDistance = maintainDistance;
        this.custom = custom;
    }

    public static MovementGoal block(BlockPos pos) {
        return new MovementGoal(Kind.BLOCK, pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), 0, null, 0, null);
    }

    public static MovementGoal getToBlock(BlockPos pos) {
        return new MovementGoal(Kind.GET_TO_BLOCK, pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), 0, null, 0, null);
    }

    public static MovementGoal xz(int x, int z) {
        return new MovementGoal(Kind.XZ, null, x, 0, z, 0, null, 0, null);
    }

    public static MovementGoal y(int y) {
        return new MovementGoal(Kind.Y, null, 0, y, 0, 0, null, 0, null);
    }

    public static MovementGoal near(BlockPos pos, int range) {
        return new MovementGoal(Kind.NEAR, pos.immutable(), pos.getX(), pos.getY(), pos.getZ(), range, null, 0, null);
    }

    public static MovementGoal follow(Entity entity, double maintainDistance) {
        return new MovementGoal(Kind.FOLLOW_ENTITY, null, 0, 0, 0, 0, entity, maintainDistance, null);
    }

    public static MovementGoal custom(Goal goal) {
        return new MovementGoal(Kind.CUSTOM, null, 0, 0, 0, 0, null, 0, goal);
    }

    public Kind getKind() {
        return kind;
    }

    public BlockPos getBlock() {
        return block;
    }

    public Entity getEntity() {
        return entity;
    }

    public double getMaintainDistance() {
        return maintainDistance;
    }

    public Goal getCustomGoal() {
        return custom;
    }

    /**
     * Best-effort conversion to a Baritone goal. Follow uses {@link GoalNear} on the entity's feet.
     */
    public Goal toBaritoneGoal() {
        return switch (kind) {
            case BLOCK -> new GoalBlock(block);
            case GET_TO_BLOCK -> new GoalGetToBlock(block);
            case XZ -> new GoalXZ(x, z);
            case Y -> new GoalYLevel(y);
            case NEAR -> new GoalNear(block, range);
            case FOLLOW_ENTITY -> {
                if (entity == null) {
                    yield null;
                }
                BlockPos feet = entity.blockPosition();
                int r = Math.max(1, (int) Math.ceil(maintainDistance));
                yield new GoalNear(feet, r);
            }
            case CUSTOM -> custom;
        };
    }

    public BlockPos primaryBlock() {
        if (block != null) {
            return block;
        }
        if (kind == Kind.FOLLOW_ENTITY && entity != null) {
            return entity.blockPosition();
        }
        if (kind == Kind.XZ) {
            return new BlockPos(x, 64, z);
        }
        if (kind == Kind.Y) {
            return new BlockPos(0, y, 0);
        }
        if (custom instanceof GoalBlock gb) {
            return new BlockPos(gb.x, gb.y, gb.z);
        }
        return null;
    }

    @Override
    public String toString() {
        return "MovementGoal{kind=" + kind
                + ", block=" + block
                + ", xz=(" + x + "," + z + ")"
                + ", y=" + y
                + ", range=" + range
                + ", entity=" + (entity != null ? entity.getId() : null)
                + ", dist=" + maintainDistance
                + ", custom=" + custom
                + "}";
    }
}
