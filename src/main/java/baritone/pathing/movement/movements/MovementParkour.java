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
import baritone.api.utils.Rotation;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
    /** Backed up a block for the 4 block gap's run-up. */
    private boolean ranUp;
    private final boolean ascend;
    /** Blocks laid in front of the edge before the jump: one turns a 5 block gap into a 4 block one, two a 6 block gap. */
    private final int ext;
    /** The extension is down and the player has backed up to run at the jump; only then does the run-up begin. */
    private boolean backed;
    private PlayerSim sim;

    private MovementParkour(IBaritone baritone, BetterBlockPos src, int dist, Direction dir, boolean ascend) {
        super(baritone, src, src.relative(dir, dist).above(ascend ? 1 : 0), EMPTY, src.relative(dir, dist).below(ascend ? 0 : 1));
        this.direction = dir;
        this.dist = dist;
        this.ascend = ascend;
        this.ext = dist > 5 ? dist - 5 : 0;
    }

    /** @return how many blocks this jump lays at the edge before leaping, 0 for a plain jump */
    public int extensions() {
        return ext;
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

        if (context.hasThrowaway && context.canSprint && context.allowParkourFourGap) {
            for (int e = 1; e <= 2; e++) {
                if (extendedJump(context, x, y, z, xDiff, zDiff, e, res)) {
                    return;
                }
            }
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
     * Lay {@code e} blocks past the edge, then sprint-jump the 4 block gap from the last one: a 5 block gap with one
     * extension, a 6 block gap with two.
     */
    private static boolean extendedJump(CalculationContext context, int x, int y, int z, int xDiff, int zDiff, int e, MutableMoveResult res) {
        if (!MovementHelper.canPlaceAgainst(context.bsi, x, y - 1, z)) {
            return false;
        }
        double cost = 0;
        for (int k = 1; k <= e; k++) {
            int bx = x + k * xDiff, bz = z + k * zDiff;
            BlockState below = context.get(bx, y - 1, bz);
            double pc = context.costOfPlacingAt(bx, y - 1, bz, below);
            if (pc >= COST_INF || !MovementHelper.isReplaceable(bx, y - 1, bz, below, context.bsi)) {
                return false;
            }
            if (!MovementHelper.fullyPassable(context, bx, y, bz) || !MovementHelper.fullyPassable(context, bx, y + 1, bz) || !MovementHelper.fullyPassable(context, bx, y + 2, bz)) {
                return false;
            }
            cost += pc + WALK_ONE_BLOCK_COST;
        }
        int dist = e + 5;
        for (int i = e + 1; i <= dist; i++) {
            int dx = x + i * xDiff, dz = z + i * zDiff;
            if (!MovementHelper.fullyPassable(context, dx, y, dz) || !MovementHelper.fullyPassable(context, dx, y + 1, dz) || !MovementHelper.fullyPassable(context, dx, y + 2, dz)) {
                return false;
            }
        }
        int lx = x + dist * xDiff, lz = z + dist * zDiff;
        BlockState landing = context.bsi.get0(lx, y - 1, lz);
        if (landing.getBlock() == Blocks.FARMLAND || !MovementHelper.canWalkOn(context, lx, y - 1, lz, landing) || !checkOvershootSafety(context.bsi, lx + xDiff, y, lz + zDiff)) {
            return false;
        }
        res.x = lx;
        res.y = y;
        res.z = lz;
        res.cost = costFromJumpDistance(5) + cost + context.jumpPenalty;
        return true;
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
        for (int i = ext > 0 ? -7 : 0; i <= dist; i++) { // an extended jump backs up along the line it came from
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
    public void reset() {
        super.reset();
        ranUp = false;
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

        BetterBlockPos from = src.relative(direction, ext);
        int jd = dist - ext; // the jump proper, measured from the last extension
        if (ext > 0 && !backed && ctx.player().onGround()) {
            BetterBlockPos missing = null;
            for (int k = 1; k <= ext && missing == null; k++) {
                BetterBlockPos cell = src.relative(direction, k).below();
                if (!MovementHelper.canWalkOn(ctx, cell)) {
                    missing = cell;
                }
            }
            if (missing != null) {
                return extend(state, missing);
            }
            // all down: back up along the extension for the run-up the 4 block jump needs, then run at it
            int k = 0;
            while (k < 7 && MovementHelper.canWalkOn(ctx, src.relative(direction, -(k + 1)).below())
                    && MovementHelper.fullyPassable(ctx, src.relative(direction, -(k + 1)))
                    && MovementHelper.fullyPassable(ctx, src.relative(direction, -(k + 1)).above())) {
                k++;
            }
            BetterBlockPos start = src.relative(direction, -k);
            double along = ctx.player().position().x * direction.getStepX() + ctx.player().position().z * direction.getStepZ();
            double startCentre = (start.x + 0.5) * direction.getStepX() + (start.z + 0.5) * direction.getStepZ();
            float travelYaw = (float) Math.toDegrees(Math.atan2(-direction.getStepX(), direction.getStepZ()));
            state.setTarget(new MovementState.MovementTarget(new Rotation(travelYaw, 0), true));
            if (k > 0 && along > startCentre + 0.15 && !runUpLands(travelYaw)) {
                state.setInput(Input.MOVE_BACK, true); // too short a run to clear the gap from here: back up further
                return state;
            }
            float yawErr = Math.abs(Mth.wrapDegrees(ctx.player().getYRot() - travelYaw));
            if (yawErr > 8 || Math.abs(ctx.player().getXRot()) > 25) {
                return state; // the camera has to be on the line before W and the jump
            }
            backed = true;
        }

        if (dist == 5 && !ascend && !ranUp) {
            // a 4 block gap needs a run-up of two blocks at full sprint: standing (or crawling) on the take-off block,
            // back up to the start of whatever run-up there is first
            BetterBlockPos back = src.relative(direction, -1), back2 = src.relative(direction, -2);
            boolean two = MovementHelper.canWalkOn(ctx, back2.below()) && MovementHelper.fullyPassable(ctx, back2) && MovementHelper.fullyPassable(ctx, back2.above());
            BetterBlockPos runFrom = two ? back2 : back;
            double v = ctx.player().getDeltaMovement().x * direction.getStepX() + ctx.player().getDeltaMovement().z * direction.getStepZ();
            double along = (ctx.player().position().x - (runFrom.x + 0.5)) * direction.getStepX() + (ctx.player().position().z - (runFrom.z + 0.5)) * direction.getStepZ();
            if (along <= -0.3 || v > 0.1 || ctx.player().position().y > src.y + 0.1 || !ctx.playerFeet().equals(src) && !ctx.playerFeet().equals(back) && !ctx.playerFeet().equals(runFrom)) {
                ranUp = true;
            } else {
                // keep facing the landing and walk backwards, so there is no turn to lag behind
                state.setInput(Input.SPRINT, false);
                MovementHelper.moveTowards(ctx, state, dest);
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, true);
                return state;
            }
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
        } else if (!ctx.playerFeet().equals(from)) {
            if (ctx.playerFeet().equals(from.relative(direction)) || ctx.player().position().y - src.y > 0.0001) {
                if (Baritone.settings().allowPlace.value // see PR #3775
                        && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()
                        && !MovementHelper.canWalkOn(ctx, dest.below())
                        && !ctx.player().onGround()
                        && MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false) == PlaceResult.READY_TO_PLACE
                ) {
                    // go in the opposite order to check DOWN before all horizontals -- down is preferable because you don't have to look to the side while in midair, which could mess up the trajectory
                    state.setInput(Input.CLICK_RIGHT, true);
                }
                if (ext > 0 && jd == 5 && ctx.player().onGround()) {
                    // an extended jump leaves from the very edge: wait while a jump one tick later still lands
                    float yaw = (float) Math.toDegrees(Math.atan2(-direction.getStepX(), direction.getStepZ()));
                    if (jumpLands(yaw, 1, true)) {
                        return state;
                    }
                } else if ((jd == 3 || jd == 5) && !ascend) { // 2 or 4 block gap: jump from the very edge
                    double xDiff = (from.x + 0.5) - ctx.player().position().x;
                    double zDiff = (from.z + 0.5) - ctx.player().position().z;
                    double distFromStart = Math.max(Math.abs(xDiff), Math.abs(zDiff));
                    // a 4 block gap jumps on the last tick still on the block: wait while the next step stays on it
                    double speed = jd == 5 ? Math.max(Math.abs(ctx.player().getDeltaMovement().x), Math.abs(ctx.player().getDeltaMovement().z)) / 0.546 : 0;
                    if (jd == 5 ? distFromStart + speed < 1.0 : distFromStart < 0.7) {
                        return state;
                    }
                }

                state.setInput(Input.JUMP, true);
            } else if (!ctx.playerFeet().equals(dest.relative(direction, -1))) {
                state.setInput(Input.SPRINT, jd == 5); // a 4 block gap needs the run-up at full sprint
                if (ctx.playerFeet().equals(from.relative(direction, -1))) {
                    MovementHelper.moveTowards(ctx, state, from);
                } else {
                    MovementHelper.moveTowards(ctx, state, from.relative(direction, -1));
                }
            }
        }
        return state;
    }

    private boolean pastExtensions(BetterBlockPos from) {
        for (int k = 1; k <= ext; k++) {
            if (!MovementHelper.canWalkOn(ctx, src.relative(direction, k).below())) {
                return false;
            }
        }
        double along = ctx.player().position().x * direction.getStepX() + ctx.player().position().z * direction.getStepZ();
        double fromCentre = (from.x + 0.5) * direction.getStepX() + (from.z + 0.5) * direction.getStepZ();
        return along >= fromCentre - 0.3;
    }

    private boolean jumpLands(float yaw, int delay, boolean live) {
        if (sim == null) {
            sim = new PlayerSim(new ClientWorld(ctx));
        }
        LocalPlayer p = ctx.player();
        sim.x = p.getX(); sim.y = p.getY(); sim.z = p.getZ();
        sim.vx = p.getDeltaMovement().x; sim.vy = p.getDeltaMovement().y; sim.vz = p.getDeltaMovement().z;
        sim.onGround = p.onGround();
        sim.sprinting = p.isSprinting();
        sim.collidedH = false;
        sim.jumpTicks = 0;
        boolean left = false;
        for (int t = 0; t < 60; t++) {
            sim.tick(yaw, true, true, t == delay);
            if (!sim.onGround) {
                left = true;
            } else if (left || t > delay + 2) {
                return left && Math.abs(sim.y - dest.y) < 0.01 && landsOn(sim.x, sim.z);
            }
            if (sim.y < dest.y - 1.5) {
                return false;
            }
        }
        return false;
    }

    private boolean landsOn(double x, double z) {
        return MovementHelper.canWalkOn(ctx, new BetterBlockPos(PlayerSim.floor(x), dest.y - 1, PlayerSim.floor(z)));
    }

    /** Is there any moment along the run-up at which jumping would clear the gap? */
    private boolean runUpLands(float yaw) {
        for (int d = 0; d < 40; d++) {
            if (jumpLands(yaw, d, false)) {
                return true;
            }
        }
        return false;
    }

    /** Creep to the edge sneaking, lay the next block in front of it, repeat until the whole extension is down. */
    private MovementState extend(MovementState state, BetterBlockPos target) {
        double along = ctx.player().position().x * direction.getStepX() + ctx.player().position().z * direction.getStepZ();
        double edge = (target.x + 0.5) * direction.getStepX() + (target.z + 0.5) * direction.getStepZ() - 0.5; // the near side of the cell to fill
        state.setInput(Input.SNEAK, true);
        ((baritone.Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, target.getX(), target.getY(), target.getZ());
        BetterBlockPos against = target.relative(direction, -1);
        Direction face = direction;
        Vec3 eye = RayTraceUtils.inferSneakingEyePosition(ctx.player());
        Rotation aim = null;
        // a point on the face of the block behind the cell, the top of the block edge first, nudged sideways if hidden
        for (double rel : new double[]{0, -0.3, 0.3}) {
            for (double v : new double[]{-0.25, -0.5, -0.75}) {
                Vec3 pt = new Vec3(against.x + 0.5 + face.getStepX() * 0.5 + (face.getStepX() == 0 ? rel : 0) + face.getStepX() * 0.001,
                        against.y + 1 + v,
                        against.z + 0.5 + face.getStepZ() * 0.5 + (face.getStepZ() == 0 ? rel : 0) + face.getStepZ() * 0.001);
                Rotation rot = RotationUtils.calcRotationFromVec3d(eye, pt, ctx.playerRotations());
                HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), rot, ctx.playerController().getBlockReachDistance(), true);
                if (hit instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(against) && h.getDirection() == face) {
                    aim = rot;
                    break;
                }
            }
            if (aim != null) {
                break;
            }
        }
        if (aim != null) {
            state.setTarget(new MovementState.MovementTarget(aim, true));
            if (ctx.player().isCrouching()) {
                HitResult hit = ctx.objectMouseOver();
                if (hit instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(against) && h.getDirection() == face) {
                    state.setInput(Input.CLICK_RIGHT, true);
                    return state;
                }
            }
        }
        if (along < edge + 0.2) {
            // shuffle toward the edge relative to where the camera looks: W/A/S/D follow the head
            double travel = Math.toDegrees(Math.atan2(-direction.getStepX(), direction.getStepZ()));
            float lookYaw = aim != null ? aim.getYaw() : ctx.player().getYRot();
            double rel = Math.toRadians(travel - lookYaw);
            double fwd = Math.cos(rel), left = -Math.sin(rel);
            state.setInput(Input.MOVE_FORWARD, fwd > 0.38);
            state.setInput(Input.MOVE_BACK, fwd < -0.38);
            state.setInput(Input.MOVE_LEFT, left > 0.38);
            state.setInput(Input.MOVE_RIGHT, left < -0.38);
        }
        return state;
    }
}
