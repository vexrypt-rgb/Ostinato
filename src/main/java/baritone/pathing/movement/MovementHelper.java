/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.pathing.precompute.Ternary;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static baritone.api.utils.RotationUtils.DEG_TO_RAD_F;
import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;
import static baritone.pathing.precompute.Ternary.*;

/**
 * Static helpers for cost calculation
 *
 * @author leijurv
 */
public interface MovementHelper extends ActionCosts, Helper {

    static boolean avoidBreaking(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (bsi.get0(x, y + 1, z).getBlock() instanceof EndPortalFrameBlock) {
            return true;
        }
        if (AltoClefSettings.getInstance().shouldAvoidBreaking(x, y, z)) return true;

        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return true;
        }
        Block b = state.getBlock();
        return Baritone.settings().blocksToDisallowBreaking.value.contains(b)
                || b instanceof EndPortalFrameBlock
                || b == Blocks.ICE // ice becomes water, and water can mess up the path
                // call context.get directly with x,y,z. no need to make 5 new BlockPos for no reason
                || avoidAdjacentBreaking(bsi, x, y + 1, z, true)
                || avoidAdjacentBreaking(bsi, x + 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x - 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x, y, z + 1, false)
                || avoidAdjacentBreaking(bsi, x, y, z - 1, false);
    }

    static boolean avoidAdjacentBreaking(BlockStateInterface bsi, int x, int y, int z, boolean directlyAbove) {
        // returns true if you should avoid breaking a block that's adjacent to this one (e.g. lava that will start flowing if you give it a path)
        // this is only called for north, south, east, west, and up. this is NOT called for down.
        // we assume that it's ALWAYS okay to break the block thats ABOVE liquid
        BlockState state = bsi.get0(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove // it is fine to mine a block that has a falling block directly above, this (the cost of breaking the stacked fallings) is included in cost calculations
                // therefore if directlyAbove is true, we will actually ignore if this is falling
                && block instanceof FallingBlock // obviously, this check is only valid for falling blocks
                && Baritone.settings().avoidUpdatingFallingBlocks.value // and if the setting is enabled
                && FallingBlock.isFree(bsi.get0(x, y - 1, z))) { // and if it would fall (i.e. it's unsupported)
            return true; // dont break a block that is adjacent to unsupported gravel because it can cause really weird stuff
        }
        // only pure liquids for now
        // waterlogged blocks can have closed bottom sides and such
        if (block instanceof LiquidBlock) {
            if (directlyAbove || Baritone.settings().strictLiquidCheck.value) {
                return true;
            }
            int level = state.getValue(LiquidBlock.LEVEL);
            if (level == 0) {
                return true; // source blocks like to flow horizontally
            }
            // everything else will prefer flowing down
            return !(bsi.get0(x, y - 1, z).getBlock() instanceof LiquidBlock); // assume everything is in a static state
        }
        return !state.getFluidState().isEmpty();
    }

    static boolean canWalkThrough(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkThrough(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkThrough(bsi, x, y, z, bsi.get0(x, y, z));
    }

    // The planner's answers below must match the executor's (the bsi overload): a path through a cell the executor
    // will not walk through leaves it trying to mine air.
    static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        if (context.swimThroughLava && state.getBlock() == Blocks.LAVA) {
            return context.get(x, y + 1, z).getFluidState().isEmpty();
        }
        if (context.avoidsWalkThrough(x, y, z)) {
            return false;
        }
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, state);
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return canWalkThrough(context, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (state.getBlock() == Blocks.LAVA && AltoClefSettings.getInstance().canSwimThroughLava()) {
            return bsi.get0(x, y + 1, z).getFluidState().isEmpty(); // the surface only
        }
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(x, y, z)) {
            return false;
        }
        Ternary canWalkThrough = canWalkThroughBlockState(state);
        if (canWalkThrough == YES) {
            return true;
        }
        if (canWalkThrough == NO) {
            return false;
        }
        return canWalkThroughPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.COBWEB || block == Blocks.END_PORTAL || block == Blocks.COCOA || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN || block instanceof ShulkerBoxBlock || block instanceof SlabBlock || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock) {
            return NO;
        }
        if (block == Blocks.BIG_DRIPLEAF) {
            return NO;
        }
        if (block == Blocks.POWDER_SNOW) {
            return NO;
        }
        if (Baritone.settings().blocksToAvoid.value.contains(block)) {
            return NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // TODO this assumes that all doors in all mods are openable
            if (block == Blocks.IRON_DOOR) {
                return NO;
            }
            return YES;
        }
        if (block instanceof CarpetBlock) {
            return MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // snow layers cached as the top layer of a packed chunk have no metadata, we can't make a decision based on their depth here
            // it would otherwise make long distance pathing through snowy biomes impossible
            return MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getType().getAmount(fluidState) != 8) {
                return NO;
            } else {
                return MAYBE;
            }
        }
        if (block instanceof CauldronBlock) {
            return NO;
        }
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    static boolean canWalkThroughPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            return canWalkOn(bsi, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            // the check in BlockSnow.isPassable is layers < 5
            // while actually, we want < 3 because 3 or greater makes it impassable in a 2 high ceiling
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            // ok, it's low enough we could walk through it, but is it supported?
            return canWalkOn(bsi, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(x, y, z, state, bsi)) {
                return false;
            }
            // Everything after this point has to be a special case as it relies on the water not being flowing, which means a special case is needed.
            if (Baritone.settings().assumeWalkOnWater.value) {
                return false;
            }

            BlockState up = bsi.get0(x, y + 1, z);
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof LilyPadBlock) {
                return false;
            }
            return fluidState.getType() instanceof WaterFluid;
        }

        return state.isPathfindable(PathComputationType.LAND);
    }

    static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return YES;
        }
        if (block instanceof SnowLayerBlock && state.getValue(SnowLayerBlock.LAYERS) == 1) {
            return YES; // Ostinato: one layer has no collision at all: a jump takes off and lands through it like air
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return NO;
        }
        // door, fence gate, liquid, trapdoor have been accounted for, nothing else uses the world or pos parameters
        // at least in 1.12.2 vanilla, that is.....
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    /**
     * canWalkThrough but also won't impede movement at all. so not including doors or fence gates (we'd have to right click),
     * not including water, and not including ladders or vines or cobwebs (they slow us down)
     */
    static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context, x, y, z, context.get(x, y, z));
    }

    static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        if (context.avoidsWalkThrough(x, y, z)) {
            return false;
        }
        return context.precomputedData.fullyPassable(context.bsi, x, y, z, state);
    }

    static boolean fullyPassable(IPlayerContext ctx, BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        Ternary fullyPassable = fullyPassableBlockState(state);
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(pos)) {
            return false;
        }
        if (fullyPassable == YES) {
            return true;
        }
        if (fullyPassable == NO) {
            return false;
        }
        return state.isPathfindable(PathComputationType.LAND);
    }

    /**
     * params retained for backwards compatibility
     */
    static boolean fullyPassablePosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        return state.isPathfindable(PathComputationType.LAND);
    }

    static boolean isReplaceable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
        // the only case to deal with is snow
        /*
         *  public boolean isReplaceable(IBlockAccess worldIn, BlockPos pos)
         *     {
         *         return ((Integer)worldIn.getBlockState(pos).getValue(LAYERS)).intValue() == 1;
         *     }
         */
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            // early return for common cases hehe
            return true;
        }
        if (block instanceof SnowLayerBlock) {
            // as before, default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.canBeReplaced();
    }

    static boolean isDoorPassable(IPlayerContext ctx, BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos)) {
            return false;
        }

        BlockState state = BlockStateInterface.get(ctx, doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return true;
        }

        return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
    }

    static boolean isGatePassable(IPlayerContext ctx, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) {
            return false;
        }

        BlockState state = BlockStateInterface.get(ctx, gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }

        return state.getValue(FenceGateBlock.OPEN);
    }

    static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState, BlockPos playerPos, BooleanProperty propertyOpen) {
        if (playerPos.equals(blockPos)) {
            return false;
        }

        Direction.Axis facing = blockState.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = blockState.getValue(propertyOpen);

        Direction.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
            playerFacing = Direction.Axis.Z;
        } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
            playerFacing = Direction.Axis.X;
        } else {
            return true;
        }

        return (facing == playerFacing) == open;
    }

    public static boolean avoidWalkingInto(BlockState state) {
        Block block = state.getBlock();
        return !state.getFluidState().isEmpty()
                || (block == Blocks.MAGMA_BLOCK && !Baritone.settings().allowWalkOnMagmaBlocks.value)
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof BaseFireBlock
                || block instanceof EndPortalFrameBlock
                || block == Blocks.END_PORTAL
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    /**
     * Can I walk on this block without anything weird happening like me falling
     * through? Includes water because we know that we automatically jump on
     * water
     * <p>
     * If changing something in this function remember to also change it in precomputed data
     *
     * @param bsi   Block state provider
     * @param x     The block's x position
     * @param y     The block's y position
     * @param z     The block's z position
     * @param state The state of the block at the specified location
     * @return Whether or not the specified block can be walked on
     */
    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (AltoClefSettings.getInstance().canWalkOnForce(x, y, z)) {
            return true;
        }
        Ternary canWalkOn = canWalkOnBlockState(state);
        if (canWalkOn == YES) {
            return true;
        }
        if (canWalkOn == NO) {
            return false;
        }
        return canWalkOnPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkOnBlockState(BlockState state) {
        Block block = state.getBlock();
        if (Baritone.settings().blocksToAvoidStandingOn.value.contains(block)) {
            return NO;
        }
        // Extra blocks we may want to walk on.
        if (block instanceof EndPortalFrameBlock) {
            return YES;
        }
        if (block == Blocks.END_PORTAL && AltoClefSettings.getInstance().isCanWalkOnEndPortal()) {
            return YES;
        }
        if (block instanceof FallingBlock && Baritone.settings().pitfallAvoidance.value) {
            return MAYBE; // depends on what it rests on, see canWalkOnPosition
        }
        if (isBlockNormalCube(state) && (block != Blocks.MAGMA_BLOCK || Baritone.settings().allowWalkOnMagmaBlocks.value) && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return YES;
        }
        // A lantern on the floor holds a player up. As NO, standing on one read as standing on air
        // (seen on soul lanterns in the nether). It is a low floor: see isBottomSlab for what that rules out.
        // Ported from xiaoka6666/baritone (26.3 branch), widened from the soul lantern to every lantern.
        if (isStandingLantern(state) || block instanceof AzaleaBlock) {
            return YES;
        }
        if (block == Blocks.LADDER || (isClimbable(block) && Baritone.settings().allowVines.value)) { // TODO reconsider this
            return YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND) {
            return YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return YES;
        }
        if (block instanceof StairBlock) {
            return YES;
        }
        if (block instanceof net.minecraft.world.level.block.IronBarsBlock) { // iron bars and glass panes: flat full-height top, even a lone post
            return YES;
        }
        if (isWater(state)) {
            return MAYBE;
        }
        if (MovementHelper.isLava(state) && Baritone.settings().assumeWalkOnLava.value) {
            return MAYBE;
        }
        if (block instanceof SlabBlock) {
            if (!Baritone.settings().allowWalkOnBottomSlab.value) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return YES;
                }
                return NO;
            }
            return YES;
        }
        // Ostinato: fence, wall and closed gate tops (1.5 high) hold a player whose head then reaches a third cell up;
        // canWalkOnPosition checks that cell.
        if (isTallTop(state)) {
            return MAYBE;
        }
        // Anything else with a flat top a player is held up by (candles, sea pickles, flower pots, heads, upright end
        // rods and chains): its real collision shape decides. A low one is a low floor, see isBottomSlab.
        if (!isHazardFloor(block) && holdsPlayer(collisionTop(state), collisionWidth(state))) {
            return YES;
        }
        return NO;
    }

    /** Floors with a collision top that still must not be stood on. */
    static boolean isHazardFloor(Block block) {
        return block instanceof CactusBlock || block instanceof CampfireBlock || block instanceof PointedDripstoneBlock
                || block == Blocks.POWDER_SNOW || block instanceof ScaffoldingBlock || block instanceof BaseRailBlock
                || block instanceof FenceBlock || block instanceof WallBlock || block instanceof FenceGateBlock; // tall tops: isTallTop
    }

    /** Ostinato: a fence, wall or closed gate, whose collision top is 1.5 high. */
    public static boolean isTallTop(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof FenceGateBlock) return !state.getValue(FenceGateBlock.OPEN);
        return b instanceof FenceBlock || b instanceof WallBlock;
    }

    /**
     * Ostinato: whether a walk between a fence/wall top (1.5 high) and the other floor {@code flat} of the same node is
     * a step: the other floor must be within the 0.6 step height, so a full-height top (cubed level 11's rope bridges
     * knot wool blocks between fences). Mid-step the head on the fence side reaches the third cell over both columns.
     */
    public static boolean stepsToTallTop(CalculationContext context, BlockState flat, int x, int z, int destX, int y, int destZ) {
        return collisionTop(flat) >= 0.9 && collisionTop(flat) <= 1.0 && collisionWidth(flat) >= 0.99
                && canWalkThrough(context, x, y + 2, z) && canWalkThrough(context, destX, y + 2, destZ);
    }

    /** Ostinato: a player standing on a top this high has feet in the cell above and head two cells above that. */
    static int cellsAboveNeeded(double top) {
        return top > 1.0 ? 3 : 2;
    }

    /** Top (0..1.5) of a state's own collision shape, -1 when it has none. */
    public static double collisionTop(BlockState state) {
        net.minecraft.world.phys.shapes.VoxelShape s = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return s.isEmpty() ? -1 : s.max(net.minecraft.core.Direction.Axis.Y);
    }

    /** Bottom (0..1) of a state's own collision shape, 2 when it has none. */
    public static double collisionBottom(BlockState state) {
        net.minecraft.world.phys.shapes.VoxelShape s = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return s.isEmpty() ? 2 : s.min(net.minecraft.core.Direction.Axis.Y);
    }

    /** The narrower horizontal extent of a state's collision shape, 0 when it has none. */
    public static double collisionWidth(BlockState state) {
        net.minecraft.world.phys.shapes.VoxelShape s = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (s.isEmpty()) return 0;
        net.minecraft.world.phys.AABB b = s.bounds();
        return Math.min(b.maxX - b.minX, b.maxZ - b.minZ);
    }

    /**
     * Ostinato: the bounds of the boxes of a state's collision shape that reach its top, null when it has none. A foot
     * lands on these: a brewing stand's bounds span its base plate, but its top is the 2/16 rod (cubed level 1).
     */
    public static net.minecraft.world.phys.AABB collisionTopBounds(BlockState state) {
        net.minecraft.world.phys.shapes.VoxelShape s = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (s.isEmpty()) return null;
        double top = s.max(net.minecraft.core.Direction.Axis.Y);
        net.minecraft.world.phys.AABB out = null;
        for (net.minecraft.world.phys.AABB b : s.toAabbs()) {
            if (b.maxY >= top - 1e-6) out = out == null ? b : out.minmax(b);
        }
        return out;
    }

    /** The narrower horizontal extent of {@link #collisionTopBounds}, 0 when there is no shape. */
    public static double collisionTopWidth(BlockState state) {
        net.minecraft.world.phys.AABB b = collisionTopBounds(state);
        return b == null ? 0 : Math.min(b.maxX - b.minX, b.maxZ - b.minZ);
    }

    /**
     * Whether a collision top this high and this wide holds a player standing in the block above. Below a quarter
     * (carpet, plates, snow layers) the player stands in this block's own cell instead; above one (fences, walls)
     * the player's feet are a cell up, which the node grid does not model.
     */
    static boolean holdsPlayer(double top, double width) {
        return top >= 0.25 && top <= 1.0 && width >= 0.12;
    }

    /** A top this low leaves a jump short of a full block above, like a bottom slab (candle 6/16, skull 8/16). */
    static boolean isLowTop(double top) {
        return top >= 0.25 && top <= 0.5625;
    }

    static boolean canWalkOnPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isWater(state)) {
            // since this is called literally millions of times per second, the benefit of not allocating millions of useless "pos.above()"
            // BlockPos s that we'd just garbage collect immediately is actually noticeable. I don't even think its a decrease in readability
            BlockState upState = bsi.get0(x, y + 1, z);
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            if (MovementHelper.isFlowing(x, y, z, state, bsi) || upState.getFluidState().getType() == Fluids.FLOWING_WATER) {
                // the only scenario in which we can walk on flowing water is if it's under still water with jesus off
                return isWater(upState) && !Baritone.settings().assumeWalkOnWater.value;
            }
            // if assumeWalkOnWater is on, we can only walk on water if there isn't water above it
            // if assumeWalkOnWater is off, we can only walk on water if there is water above it
            return isWater(upState) ^ Baritone.settings().assumeWalkOnWater.value;
        }

        if (isTallTop(state)) { // Ostinato: standing 1.5 up, the head is in the third cell
            return canWalkThrough(bsi, x, y + cellsAboveNeeded(1.5), z);
        }
        if (block instanceof FallingBlock) {
            // pitfall: sand/gravel/concrete powder resting on something without collision (air, open gate, sign...) drops out from under us
            BlockPos.MutableBlockPos below = bsi.isPassableBlockPos.set(x, y - 1, z);
            // Ostinato: an anvil is a falling block that is not a full cube; its shape decides like any other low top
            boolean holds = isBlockNormalCube(state) || holdsPlayer(collisionTop(state), collisionWidth(state));
            return holds && !bsi.get0(x, y - 1, z).getCollisionShape(bsi.access, below).isEmpty();
        }
        if (MovementHelper.isLava(state) && !MovementHelper.isFlowing(x, y, z, state, bsi) && Baritone.settings().assumeWalkOnLava.value) { // if we get here it means that assumeWalkOnLava must be true, so put it last
            return true;
        }

        return false; // If we don't recognise it then we want to just return false to be safe.
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        if (context.forcesWalkOn(x, y, z)) {
            return true;
        }
        return context.precomputedData.canWalkOn(context.bsi, x, y, z, state);
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z) {
        return canWalkOn(context, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos, BlockState state) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z, state);
    }

    static boolean canWalkOn(IPlayerContext ctx, BlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkOn(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canUseFrostWalker(CalculationContext context, BlockState state) {
        return context.frostWalker != 0
                && state == FrostedIceBlock.meltsInto()
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    static boolean canUseFrostWalker(IPlayerContext ctx, BlockPos pos) {
        boolean hasFrostWalker = false;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = ctx
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    hasFrostWalker = true;
                    break OUTER;
                }
            }
        }
        BlockState state = BlockStateInterface.get(ctx, pos);
        return hasFrostWalker
                && state == FrostedIceBlock.meltsInto()
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * If movements make us stand/walk on this block, will it have a top to walk on?
     */
    static boolean mustBeSolidToWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isClimbable(block)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            // used for frostwalker so only includes blocks where we are still on ground when leaving them to any side
            if (block instanceof SlabBlock) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairBlock) {
                if (state.getValue(StairBlock.HALF) == Half.TOP) {
                    return true;
                }
                StairsShape shape = state.getValue(StairBlock.SHAPE);
                if (shape == StairsShape.INNER_LEFT || shape == StairsShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (context.assumeWalkOnWater) {
                return false;
            }
            Block blockAbove = context.getBlock(x, y + 1, z);
            if (blockAbove instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z) {
        return canPlaceAgainst(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, BlockPos pos) {
        return canPlaceAgainst(bsi, pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean canPlaceAgainst(IPlayerContext ctx, BlockPos pos) {
        return canPlaceAgainst(new BlockStateInterface(ctx), pos);
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (AltoClefSettings.getInstance().shouldAvoidPlacingAt(x, y, z)) return false;

        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return false;
        }
        // can we look at the center of a side face of this block and likely be able to place?
        // (thats how this check is used)
        // therefore dont include weird things that we technically could place against (like carpet) but practically can't
        return isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock;
    }

    /**
     * Can we climb up this block by pressing space while inside it?
     * Also doubles as "If I start a movement on this, can weird things happen?"
     * because movements can end/start on these blocks despite them not being canWalkOn.
     */
    static boolean isClimbable(Block block) {
        return block == Blocks.LADDER
            || block == Blocks.VINE
            || block == Blocks.WEEPING_VINES
            || block == Blocks.WEEPING_VINES_PLANT
            || block == Blocks.TWISTING_VINES
            || block == Blocks.TWISTING_VINES_PLANT;
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
        // per-search memo, ported from Soprano's MovementHelper (https://github.com/AverWasTaken/soprano, LGPL-3.0)
        long[] keys = includeFalling ? context.miningKeysFalling : context.miningKeys;
        if (keys == null || context.miningOwner != Thread.currentThread()) {
            return getMiningDurationTicks0(context, x, y, z, state, includeFalling);
        }
        // y is shifted so it's never negative, so the all-ones key (the empty marker) would need y=4095
        long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y - context.minY) & 0xFFF);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - context.miningCacheBits));
        double[] vals = includeFalling ? context.miningValsFalling : context.miningVals;
        if (keys[slot] == key) {
            return vals[slot];
        }
        double result = getMiningDurationTicks0(context, x, y, z, state, includeFalling);
        keys[slot] = key;
        vals[slot] = result;
        return result;
    }

    static double getMiningDurationTicks0(CalculationContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
        Block block = state.getBlock();
        if (!canWalkThrough(context, x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                return COST_INF;
            }
            if (context.avoidsWalkThrough(x, y, z)) {
                return COST_INF; // kept out of, not walled off: digging does not open it
            }
            double mult = context.breakCostMultiplierAt(x, y, z, state); // asks the break avoiders, as does avoidBreaking
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (avoidBreaking(context.bsi, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = context.toolSet.getStrVsBlock(state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            double result = 1 / strVsBlock;
            result += context.breakBlockAdditionalCost;
            result *= mult;
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += getMiningDurationTicks(context, x, y + 1, z, above, true);
                }
            }
            return result;
        }
        return 0; // we won't actually mine it, so don't check fallings above
    }

    /**
     * A floor about half a block up: a bottom slab, or a lantern standing on the ground (9/16). A jump from one
     * does not reach a full block above, and a full block beside it is a step, not a jump.
     */
    static boolean isBottomSlab(BlockState state) {
        return (state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM)
                || isStandingLantern(state)
                || (!(state.getBlock() instanceof SlabBlock) && !isBlockNormalCube(state) && canWalkOnBlockState(state) == YES
                    && !isHazardFloor(state.getBlock()) && isLowTop(collisionTop(state)));
    }

    /** A lantern on the ground. One hanging has the block it hangs from where the player would stand. */
    static boolean isStandingLantern(BlockState state) {
        return state.getBlock() instanceof LanternBlock && !state.getValue(LanternBlock.HANGING);
    }

    /**
     * AutoTool for a specific block
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     */
    static void switchToBestToolFor(IPlayerContext ctx, BlockState b) {
        switchToBestToolFor(ctx, b, new ToolSet(ctx.player()), BaritoneAPI.getSettings().preferSilkTouch.value);
    }

    /**
     * AutoTool for a specific block with precomputed ToolSet data
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     * @param ts  previously calculated ToolSet
     */
    static void switchToBestToolFor(IPlayerContext ctx, BlockState b, ToolSet ts, boolean preferSilkTouch) {
        if (Baritone.settings().autoTool.value && !Baritone.settings().assumeExternalAutoTool.value) {
            ctx.player().getInventory().setSelectedSlot(ts.getBestSlot(b.getBlock(), preferSilkTouch));
        }
    }

    /**
     * Ostinato: where in a node's cell to stand. The centre, unless nothing holds a player there: an open trapdoor's
     * panel holds only the side of the cell it is on (cubed level 1's trapdoor step walked to the centre of a node held
     * by a north-facing panel, 0.01 past its edge, and fell every time). Then the point nearest the centre whose hitbox
     * rests 0.1 onto a top level with the feet and clips nothing at body height.
     */
    static Vec3 footing(IPlayerContext ctx, BlockPos pos) {
        Vec3 c = VecUtils.getBlockPosCenter(pos);
        net.minecraft.world.level.Level w = ctx.world();
        if (w == null) return c;
        int y = pos.getY();
        List<net.minecraft.world.phys.AABB> floor = new ArrayList<>(), body = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    m.set(pos.getX() + dx, y + dy, pos.getZ() + dz);
                    for (net.minecraft.world.phys.AABB b : w.getBlockState(m).getCollisionShape(w, m).toAabbs()) {
                        net.minecraft.world.phys.AABB a = b.move(m);
                        if (a.maxY > y - 0.6 && a.maxY <= y + 0.6) { // down to a bottom slab, up to a fence top
                            floor.add(a);
                        } else if (a.maxY > y + 0.6 && a.minY < y + 1.8) {
                            body.add(a);
                        }
                    }
                }
            }
        }
        if (floor.isEmpty() || rests(floor, body, c.x, c.z, 1e-3)) return c;
        double bx = c.x, bz = c.z, best = Double.MAX_VALUE;
        for (int i = 1; i < 50; i++) {
            for (int k = 1; k < 50; k++) {
                double px = pos.getX() + i * 0.02, pz = pos.getZ() + k * 0.02;
                double d = (px - c.x) * (px - c.x) + (pz - c.z) * (pz - c.z);
                if (d < best && rests(floor, body, px, pz, 0.1)) {
                    best = d;
                    bx = px;
                    bz = pz;
                }
            }
        }
        return new Vec3(bx, c.y, bz);
    }

    /** Whether a hitbox centred at (px, pz) overlaps a floor box by {@code min} (or its whole extent) and no body box. */
    private static boolean rests(List<net.minecraft.world.phys.AABB> floor, List<net.minecraft.world.phys.AABB> body, double px, double pz, double min) {
        double h = 0.3;
        for (net.minecraft.world.phys.AABB a : body) {
            if (a.minX < px + h && a.maxX > px - h && a.minZ < pz + h && a.maxZ > pz - h) return false;
        }
        for (net.minecraft.world.phys.AABB a : floor) {
            double ox = Math.min(px + h, a.maxX) - Math.max(px - h, a.minX);
            double oz = Math.min(pz + h, a.maxZ) - Math.max(pz - h, a.minZ);
            if (ox >= Math.min(min, (a.maxX - a.minX) * 0.9) && oz >= Math.min(min, (a.maxZ - a.minZ) * 0.9)) return true;
        }
        return false;
    }

    static void moveTowards(IPlayerContext ctx, MovementState state, BlockPos pos) {
        state.setTarget(new MovementTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        footing(ctx, pos),
                        ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                false
        )).setInput(Input.MOVE_FORWARD, true);
    }

    /**
     * Ostinato: walk to a run-up start (dx, dz) away. Walking on while the look still turns orbits a close target
     * (practice parkour: circled a start 0.6 away for 5 s), so only walk once roughly facing it.
     */
    static void walkToSpot(IPlayerContext ctx, MovementState state, double dx, double dz) {
        float to = (float) Math.toDegrees(Math.atan2(-dx, dz));
        state.setTarget(new MovementTarget(new Rotation(to, ctx.playerRotations().getPitch()), true));
        double d2 = dx * dx + dz * dz;
        net.minecraft.world.phys.Vec3 m = ctx.player().getDeltaMovement();
        boolean facing = Math.abs(Mth.wrapDegrees(to - ctx.player().getYRot())) < (d2 > 1 ? 60 : 30);
        if (facing && (d2 > 0.05 || Math.abs(m.x) + Math.abs(m.z) < 0.05)) {
            state.setInput(Input.MOVE_FORWARD, true);
        }
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, float idealYaw) {
        MovementOption.getOptions(
                Mth.sin(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Mth.cos(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Baritone.settings().allowSprint.value
        ).min(Comparator.comparing(option -> option.distanceToSq(
                Mth.sin(idealYaw * DEG_TO_RAD_F),
                Mth.cos(idealYaw * DEG_TO_RAD_F)
        ))).ifPresent(selection -> selection.setInputs(state));
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                footing(ctx, dest),
                ctx.playerRotations()
        ).getYaw();
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    static void moveTowardsWithSlightRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                footing(ctx, dest),
                ctx.playerRotations()
        ).getYaw();
        float distance = Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), idealYaw) % 45f;
        float newYaw = distance > 0f ?
                distance > 22.5f ? distance - 45f : distance :
                distance < -22.5f ? distance + 45f : distance;
        state.setTarget(new MovementTarget(new Rotation(
                ctx.playerRotations().getYaw() - newYaw,
                ctx.playerRotations().getPitch()
        ), true));
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    /**
     * Returns whether or not the specified block is
     * water, regardless of whether or not it is flowing.
     *
     * @param state The block state
     * @return Whether or not the block is water
     */
    static boolean isWater(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    /**
     * Returns whether or not the block at the specified pos is
     * water, regardless of whether or not it is flowing.
     *
     * @param ctx The player context
     * @param bp  The block pos
     * @return Whether or not the block is water
     */
    static boolean isWater(IPlayerContext ctx, BlockPos bp) {
        return isWater(BlockStateInterface.get(ctx, bp));
    }

    static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    /**
     * Returns whether or not the specified pos has a liquid
     *
     * @param ctx The player context
     * @param p   The pos
     * @return Whether or not the block is a liquid
     */
    static boolean isLiquid(IPlayerContext ctx, BlockPos p) {
        return isLiquid(BlockStateInterface.get(ctx, p));
    }

    static boolean isLiquid(BlockState blockState) {
        return !blockState.getFluidState().isEmpty();
    }

    static boolean possiblyFlowing(BlockState state) {
        FluidState fluidState = state.getFluidState();
        return fluidState.getType() instanceof FlowingFluid
                && fluidState.getType().getAmount(fluidState) != 8;
    }

    static boolean isFlowing(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluidState.getType().getAmount(fluidState) != 8) {
            return true;
        }
        return possiblyFlowing(bsi.get0(x + 1, y, z))
                || possiblyFlowing(bsi.get0(x - 1, y, z))
                || possiblyFlowing(bsi.get0(x, y, z + 1))
                || possiblyFlowing(bsi.get0(x, y, z - 1));
    }

    /**
     * Ostinato: per-state memo of {@link #isBlockNormalCube}, indexed by block-state id (0 = not computed, 1 = no, 2 = yes).
     * The answer only depends on the state, and the uncached path goes through vanilla's Guava shape cache
     * ({@code Block.isShapeFullBlock}), which profiling showed at ~17% of planning time and most of its allocation.
     * Racy writes are benign: every thread computes the same value (same scheme as PrecomputedData).
     */
    final class NormalCubeMemo {
        static final byte[] MEMO = new byte[Block.BLOCK_STATE_REGISTRY.size()];

        private NormalCubeMemo() {}
    }

    static boolean isBlockNormalCube(BlockState state) {
        byte[] memo = NormalCubeMemo.MEMO;
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0 || id >= memo.length) {
            return isBlockNormalCube0(state);
        }
        byte v = memo[id];
        if (v == 0) {
            v = isBlockNormalCube0(state) ? (byte) 2 : (byte) 1;
            memo[id] = v;
        }
        return v == 2;
    }

    // Ostinato: the original Baritone isBlockNormalCube body, unchanged, now behind the memo above.
    private static boolean isBlockNormalCube0(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooStalkBlock
                || block instanceof MovingPistonBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            // if we can't get the collision shape, assume it's bad and add to blocksToAvoid
        }
        return false;
    }

    static PlaceResult attemptToPlaceABlock(MovementState state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Optional<Rotation> direct = RotationUtils.reachable(ctx, placeAt, wouldSneak); // we assume that if there is a block there, it must be replacable
        boolean found = false;
        if (direct.isPresent()) {
            state.setTarget(new MovementTarget(direct.get(), true));
            found = true;
        }
        for (int i = 0; i < 5; i++) {
            BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
            if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(false, placeAt.getX(), placeAt.getY(), placeAt.getZ())) { // get ready to place a throwaway block
                    Helper.HELPER.logDebug("bb pls get me some blocks. dirt, netherrack, cobble");
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                Rotation place = RotationUtils.calcRotationFromVec3d(wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(place);
                HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), actual, ctx.playerController().getBlockReachDistance(), wouldSneak);
                if (res != null && res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
                    state.setTarget(new MovementTarget(place, true));
                    found = true;

                    if (!preferDown) {
                        // if preferDown is true, we want the last option
                        // if preferDown is false, we want the first
                        break;
                    }
                }
            }
        }
        if (ctx.getSelectedBlock().isPresent()) {
            BlockPos selectedBlock = ctx.getSelectedBlock().get();
            Direction side = ((BlockHitResult) ctx.objectMouseOver()).getDirection();
            // only way for selectedBlock.equals(placeAt) to be true is if it's replaceable
            if (selectedBlock.equals(placeAt) || (MovementHelper.canPlaceAgainst(ctx, selectedBlock) && selectedBlock.relative(side).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                ((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            ((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    enum PlaceResult {
        READY_TO_PLACE, ATTEMPTING, NO_OPTION;
    }

    static boolean isTransparent(Block b) {

        return b instanceof AirBlock ||
                b == Blocks.LAVA ||
                b == Blocks.WATER;
    }

    static List<BetterBlockPos> steppingOnBlocks(IPlayerContext ctx) {
        List<BetterBlockPos> blocks = new ArrayList<>();
        for (byte x = -1; x <= 1; x++) {
            for (byte z = -1; z <= 1; z++) {
                if (ctx.player().getBoundingBox().intersects(Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x, 0, z), Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x + 1, 1, z + 1))) {
                    blocks.add(new BetterBlockPos(ctx.player().getBlockX() + x, ctx.player().getBlockY() - 1, ctx.player().getBlockZ() + z));
                }
            }
        }
        return blocks;
    }

    /**
     * Crossing water at the surface: sprint and swim instead of bobbing. Swimming only starts with
     * the eyes under water, so dip in with the head pitched down; once in the swim pose, keep level,
     * about a block under the path drawn over the surface.
     */
    static void surfaceSwim(IPlayerContext ctx, MovementState state) {
        state.setInput(Input.SPRINT, true);
        state.setInput(Input.JUMP, false);
        float yaw = state.getTarget().getRotation().map(Rotation::getYaw).orElse(ctx.playerRotations().getYaw());
        float pitch;
        if (!ctx.player().isSwimming()) {
            pitch = 35;
            // the swim pose needs the eyes under; pitch alone can't sink them
            if (!ctx.player().isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) state.setInput(Input.SNEAK, true);
        } else if (!ctx.player().isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
            pitch = 6; // breaching: nose back under or the swim pose drops
        } else {
            // two blocks of water over the head: drifted too deep, ease back up
            pitch = isWater(ctx.world().getBlockState(BlockPos.containing(ctx.player().getX(), ctx.player().getEyeY() + 1, ctx.player().getZ()))) ? -12 : 0;
        }
        state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, pitch), true));
    }

    /** Swimming a surface path puts the feet a block under it; that still counts as there. */
    static boolean atSwum(IPlayerContext ctx, BlockPos dest) {
        BlockPos f = ctx.playerFeet();
        return f.equals(dest) || (ctx.player().isSwimming() && f.equals(dest.below()));
    }
}
