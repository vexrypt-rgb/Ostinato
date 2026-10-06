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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.utils.ExperimentalMovement;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public class MovementDescend extends Movement {

    private static final double SHALLOW_WATER_LANDING_PENALTY = 6;

    /**
     * Drops (feet to feet) where a boat lands a hair above the floor, the rider is treated as on land,
     * and both the boat and the rider take the full fall.
     */
    private static final java.util.Set<Integer> BOAT_BREAK_HEIGHTS = new java.util.HashSet<>(java.util.Arrays.asList(12, 13, 49, 51, 111, 114, 202, 310, 315));

    private int numTicks = 0;
    public boolean forceSafeMode = false;

    public MovementDescend(IBaritone baritone, BetterBlockPos start, BetterBlockPos end) {
        super(baritone, start, end, new BetterBlockPos[]{end.above(2), end.above(), end}, end.below());
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
        forceSafeMode = false;
    }

    /**
     * Called by PathExecutor if needing safeMode can only be detected with knowledge about the next movement
     */
    public void forceSafeMode() {
        forceSafeMode = true;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is a fall not a descend
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest.above(), dest);
    }

    public static void cost(CalculationContext context, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        double totalCost = 0;
        BlockState destDown = context.get(destX, y - 1, destZ);
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y - 1, destZ, destDown, false);
        if (totalCost >= COST_INF) {
            return;
        }
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y, destZ, false);
        if (totalCost >= COST_INF) {
            return;
        }
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, true); // only the top block in the 3 we need to mine needs to consider the falling blocks above
        if (totalCost >= COST_INF) {
            return;
        }

        Block fromDown = context.get(x, y - 1, z).getBlock();
        if (MovementHelper.isClimbable(fromDown)) {
            return;
        }

        // A
        //SA
        // A
        // B
        // C
        // D
        //if S is where you start, B needs to be air for a movementfall
        //A is plausibly breakable by either descend or fall
        //C, D, etc determine the length of the fall

        BlockState below = context.get(destX, y - 2, destZ);
        if (!MovementHelper.canWalkOn(context, destX, y - 2, destZ, below)) {
            dynamicFallCost(context, x, y, z, destX, destZ, totalCost, below, res);
            return;
        }

        if (destDown.getBlock() == Blocks.LADDER || destDown.getBlock() == Blocks.VINE) {
            return;
        }
        if (MovementHelper.canUseFrostWalker(context, destDown)) { // no need to check assumeWalkOnWater
            return; // the water will freeze when we try to walk into it
        }

        // we walk half the block plus 0.3 to get to the edge, then we walk the other 0.2 while simultaneously falling (math.max because of how it's in parallel)
        double walk = WALK_OFF_BLOCK_COST;
        if (fromDown == Blocks.SOUL_SAND) {
            // use this ratio to apply the soul sand speed penalty to our 0.8 block distance
            walk *= WALK_ONE_OVER_SOUL_SAND_COST / WALK_ONE_BLOCK_COST;
        }
        totalCost += walk + Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
        res.x = destX;
        res.y = y - 1;
        res.z = destZ;
        res.cost = totalCost;
    }

    /** Whether a neighbour of the landing column has 2+ deep still water under an open drop from {@code topY} down. */
    private static boolean deepDropBeside(CalculationContext context, int x, int waterY, int z, int topY) {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : sides) {
            for (int step = 1; step <= 2; step++) {
                int nx = x + d[0] * step, nz = z + d[1] * step;
                if (!openColumn(context, nx, waterY + 1, topY, nz)) {
                    break;
                }
                BlockState top = context.get(nx, waterY, nz);
                if (MovementHelper.isWater(top) && MovementHelper.isWater(context.get(nx, waterY - 1, nz))
                        && !MovementHelper.isFlowing(nx, waterY, nz, top, context.bsi)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean openColumn(CalculationContext context, int x, int fromY, int toY, int z) {
        for (int yy = fromY; yy < toY; yy++) {
            if (!MovementHelper.fullyPassable(context, x, yy, z)) {
                return false;
            }
        }
        return true;
    }

    public static boolean dynamicFallCost(CalculationContext context, int x, int y, int z, int destX, int destZ, double frontBreak, BlockState below, MutableMoveResult res) {
        if (frontBreak != 0 && context.get(destX, y + 2, destZ).getBlock() instanceof FallingBlock) {
            // if frontBreak is 0 we can actually get through this without updating the falling block and making it actually fall
            // but if frontBreak is nonzero, we're breaking blocks in front, so don't let anything fall through this column,
            // and potentially replace the water we're going to fall into
            return false;
        }
        if (!MovementHelper.canWalkThrough(context, destX, y - 2, destZ, below)) {
            return false;
        }
        double costSoFar = 0;
        int effectiveStartHeight = y;
        for (int fallHeight = 3; true; fallHeight++) {
            int newY = y - fallHeight;
            if (newY < context.world.getMinY()) {
                // when pathing in the end, where you could plausibly fall into the void
                // this check prevents it from getting the block at y=(below whatever the minimum height is) and crashing
                return false;
            }
            boolean reachedMinimum = fallHeight >= context.minFallHeight;
            BlockState ontoBlock = context.get(destX, newY, destZ);
            int unprotectedFallHeight = fallHeight - (y - effectiveStartHeight); // equal to fallHeight - y + effectiveFallHeight, which is equal to -newY + effectiveFallHeight, which is equal to effectiveFallHeight - newY
            double tentativeCost = WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[unprotectedFallHeight] + frontBreak + costSoFar;
            if (reachedMinimum && MovementHelper.isWater(ontoBlock)) {
                if (!MovementHelper.canWalkThrough(context, destX, newY, destZ, ontoBlock)) {
                    return false;
                }
                if (context.assumeWalkOnWater) {
                    return false; // TODO fix
                }
                if (MovementHelper.isFlowing(destX, newY, destZ, ontoBlock, context.bsi)) {
                    return false; // TODO flowing check required here?
                }
                int depth = 1;
                while (depth < 4 && newY - depth > 0 && MovementHelper.isWater(context.get(destX, newY - depth, destZ))
                        && !MovementHelper.isFlowing(destX, newY - depth, destZ, context.get(destX, newY - depth, destZ), context.bsi)) {
                    depth++;
                }
                if (!MovementHelper.canWalkOn(context, destX, newY - depth, destZ)) {
                    // we could punch right through the water into something else
                    return false;
                }
                if (depth == 1) {
                    if (deepDropBeside(context, destX, newY, destZ, y)) {
                        return false; // deep water within two columns: drop there instead and swim straight away
                    }
                    tentativeCost += SHALLOW_WATER_LANDING_PENALTY; // wading out of 1-deep water is slow
                }
                // found a fall into water
                res.x = destX;
                res.y = newY;
                res.z = destZ;
                res.cost = tentativeCost;// TODO incorporate water swim up cost?
                return false;
            }
            if (reachedMinimum && context.allowFallIntoLava && MovementHelper.isLava(ontoBlock)) {
                // found a fall into lava
                res.x = destX;
                res.y = newY;
                res.z = destZ;
                res.cost = tentativeCost;
                return false;
            }
            if (unprotectedFallHeight <= 11 && MovementHelper.isClimbable(ontoBlock.getBlock())) {
                // if fall height is greater than or equal to 11, we don't actually grab on to vines or ladders. the more you know
                // this effectively "resets" our falling speed
                costSoFar += FALL_N_BLOCKS_COST[unprotectedFallHeight - 1];// we fall until the top of this block (not including this block)
                costSoFar += LADDER_DOWN_ONE_COST;
                effectiveStartHeight = newY;
                continue;
            }
            if (MovementHelper.canWalkThrough(context, destX, newY, destZ, ontoBlock)) {
                continue;
            }
            if (!MovementHelper.canWalkOn(context, destX, newY, destZ, ontoBlock)) {
                return false;
            }
            if (MovementHelper.isBottomSlab(ontoBlock)) {
                return false; // falling onto a half slab is really glitchy, and can cause more fall damage than we'd expect
            }
            if (reachedMinimum && unprotectedFallHeight <= context.maxFallHeightNoWater + 1) {
                // fallHeight = 4 means onto.up() is 3 blocks down, which is the max
                res.x = destX;
                res.y = newY + 1;
                res.z = destZ;
                res.cost = tentativeCost;
                return false;
            }
            boolean bucketOk = reachedMinimum && context.hasWaterBucket && unprotectedFallHeight <= context.maxFallHeightBucket + 1;
            double clutch = reachedMinimum ? clutchCost(context, destX, destZ, effectiveStartHeight, newY + 1) : COST_INF;
            if (clutch < COST_INF) {
                clutch += WALK_OFF_BLOCK_COST + frontBreak + costSoFar;
            }
            if (clutch < COST_INF && !(bucketOk && tentativeCost + context.placeBucketCost() <= clutch)) {
                // the bucket wins ties, it doesn't care about timing
                res.x = destX;
                res.y = newY + 1;
                res.z = destZ;
                res.cost = clutch;
                res.clutch = true;
                return false;
            }
            if (bucketOk) {
                res.x = destX;
                res.y = newY + 1;// this is the block we're falling onto, so dest is +1
                res.z = destZ;
                res.cost = tentativeCost + context.placeBucketCost();
                return true;
            }
            boolean boatThere = context.freeBoatAt(x, y, z);
            if ((context.hasBoat || boatThere) && unprotectedFallHeight <= context.maxFallHeightBoat + 1 && !BOAT_BREAK_HEIGHTS.contains(y - newY - 1)) {
                res.x = destX;
                res.y = newY + 1;
                res.z = destZ;
                res.cost = tentativeCost + (boatThere ? context.boardBoatFallCost() : context.boatFallCost());
                return true;
            }
            hurtingFall(context, destX, destZ, newY, unprotectedFallHeight - 1, tentativeCost, ontoBlock, res);
            return false;
        }
    }

    /**
     * A ladder or vine in one of the last few cells before the floor, placed against whatever wall is beside the column.
     * All the timing is in {@link LadderClutch}, we just say which cells have a wall. COST_INF if it can't be done.
     */
    private static double clutchCost(CalculationContext context, int destX, int destZ, int startY, int landY) {
        if (!context.hasClutchItem || context.placeBucketCost() >= COST_INF || !context.bsi.worldBorder.canPlaceAt(destX, destZ)) {
            return COST_INF;
        }
        int mask = 0;
        for (int k = 0; k < LadderClutch.CELLS && landY + k < startY; k++) {
            // the clutch item goes in this cell, so it has to be somewhere altoclef lets us place
            if (!context.get(destX, landY + k, destZ).isAir() || context.isPossiblyProtected(destX, landY + k, destZ)) {
                continue;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                int x = destX + side.getStepX();
                int z = destZ + side.getStepZ();
                if (clutchWall(context.get(x, landY + k, z)) && MovementHelper.canPlaceAgainst(context.bsi, x, landY + k, z)) {
                    mask |= 1 << k;
                    break;
                }
            }
        }
        LadderClutch.Plan plan = mask == 0 ? null : LadderClutch.plan(startY - landY, mask, context.blockReach);
        if (plan == null) {
            return COST_INF;
        }
        // a ladder comes back off the wall after we land (MovementFall.pickUpLadder), mining it and waiting on the drop is about a second
        return plan.ticks() + context.placeBucketCost() + (context.clutchPicksUp ? LADDER_PICKUP_COST : 0);
    }

    static final double LADDER_PICKUP_COST = 20;

    /** canPlaceAgainst lets leaves through, and a ladder can't hang off those (no sturdy face). */
    static boolean clutchWall(BlockState wall) {
        return !wall.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /**
     * The last resort under experimentalMovement: no bucket, no boat, so eat the fall if we can afford the hearts.
     * Everything that protects us gets its say first, a fall that hurts never beats one that doesn't.
     */
    private static void hurtingFall(CalculationContext context, int destX, int destZ, int newY, int blocks, double tentativeCost, BlockState onto, MutableMoveResult res) {
        if (!context.experimental || blocks > ExperimentalMovement.MAX_HURT_FALL) {
            return;
        }
        int damage = ExperimentalMovement.fallDamage(blocks);
        if (!ExperimentalMovement.canAffordFall(context.health, damage, context.experimentalMinHealth)) {
            return;
        }
        // canWalkOn lets some of these through (we can sneak on magma, that is not the same as landing on it)
        if (MovementHelper.avoidWalkingInto(onto) || onto.is(Blocks.MAGMA_BLOCK) || MovementHelper.isLava(context.get(destX, newY + 1, destZ))) {
            return;
        }
        res.x = destX;
        res.y = newY + 1;
        res.z = destZ;
        res.cost = tentativeCost + damage * context.fallDamageCost;
        res.damage = damage;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        BlockPos playerFeet = ctx.playerFeet();
        BlockPos fakeDest = new BlockPos(dest.getX() * 2 - src.getX(), dest.getY(), dest.getZ() * 2 - src.getZ());
        if ((playerFeet.equals(dest) || playerFeet.equals(fakeDest)) && (MovementHelper.isLiquid(ctx, dest) || ctx.player().position().y - dest.getY() < 0.5)) { // lilypads
            // Wait until we're actually on the ground before saying we're done because sometimes we continue to fall if the next action starts immediately
            return state.setStatus(MovementStatus.SUCCESS);
            /* else {
                // System.out.println(player().position().y + " " + playerFeet.getY() + " " + (player().position().y - playerFeet.getY()));
            }*/
        }
        if (safeMode()) {
            double destX = (src.getX() + 0.5) * 0.17 + (dest.getX() + 0.5) * 0.83;
            double destZ = (src.getZ() + 0.5) * 0.17 + (dest.getZ() + 0.5) * 0.83;
            state.setTarget(new MovementState.MovementTarget(
                    RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                            new Vec3(destX, dest.getY(), destZ),
                            ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                    false
            )).setInput(Input.MOVE_FORWARD, true);
            return state;
        }
        double diffX = ctx.player().position().x - (dest.getX() + 0.5);
        double diffZ = ctx.player().position().z - (dest.getZ() + 0.5);
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double x = ctx.player().position().x - (src.getX() + 0.5);
        double z = ctx.player().position().z - (src.getZ() + 0.5);
        double fromStart = Math.sqrt(x * x + z * z);

        state.setInput(Input.SNEAK, Baritone.settings().allowWalkOnMagmaBlocks.value && ctx.world().getBlockState(ctx.player().blockPosition().below()).is(Blocks.MAGMA_BLOCK));

        if (!playerFeet.equals(dest) || ab > 0.25) {
            if (numTicks++ < 20 && fromStart < 1.25) {
                MovementHelper.moveTowards(ctx, state, fakeDest);
            } else {
                MovementHelper.moveTowards(ctx, state, dest);
            }
        }
        return state;
    }

    public boolean safeMode() {
        if (forceSafeMode) {
            return true;
        }
        // (dest - src) + dest is offset 1 more in the same direction
        // so it's the block we'd need to worry about running into if we decide to sprint straight through this descend
        BlockPos into = dest.subtract(src.below()).offset(dest);
        if (skipToAscend()) {
            // if dest extends into can't walk through, but the two above are can walk through, then we can overshoot and glitch in that weird way
            return true;
        }
        for (int y = 0; y <= 2; y++) { // we could hit any of the three blocks
            BlockPos p = into.above(y);
            if (MovementHelper.avoidWalkingInto(BlockStateInterface.get(ctx, p))) {
                return true;
            }
            if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean skipToAscend() {
        BlockPos into = dest.subtract(src.below()).offset(dest);
        return !MovementHelper.canWalkThrough(ctx, new BetterBlockPos(into)) && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(into).above()) && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(into).above(2));
    }
}
