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
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

import java.util.HashSet;
import java.util.Set;

public class MovementParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private final Direction direction;
    private final int dist;
    private final boolean ascend;

    private MovementParkour(IBaritone baritone, BetterBlockPos src, int dist, Direction dir, boolean ascend) {
        super(baritone, src, src.relative(dir, dist).above(ascend ? 1 : 0), EMPTY, src.relative(dir, dist).below(ascend ? 0 : 1));
        this.direction = dir;
        this.dist = dist;
        this.ascend = ascend;
    }

    /** Test hook: SimBench turns plain parkour off so the template movements are what gets exercised. */
    public static volatile boolean disabled;

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        int dist = Math.abs(res.x - src.x) + Math.abs(res.z - src.z);
        return new MovementParkour(context.getBaritone(), src, dist, direction, res.y > src.y);
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        if (!context.allowParkour || disabled) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.world.getMaxY()) {
            return;
        }
        int xDiff = dir.getStepX();
        int zDiff = dir.getStepZ();
        if (!MovementHelper.fullyPassable(context, x + xDiff, y, z + zDiff)) {
            // most common case at the top -- the adjacent block isn't air
            return;
        }
        BlockState adj = context.get(x + xDiff, y - 1, z + zDiff);
        if (MovementHelper.canWalkOn(context, x + xDiff, y - 1, z + zDiff, adj)) { // don't parkour if we could just traverse (for now)
            // second most common case -- we could just traverse not parkour
            return;
        }
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) { // magma sucks
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 1, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 2, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (MovementHelper.isClimbable(standingOn.getBlock()) || standingOn.getBlock() instanceof StairBlock || MovementHelper.isBottomSlab(standingOn)) {
            return;
        }
        // we can't jump from (frozen) water with assumeWalkOnWater because we can't be sure it will be frozen
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return; // can't jump out of water
        }
        int maxJump;
        if (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) {
            maxJump = 2;
        } else if (standingOn.getBlock() == Blocks.SOUL_SAND) {
            maxJump = 2; // 1 block gap
        } else if (context.canSprint && context.allowParkourFourGap && hasRunUp(context, x, y, z, xDiff, zDiff)) {
            maxJump = 5; // 4 block gap: a hop timed onto the edge, driven by the kinematic controller
        } else if (context.canSprint) {
            maxJump = 4;
        } else {
            maxJump = 3;
        }

        // check parkour jumps from smallest to largest for obstacles/walls and landing positions
        int verifiedMaxJump = 1; // i - 1 (when i = 2)
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + xDiff * i;
            int destZ = z + zDiff * i;

            // jump straight into a ladder hung on the far wall, facing us (caught mid-air, no floor needed)
            if (isCatchableLadder(context.bsi.get0(destX, y, destZ), dir)
                    && climbableOrPassable(context, destX, y + 1, destZ, dir) && climbableOrPassable(context, destX, y + 2, destZ, dir)) {
                res.x = destX;
                res.y = y;
                res.z = destZ;
                res.cost = costFromJumpDistance(i) + context.jumpPenalty;
                return;
            }

            // check head/feet
            if (!MovementHelper.fullyPassable(context, destX, y + 1, destZ)) {
                break;
            }
            if (!MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                break;
            }

            // check for ascend landing position
            BlockState destInto = context.bsi.get0(destX, y, destZ);
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                if (i <= 3 && context.allowParkourAscend && context.canSprint && MovementHelper.canWalkOn(context, destX, y, destZ, destInto) && checkOvershootSafety(context.bsi, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = i * SPRINT_ONE_BLOCK_COST + context.jumpPenalty;
                    return;
                }
                break;
            }

            // check for flat landing position
            BlockState landingOn = context.bsi.get0(destX, y - 1, destZ);
            // a fence or wall top is half a block up: reachable like an ascend, so only up to a 2 block gap
            if (isTallTop(landingOn) && i <= 3 && (i < 3 || context.canSprint)) {
                if (checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + context.jumpPenalty;
                    return;
                }
                break;
            }
            // farmland needs to be canWalkOn otherwise farm can never work at all, but we want to specifically disallow ending a jump on farmland haha
            // frostwalker works here because we can't jump from possibly unfrozen water
            if ((landingOn.getBlock() != Blocks.FARMLAND && MovementHelper.canWalkOn(context, destX, y - 1, destZ, landingOn))
                    || (Math.min(16, context.frostWalker + 2) >= i && MovementHelper.canUseFrostWalker(context, landingOn))
            ) {
                if (checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + context.jumpPenalty;
                    return;
                }
                break;
            }

            if (!MovementHelper.fullyPassable(context, destX, y + 3, destZ)) {
                break;
            }

            verifiedMaxJump = i;
        }

        // parkour place starts here
        if (!context.allowParkourPlace) {
            return;
        }
        // check parkour jumps from largest to smallest for positions to place blocks
        for (int i = verifiedMaxJump; i > 1; i--) {
            int destX = x + i * xDiff;
            int destZ = z + i * zDiff;
            BlockState toReplace = context.get(destX, y - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue;
            }
            if (!MovementHelper.isReplaceable(destX, y - 1, destZ, toReplace, context.bsi)) {
                continue;
            }
            if (!checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                continue;
            }
            for (int j = 0; j < 5; j++) {
                int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepX();
                int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepY();
                int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepZ();
                if (againstX == destX - xDiff && againstZ == destZ - zDiff) { // we can't turn around that fast
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + placeCost + context.jumpPenalty;
                    return;
                }
            }
        }
    }

    /**
     * A lone post (iron bars, fence) under the landing: a walking 2 block gap jump falls just short of it, so sprint and brake in the air instead
     */
    private boolean narrowLanding() {
        net.minecraft.world.phys.shapes.VoxelShape shape = ctx.world().getBlockState(dest.below()).getCollisionShape(ctx.world(), dest.below());
        if (shape.isEmpty()) {
            return false;
        }
        net.minecraft.world.phys.AABB box = shape.bounds();
        return box.maxX - box.minX < 0.99 || box.maxZ - box.minZ < 0.99;
    }

    static boolean isTallTop(BlockState state) {
        Block b = state.getBlock();
        return b instanceof FenceBlock || b instanceof WallBlock || b instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN);
    }

    private static boolean isCatchableLadder(BlockState state, Direction dir) {
        return state.getBlock() == Blocks.LADDER && state.getValue(LadderBlock.FACING) == dir.getOpposite();
    }

    private static boolean climbableOrPassable(CalculationContext context, int x, int y, int z, Direction dir) {
        BlockState state = context.bsi.get0(x, y, z);
        return isCatchableLadder(state, dir) || MovementHelper.fullyPassable(context, x, y, z, state);
    }

    private static boolean checkOvershootSafety(BlockStateInterface bsi, int x, int y, int z) {
        // we're going to walk into these two blocks after the landing of the parkour anyway, so make sure they aren't avoidWalkingInto
        return !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z)) && !MovementHelper.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    private static boolean hasRunUp(CalculationContext context, int x, int y, int z, int xDiff, int zDiff) {
        int bx = x - xDiff, bz = z - zDiff;
        return MovementHelper.canWalkOn(context, bx, y - 1, bz)
                && MovementHelper.fullyPassable(context, bx, y, bz)
                && MovementHelper.fullyPassable(context, bx, y + 1, bz);
    }

    private static double costFromJumpDistance(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2; // IDK LOL
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            case 5:
                return SPRINT_ONE_BLOCK_COST * 6; // tight jump, prefer shorter routes
            default:
                throw new IllegalStateException("LOL " + dist);
        }
    }


    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        for (int i = 0; i <= dist; i++) {
            for (int y = 0; y < 2; y++) {
                set.add(src.relative(direction, i).above(y));
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // once this movement is instantiated, the state is default to PREPPING
        // but once it's ticked for the first time it changes to RUNNING
        // since we don't really know anything about momentum, it suffices to say Parkour can only be canceled on the 0th tick
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < src.y) {
            // we have fallen
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (dist >= 4 || ascend || dist == 3 && narrowLanding()) {
            state.setInput(Input.SPRINT, true);
        }
        if (Baritone.settings().allowWalkOnMagmaBlocks.value && ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.MAGMA_BLOCK)) {
            state.setInput(Input.SNEAK, true);
        }

        MovementHelper.moveTowards(ctx, state, dest);
        if (!ctx.player().onGround() && ctx.player().position().y > src.y + 0.1 && BlockStateInterface.getBlock(ctx, dest) != Blocks.LADDER) {
            // mid-air: stop pushing once coasting alone reaches the landing centre, so narrow tops (bars, fence posts) are not overshot
            double pos = ctx.player().position().x * direction.getStepX() + ctx.player().position().z * direction.getStepZ();
            double centre = (dest.x + 0.5) * direction.getStepX() + (dest.z + 0.5) * direction.getStepZ();
            double v = ctx.player().getDeltaMovement().x * direction.getStepX() + ctx.player().getDeltaMovement().z * direction.getStepZ();
            double vy = ctx.player().getDeltaMovement().y, y = ctx.player().position().y, land = pos;
            for (int t = 0; t < 40 && (vy > 0 || y > dest.y); t++) {
                vy = (vy - 0.08) * 0.98;
                y += vy;
                v *= 0.91;
                land += v;
            }
            if (pos < centre && land > centre) {
                state.setInput(Input.SPRINT, false);
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, land > centre + 0.2);
            }
        }
        if (ctx.playerFeet().equals(dest)) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                // it physically hurt me to add support for parkour jumping onto a vine
                // but i did it anyway
                return state.setStatus(MovementStatus.SUCCESS);
            }
            // any parkour landing still carrying momentum past the centre (or sliding on ice): brake toward the centre before succeeding
            double off = (ctx.player().position().x - (dest.x + 0.5)) * direction.getStepX() + (ctx.player().position().z - (dest.z + 0.5)) * direction.getStepZ();
            double v = ctx.player().getDeltaMovement().x * direction.getStepX() + ctx.player().getDeltaMovement().z * direction.getStepZ();
            if (off > 0.2 && v > 0.05) {
                state.setInput(Input.SPRINT, false);
                return state;
            }
            if (ctx.player().position().y - ctx.playerFeet().getY() < 0.094 // lilypads
                    || ctx.player().onGround() && isTallTop(ctx.world().getBlockState(dest.below()))) {
                state.setStatus(MovementStatus.SUCCESS);
            }
        } else if (!ctx.playerFeet().equals(src)) {
            if (ctx.playerFeet().equals(src.relative(direction)) || ctx.player().position().y - src.y > 0.0001) {
                if (Baritone.settings().allowPlace.value // see PR #3775
                        && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()
                        && !MovementHelper.canWalkOn(ctx, dest.below())
                        && !ctx.player().onGround()
                        && MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false) == PlaceResult.READY_TO_PLACE
                ) {
                    // go in the opposite order to check DOWN before all horizontals -- down is preferable because you don't have to look to the side while in midair, which could mess up the trajectory
                    state.setInput(Input.CLICK_RIGHT, true);
                }
                // prevent jumping too late by checking for ascend
                if ((dist == 3 || dist == 5) && !ascend) { // 2 or 4 block gap: jump from the very edge
                    double xDiff = (src.x + 0.5) - ctx.player().position().x;
                    double zDiff = (src.z + 0.5) - ctx.player().position().z;
                    double distFromStart = Math.max(Math.abs(xDiff), Math.abs(zDiff));
                    // a 4 block gap jumps on the last tick still on the block: wait while the next step stays on it
                    double speed = dist == 5 ? Math.max(Math.abs(ctx.player().getDeltaMovement().x), Math.abs(ctx.player().getDeltaMovement().z)) / 0.546 : 0;
                    if (dist == 5 ? distFromStart + speed < 1.0 : distFromStart < 0.7) {
                        return state;
                    }
                }

                state.setInput(Input.JUMP, true);
            } else if (!ctx.playerFeet().equals(dest.relative(direction, -1))) {
                state.setInput(Input.SPRINT, dist == 5); // a 4 block gap needs the run-up at full sprint
                if (ctx.playerFeet().equals(src.relative(direction, -1))) {
                    MovementHelper.moveTowards(ctx, state, src);
                } else {
                    MovementHelper.moveTowards(ctx, state, src.relative(direction, -1));
                }
            }
        }
        return state;
    }
}
