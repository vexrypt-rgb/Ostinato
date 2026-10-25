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
import baritone.pathing.movement.FallBudget;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.AirDrift;
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
    private static final double DEEP_WATER_PASSED_UP_PENALTY = 14;

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
        if (MovementHelper.isClimbable(context.get(x, y, z).getBlock())) {
            return; // walking off a ladder just falls past the block: the ClimbJump leap is the way down
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
        return fallDown(context, x, y, z, destX, destZ, frontBreak, 3, 0, y - 1, 0, Integer.MIN_VALUE, COST_INF, res);
    }

    /**
     * Ostinato: per-thread scratch results for the steered-fall recursion, one slot per recursion depth, so a fall search
     * allocates nothing (these two objects were ~40% of all bytes the planner allocated). A reset slot equals a new one.
     */
    private static final class FallScratch {
        MutableMoveResult[] res = new MutableMoveResult[8];
        boolean[][] bucket = new boolean[8][];
        int depth;

        MutableMoveResult take(boolean[][] bucketOut) {
            int d = depth++;
            if (d >= res.length) {
                res = java.util.Arrays.copyOf(res, d * 2);
                bucket = java.util.Arrays.copyOf(bucket, d * 2);
            }
            if (res[d] == null) {
                res[d] = new MutableMoveResult();
                bucket[d] = new boolean[1];
            }
            res[d].reset();
            bucket[d][0] = false;
            bucketOut[0] = bucket[d];
            return res[d];
        }
    }

    private static final ThreadLocal<FallScratch> SCRATCH = ThreadLocal.withInitial(FallScratch::new);
    private static final ThreadLocal<boolean[][]> HOLDER = ThreadLocal.withInitial(() -> new boolean[1][]);

    /** At most this many holes threaded in one fall. */
    private static final int MAX_STEERS = 3;
    /** Each threaded hole: the steering has to be right, so a plain landing that is about as cheap wins. */
    static final double STEER_COST = 4;

    /**
     * The fall down column cx, cz from fallHeight from on. legStart is how far below y the current sideways leg began
     * (0, or the floor of the last hole we went through) and legTop the highest cell it drifts through; the floor at
     * noSteerY was already offered its holes by the caller.
     */
    private static boolean fallDown(CalculationContext context, int x, int y, int z, int cx, int cz, double frontBreak, int from,
                                    int legStart, int legTop, int steers, int noSteerY, double bound, MutableMoveResult res) {
        double costSoFar = 0;
        int effectiveStartHeight = y;
        for (int fallHeight = from; true; fallHeight++) {
            int newY = y - fallHeight;
            if (newY < context.worldMinY /* Ostinato: was context.world.getMinY() */) {
                // when pathing in the end, where you could plausibly fall into the void
                // this check prevents it from getting the block at y=(below whatever the minimum height is) and crashing
                return false;
            }
            boolean reachedMinimum = fallHeight >= context.minFallHeight;
            BlockState ontoBlock = context.get(cx, newY, cz);
            int unprotectedFallHeight = fallHeight - (y - effectiveStartHeight); // equal to fallHeight - y + effectiveFallHeight, which is equal to -newY + effectiveFallHeight, which is equal to effectiveFallHeight - newY
            // Ostinato: Phase 2 early-out. Every landing below costs at least this (ladders add FALL[h-1] to costSoFar,
            // the rest add on top of tentativeCost); the caller only takes a result under bound. A clutch can undercut it.
            if (bound < COST_INF && !context.hasClutchItem
                    && WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[Math.max(unprotectedFallHeight - 1, 0)] + frontBreak + costSoFar >= bound) {
                return false;
            }
            double tentativeCost = WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[unprotectedFallHeight] + frontBreak + costSoFar;
            if (reachedMinimum && MovementHelper.isWater(ontoBlock)) {
                if (!MovementHelper.canWalkThrough(context, cx, newY, cz, ontoBlock)) {
                    return false;
                }
                if (context.assumeWalkOnWater) {
                    return false; // TODO fix
                }
                if (MovementHelper.isFlowing(cx, newY, cz, ontoBlock, context.bsi)) {
                    return false; // TODO flowing check required here?
                }
                int depth = 1;
                while (depth < 4 && newY - depth > 0 && MovementHelper.isWater(context.get(cx, newY - depth, cz))
                        && !MovementHelper.isFlowing(cx, newY - depth, cz, context.get(cx, newY - depth, cz), context.bsi)) {
                    depth++;
                }
                if (!MovementHelper.canWalkOn(context, cx, newY - depth, cz)) {
                    // we could punch right through the water into something else
                    return false;
                }
                if (depth == 1) {
                    tentativeCost += SHALLOW_WATER_LANDING_PENALTY; // wading out of 1-deep water is slow
                    if (deepDropBeside(context, cx, newY, cz, y)) {
                        // A drop that reaches the deep water wins by this much. Not refused: under a cliff over a
                        // shelving shore every landing is shallow with deep water two columns out, and no drop reaches that.
                        tentativeCost += DEEP_WATER_PASSED_UP_PENALTY;
                    }
                }
                // found a fall into water
                res.x = cx;
                res.y = newY;
                res.z = cz;
                res.cost = tentativeCost;// TODO incorporate water swim up cost?
                return false;
            }
            if (reachedMinimum && context.allowFallIntoLava && MovementHelper.isLava(ontoBlock)) {
                // found a fall into lava
                res.x = cx;
                res.y = newY;
                res.z = cz;
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
            if (MovementHelper.canWalkThrough(context, cx, newY, cz, ontoBlock)) {
                continue;
            }
            if (reachedMinimum && context.allowSteeredFalls && frontBreak == 0 && steers < MAX_STEERS && newY != noSteerY
                    && effectiveStartHeight == y && !freeLanding(ontoBlock, unprotectedFallHeight, context)) {
                FallScratch scratch = SCRATCH.get(); // Ostinato: pooled, was two allocations per steer attempt
                boolean[][] holder = HOLDER.get();
                MutableMoveResult through = scratch.take(holder);
                boolean[] throughBucket = holder[0];
                int mark = scratch.depth - 1;
                try {
                if (steerThrough(context, x, y, z, cx, cz, fallHeight, newY, legStart, legTop, steers, bound, through, throughBucket)) {
                    // the plain landing on this floor (a clutch, a bucket, a mount, a hit we can take) might still be cheaper;
                    // nothing above it changed the fall (no ladder, costSoFar is 0), so pick up right at this floor
                    boolean bucket = fallDown(context, x, y, z, cx, cz, frontBreak, fallHeight, legStart, legTop, steers, newY, bound, res);
                    if (through.cost < res.cost) {
                        copy(through, res);
                        return throughBucket[0];
                    }
                    return bucket;
                }
                } finally {
                    scratch.depth = mark;
                }
            }
            if (!MovementHelper.canWalkOn(context, cx, newY, cz, ontoBlock)) {
                return false;
            }
            if (MovementHelper.isBottomSlab(ontoBlock)) {
                // Ostinato: a half slab's top is half a block lower than the node height says, so it hurts like one more
                // block of fall. Only plain landings: no clutch, bucket or boat on half a block.
                if (reachedMinimum && effectiveStartHeight == y) {
                    if (unprotectedFallHeight + 1 <= context.maxFallHeightNoWater + 1) {
                        res.x = cx;
                        res.y = newY + 1;
                        res.z = cz;
                        res.cost = tentativeCost;
                    } else {
                        hurtingFall(context, cx, cz, newY, unprotectedFallHeight, tentativeCost, ontoBlock, res);
                    }
                }
                return false;
            }
            if (reachedMinimum && unprotectedFallHeight <= context.maxFallHeightNoWater + 1) {
                // fallHeight = 4 means onto.up() is 3 blocks down, which is the max
                res.x = cx;
                res.y = newY + 1;
                res.z = cz;
                res.cost = tentativeCost;
                return false;
            }
            if (reachedMinimum && (ontoBlock.is(Blocks.SLIME_BLOCK) || ontoBlock.is(Blocks.HAY_BLOCK) && softFallDamage(unprotectedFallHeight - 1, HAY_FALL_MULTIPLIER) == 0)) {
                // slime throws us back up instead of hurting us (we came down this column, so it's clear going back up),
                // hay takes a fifth of the hit and up to 7 blocks of that is nothing
                res.x = cx;
                res.y = newY + 1;
                res.z = cz;
                res.cost = tentativeCost + (ontoBlock.is(Blocks.SLIME_BLOCK) ? slimeSettleTicks(unprotectedFallHeight - 1) : 0);
                return false;
            }
            boolean bucketOk = reachedMinimum && context.hasWaterBucket && unprotectedFallHeight <= context.maxFallHeightBucket + 1;
            double clutch = reachedMinimum ? clutchCost(context, x, z, cx, cz, effectiveStartHeight, newY + 1) : COST_INF;
            if (clutch < COST_INF) {
                clutch += WALK_OFF_BLOCK_COST + frontBreak + costSoFar;
            }
            if (clutch < COST_INF && !(bucketOk && tentativeCost + context.placeBucketCost() <= clutch)) {
                // the bucket wins ties, it doesn't care about timing
                res.x = cx;
                res.y = newY + 1;
                res.z = cz;
                res.cost = clutch;
                res.clutch = true;
                return false;
            }
            if (bucketOk) {
                res.x = cx;
                res.y = newY + 1;// this is the block we're falling onto, so dest is +1
                res.z = cz;
                res.cost = tentativeCost + context.placeBucketCost();
                return true;
            }
            boolean boatThere = context.freeBoatAt(x, y, z);
            if ((context.hasBoat || boatThere) && unprotectedFallHeight <= context.maxFallHeightBoat + 1 && !BOAT_BREAK_HEIGHTS.contains(y - newY - 1)) {
                res.x = cx;
                res.y = newY + 1;
                res.z = cz;
                res.cost = tentativeCost + (boatThere ? context.boardBoatFallCost() : context.boatFallCost());
                return true;
            }
            if (reachedMinimum && context.mountAt(cx, newY + 1, cz)) {
                // something to ride on the landing cell or beside it: get on just before we hit, and riding cancels the fall
                res.x = cx;
                res.y = newY + 1;
                res.z = cz;
                res.cost = tentativeCost + context.mountClutchCost();
                res.mount = true;
                return false;
            }
            hurtingFall(context, cx, cz, newY, unprotectedFallHeight - 1, tentativeCost, ontoBlock, res);
            return false;
        }
    }

    /** Landing on this floor costs nothing, no reason to look for a way past it. */
    private static boolean freeLanding(BlockState onto, int unprotectedFallHeight, CalculationContext context) {
        return unprotectedFallHeight <= context.maxFallHeightNoWater + 1 || onto.is(Blocks.SLIME_BLOCK)
                || onto.is(Blocks.HAY_BLOCK) && softFallDamage(unprotectedFallHeight - 1, HAY_FALL_MULTIPLIER) == 0;
    }

    /** Column offsets within {@link AirDrift#MAX_RADIUS}, nearest first. */
    private static final int[][] DRIFT_OFFSETS;

    static {
        java.util.List<int[]> l = new java.util.ArrayList<>();
        int r = AirDrift.MAX_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx != 0 || dz != 0) {
                    l.add(new int[]{dx, dz});
                }
            }
        }
        l.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        DRIFT_OFFSETS = l.toArray(new int[0][]);
    }

    /** Holes tried per floor before giving up on it: each one is a whole fall further down. */
    private static final int MAX_HOLES_TRIED = 4;

    /**
     * A floor that would hurt is in the way at newY: drift sideways on the way down into an opening in it (a dropper's
     * offset hole, the next ledge down) and carry on falling from there. Fills out with the cheapest such fall.
     */
    private static boolean steerThrough(CalculationContext context, int x, int y, int z, int cx, int cz, int fallHeight, int newY,
                                        int legStart, int legTop, int steers, double bound, MutableMoveResult out, boolean[] bucketOut) {
        int toDepth = y - (newY + 1); // feet on the floor's top when we get there
        int tried = 0;
        FallScratch scratch = SCRATCH.get(); // Ostinato: pooled, was one allocation per call
        int mark = scratch.depth;
        MutableMoveResult sub = scratch.take(HOLDER.get());
        try {
        for (int[] o : DRIFT_OFFSETS) {
            int hx = cx + o[0], hz = cz + o[1];
            if (!AirDrift.reachable(o[0], o[1], legStart, toDepth)) {
                continue;
            }
            BlockState hole = context.get(hx, newY, hz);
            if (MovementHelper.isLava(hole) || !context.isLoaded(hx, hz)
                    || !(MovementHelper.isWater(hole) || MovementHelper.fullyPassable(context, hx, newY, hz))
                    || !openBox(context, cx, cz, hx, hz, newY + 1, legTop)) {
                continue;
            }
            sub.reset();
            // water in the opening is a landing on this floor's level, so start the next leg right at it
            // Ostinato: a hole only counts if it beats the best so far (and what our caller would take)
            double subBound = Math.min(out.cost, bound) - STEER_COST;
            boolean bucket = fallDown(context, x, y, z, hx, hz, 0, fallHeight, y - newY + 2, newY - 1, steers + 1, Integer.MIN_VALUE, subBound, sub);
            if (sub.cost < COST_INF && sub.y <= newY) {
                sub.cost += STEER_COST;
                int[] tail = sub.steer == null ? new int[0] : sub.steer;
                int[] steer = new int[3 + tail.length];
                steer[0] = hx;
                steer[1] = hz;
                steer[2] = newY;
                System.arraycopy(tail, 0, steer, 3, tail.length);
                sub.steer = steer;
                if (sub.cost < out.cost) {
                    copy(sub, out);
                    bucketOut[0] = bucket;
                }
            }
            if (++tried >= MAX_HOLES_TRIED) {
                break;
            }
        }
        } finally {
            scratch.depth = mark;
        }
        return out.cost < COST_INF;
    }

    /** Everything we can drift through between the two columns, from fromY up to toY. */
    private static boolean openBox(CalculationContext context, int ax, int az, int bx, int bz, int fromY, int toY) {
        for (int yy = toY; yy >= fromY; yy--) {
            for (int xx = Math.min(ax, bx); xx <= Math.max(ax, bx); xx++) {
                for (int zz = Math.min(az, bz); zz <= Math.max(az, bz); zz++) {
                    if (!MovementHelper.fullyPassable(context, xx, yy, zz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void copy(MutableMoveResult from, MutableMoveResult to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.cost = from.cost;
        to.damage = from.damage;
        to.clutch = from.clutch;
        to.mount = from.mount;
        to.steer = from.steer;
    }

    /**
     * A ladder or vine in one of the last few cells before the floor, placed against whatever wall is beside the column.
     * All the timing is in {@link LadderClutch}, we just say which cells have a wall. COST_INF if it can't be done.
     */
    private static double clutchCost(CalculationContext context, int srcX, int srcZ, int destX, int destZ, int startY, int landY) {
        if (!context.hasClutchItem || context.placeBucketCost() >= COST_INF || !context.bsi.worldBorder.canPlaceAt(destX, destZ)) {
            return COST_INF;
        }
        int mask = 0;
        for (int k = 0; k < LadderClutch.CELLS && landY + k < startY; k++) {
            // the clutch item goes in this cell, so it has to be somewhere altoclef lets us place
            if (!context.get(destX, landY + k, destZ).isAir() || context.isPossiblyProtected(destX, landY + k, destZ)
                    || AltoClefSettings.getInstance().shouldAvoidPlacingAt(destX, landY + k, destZ)) {
                continue;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (context.clutchIsLadder && inLine(side, destX - srcX, destZ - srcZ)) {
                    continue; // we walk off towards this wall and keep the speed, we'd be pinned against it and a ladder doesn't fit
                }
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

    /** HayBlock.fallOn: the hit is scaled by this, rounded down (LivingEntity.calculateFallDamage floors). */
    static final float HAY_FALL_MULTIPLIER = 0.2F;

    static int softFallDamage(int blocks, float multiplier) {
        return (int) Math.floor(Math.max(0, blocks - ExperimentalMovement.SAFE_FALL) * multiplier);
    }

    private static final double[] SLIME_SETTLE = new double[1024];

    /**
     * Ticks spent bouncing on slime after a fall of this many blocks, past the fall itself. Vanilla reverses the fall
     * at every landing until we sneak, and sneaking lands for real with the damage of that bounce's drop, so
     * MovementFall sneaks once a bounce comes down from under 3 blocks. That takes 5 or 6 bounces from any height.
     */
    static double slimeSettleTicks(int blocks) {
        blocks = Math.max(0, Math.min(blocks, SLIME_SETTLE.length - 1));
        double t = SLIME_SETTLE[blocks];
        if (t == 0) {
            SLIME_SETTLE[blocks] = t = simulateSlimeSettle(blocks);
        }
        return t;
    }

    private static double simulateSlimeSettle(int blocks) {
        double y = blocks, vy = 0, peak = blocks;
        int ticks = 0, firstFall = -1;
        while (ticks < 2000) {
            ticks++;
            if (y + vy <= 0 && vy < 0) {
                if (firstFall < 0) {
                    firstFall = ticks;
                }
                if (peak < SLIME_SNEAK_DROP) {
                    break;
                }
                y = 0;
                vy = -vy;
                peak = 0;
            } else {
                y += vy;
            }
            peak = Math.max(peak, y);
            vy = (vy - 0.08) * 0.98;
        }
        return Math.max(1, ticks - Math.max(firstFall, 0));
    }

    /** A bounce coming down from less than this lands safely when we sneak onto it. */
    public static final double SLIME_SNEAK_DROP = 2.9;

    /** Is this wall the one we are walking towards? Measured in the real game: walking off pins us against it, so a ladder is never placed there. */
    private static boolean inLine(Direction side, int dx, int dz) {
        return side.getStepX() != 0 && side.getStepX() == Integer.signum(dx) || side.getStepZ() != 0 && side.getStepZ() == Integer.signum(dz);
    }

    /** canPlaceAgainst lets leaves through, and a ladder can't hang off those (no sturdy face). */
    static boolean clutchWall(BlockState wall) {
        return !wall.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /**
     * The last resort under experimentalMovement: no bucket, no boat, so eat the fall if we can afford the hearts.
     * Everything that protects us gets its say first, a fall that hurts never beats one that doesn't.
     */
    private static void hurtingFall(CalculationContext context, int destX, int destZ, int newY, int blocks, double tentativeCost, BlockState onto, MutableMoveResult res) {
        int damage = onto.is(Blocks.HAY_BLOCK) ? softFallDamage(blocks, HAY_FALL_MULTIPLIER) : ExperimentalMovement.fallDamage(blocks);
        int hit = damage + ExperimentalMovement.SAFE_FALL; // the plain fall that hurts as much, which is what the caps are in
        if (context.experimental) {
            if (hit > ExperimentalMovement.MAX_HURT_FALL) {
                return;
            }
            if (!ExperimentalMovement.canAffordFall(context.health, damage, context.experimentalMinHealth)) {
                return;
            }
        } else if (hit > FallBudget.maxBlocks(context.health, FallBudget.FLOOR, context.maxFallHeightNoWater, FallBudget.CAP)) {
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
