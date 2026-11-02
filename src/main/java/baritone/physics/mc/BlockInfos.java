/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics.mc;

import baritone.physics.BlockInfo;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Ostinato: reads a {@link BlockState} into the engine's {@link BlockInfo}, once per state. A state whose collision
 * shape depends on where it is (vanilla's dynamic-shape blocks: bamboo, pointed dripstone, sulfur spikes, scaffolding,
 * powder snow) or on a block entity (shulker box lids, moving pistons) is read again at each position; those that
 * also depend on who touches them are read with an empty collision context and flagged {@link BlockInfo#CONTEXT}.
 * Game thread only. Bounded by the number of block states; cleared on world change (tags can differ by server).
 */
final class BlockInfos {
    private static final double[] NONE = new double[0];
    private final IdentityHashMap<BlockState, BlockInfo> byState = new IdentityHashMap<>();

    void clear() {
        byState.clear();
    }

    /** Whether {@code state}'s collision shape must be read at its position. */
    static boolean perPosition(BlockState state) {
        Block b = state.getBlock();
        return b.hasDynamicShape() || b instanceof ShulkerBoxBlock || b instanceof MovingPistonBlock;
    }

    /** The state's info, its shape read at {@code pos} when {@link #perPosition}. */
    BlockInfo get(BlockState state, BlockGetter level, BlockPos pos) {
        BlockInfo info = byState.get(state);
        if (info == null) {
            info = read(state);
            byState.put(state, info);
        }
        if (!perPosition(state)) return info;
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        return info.withShape(boxes(shape), yCoords(shape));
    }

    private static BlockInfo read(BlockState state) {
        Block b = state.getBlock();
        FluidState fs = state.getFluidState();
        BlockInfo.Builder i = new BlockInfo.Builder(state.toString())
                .friction(b.getFriction()).speedFactor(b.getSpeedFactor()).jumpFactor(b.getJumpFactor())
                .bounce(b.getBounceRestitution()).fallReduction(b.getFallDistanceReduction())
                .resistance(state.isAir() && fs.isEmpty() ? -1 : Math.max(b.getExplosionResistance(), fs.getExplosionResistance()));
        int f = 0;
        if (state.is(BlockTags.CLIMBABLE)) f |= BlockInfo.CLIMBABLE;
        if (state.is(BlockTags.SUPPRESSES_BOUNCE)) f |= BlockInfo.SUPPRESSES_BOUNCE;
        if (state.is(BlockTags.FALL_DAMAGE_RESETTING)) f |= BlockInfo.FALL_RESET;
        if (state.is(BlockTags.FENCES)) f |= BlockInfo.FENCE;
        if (state.is(BlockTags.WALLS) || b instanceof FenceGateBlock) f |= BlockInfo.WALL_OR_GATE;
        if (state.is(BlockTags.CAN_GLIDE_THROUGH)) f |= BlockInfo.GLIDE_THROUGH;
        if (b == Blocks.WATER || b == Blocks.BUBBLE_COLUMN) f |= BlockInfo.WATER_BLOCK;
        if (b instanceof TntBlock) f |= BlockInfo.TNT;
        if (b instanceof NetherPortalBlock || b instanceof EndPortalBlock || b instanceof EndGatewayBlock || b instanceof BigDripleafBlock) {
            f |= BlockInfo.UNMODELLED;
        }
        if (b instanceof MovingPistonBlock) f |= BlockInfo.MOVING_PISTON | BlockInfo.CONTEXT;
        if (b instanceof ScaffoldingBlock || b instanceof PowderSnowBlock || b instanceof ShulkerBoxBlock) f |= BlockInfo.CONTEXT;
        i.flags(f);

        // Block.fallOn overrides
        if (b instanceof HayBlock) i.fall(BlockInfo.FALL_HAY);
        else if (b instanceof HoneyBlock) i.fall(BlockInfo.FALL_HONEY);
        else if (b instanceof SlimeBlock) i.fall(BlockInfo.FALL_SLIME);
        else if (b instanceof PowderSnowBlock) i.fall(BlockInfo.FALL_NONE);
        else if (b instanceof PointedDripstoneBlock && state.getValue(SpeleothemBlock.TIP_DIRECTION) == Direction.UP
                && state.getValue(SpeleothemBlock.THICKNESS) == SpeleothemThickness.TIP) i.fall(BlockInfo.FALL_DRIPSTONE);

        // entityInside / stepOn effects on motion
        if (b instanceof WebBlock) i.effect(BlockInfo.EFFECT_COBWEB);
        else if (b instanceof PowderSnowBlock) i.effect(BlockInfo.EFFECT_POWDER_SNOW);
        else if (b instanceof SweetBerryBushBlock) i.effect(BlockInfo.EFFECT_BERRY);
        else if (b instanceof HoneyBlock) i.effect(BlockInfo.EFFECT_HONEY);
        else if (b instanceof SlimeBlock) i.effect(BlockInfo.EFFECT_SLIME);
        else if (b instanceof BubbleColumnBlock) i.effect(state.getValue(BubbleColumnBlock.DRAG_DOWN) ? BlockInfo.EFFECT_BUBBLE_DOWN : BlockInfo.EFFECT_BUBBLE_UP);

        if (!fs.isEmpty()) {
            byte type = fs.is(FluidTags.WATER) ? BlockInfo.FLUID_WATER : fs.is(FluidTags.LAVA) ? BlockInfo.FLUID_LAVA : BlockInfo.FLUID_NONE;
            if (type != BlockInfo.FLUID_NONE) i.fluid(type, fs.getOwnHeight(), fs.getAmount(), fs.getValueOrElse(FlowingFluid.FALLING, false));
        }
        if (perPosition(state)) return i.build(); // shape filled in per position
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        BlockInfo info = i.boxes(boxes(shape)).build();
        return shape.isEmpty() ? info : info.withYCoords(yCoords(shape));
    }

    private static double[] boxes(VoxelShape shape) {
        if (shape.isEmpty()) return NONE;
        List<AABB> list = shape.toAabbs();
        double[] out = new double[list.size() * 6];
        for (int k = 0; k < list.size(); k++) {
            AABB a = list.get(k);
            out[k * 6] = a.minX;
            out[k * 6 + 1] = a.minY;
            out[k * 6 + 2] = a.minZ;
            out[k * 6 + 3] = a.maxX;
            out[k * 6 + 4] = a.maxY;
            out[k * 6 + 5] = a.maxZ;
        }
        return out;
    }

    private static double[] yCoords(VoxelShape shape) {
        if (shape.isEmpty()) return NONE;
        DoubleList ys = shape.getCoords(Direction.Axis.Y);
        return ys.toDoubleArray();
    }
}
