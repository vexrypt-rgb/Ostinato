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

import baritone.utils.BoatUtil;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class MovementFall extends Movement {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
    private static final ItemStack STACK_BUCKET_EMPTY = new ItemStack(Items.BUCKET);

    /** True while a boat fall is driving; BoatProcess keeps its hands off until we land. */
    public static volatile boolean boatRide;
    /** Tick boatRide was last refreshed; a claim older than a few ticks (movement cancelled) lapses. */
    public static volatile int boatRideTick;

    public static boolean boatRideClaimed(int now) {
        return boatRide && now - boatRideTick <= 5;
    }
    private Boolean boatMode;
    private int boatTicks;

    public MovementFall(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, MovementFall.buildPositionsToBreak(src, dest));
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is a descend not a fall
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            set.add(dest.above(y));
        }
        return set;
    }

    private boolean willPlaceBucket() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        return MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (boatMode == null) {
            CalculationContext c = new CalculationContext(baritone);
            boatMode = willPlaceBucket() && !(c.hasWaterBucket && src.y - dest.y <= c.maxFallHeightBucket + 1)
                    && !MovementHelper.isWater(ctx.world().getBlockState(dest));
        }
        if (boatMode) {
            // Claim any boat ride for this movement from the start, so BoatProcess doesn't see us seated
            // on land and climb straight back out (which also puts a 60-tick cooldown on boarding).
            boatRide = true;
            boatRideTick = ctx.player().tickCount;
            boatFall(state);
            boatRide = state.getStatus() == MovementStatus.RUNNING;
            return state;
        }
        BlockPos playerFeet = ctx.playerFeet();
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations());
        Rotation targetRotation = null;
        BlockState destState = ctx.world().getBlockState(dest);
        Block destBlock = destState.getBlock();

        if (ctx.world().getBlockState(dest.below()).is(Blocks.MAGMA_BLOCK) && MovementHelper.steppingOnBlocks(ctx).stream().allMatch(block -> MovementHelper.canWalkThrough(ctx, block))) {
            state.setInput(Input.SNEAK, true);
        }

        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        if (!isWater && willPlaceBucket() && !playerFeet.equals(dest) && !AltoClefSettings.getInstance().shouldNotPlaceBucketButStillFall()) {
            if (!Inventory.isHotbarSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) || ctx.world().dimension() == Level.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (ctx.player().position().y - dest.getY() < ctx.playerController().getBlockReachDistance() && !ctx.player().onGround()) {
                ctx.player().getInventory().setSelectedSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER));

                targetRotation = new Rotation(toDest.getYaw(), 90.0F);

                if (ctx.isLookingAt(dest) || ctx.isLookingAt(dest.below())) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
        }
        if (targetRotation != null) {
            state.setTarget(new MovementTarget(targetRotation, true));
        } else {
            state.setTarget(new MovementTarget(toDest, false));
        }
        if (playerFeet.equals(dest) && (ctx.player().position().y - playerFeet.getY() < 0.094 || isWater)) { // 0.094 because lilypads
            if (isWater) { // only match water, not flowing water (which we cannot pick up with a bucket)
                if (Inventory.isHotbarSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_EMPTY))) {
                    ctx.player().getInventory().setSelectedSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_EMPTY));
                    if (ctx.player().getDeltaMovement().y >= 0) {
                        return state.setInput(Input.CLICK_RIGHT, true);
                    } else {
                        return state;
                    }
                } else {
                    if (ctx.player().getDeltaMovement().y >= 0) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    } // don't else return state; we need to stay centered because this water might be flowing under the surface
                }
            } else {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest); // we are moving to the 0.5 center not the edge (like if we were falling on a ladder)
        if (Math.abs(ctx.player().position().x + ctx.player().getDeltaMovement().x - destCenter.x) > 0.1 || Math.abs(ctx.player().position().z + ctx.player().getDeltaMovement().z - destCenter.z) > 0.1) {
            if (!ctx.player().onGround() && Math.abs(ctx.player().getDeltaMovement().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        Vec3i avoid = Optional.ofNullable(avoid()).map(Direction::getUnitVec3i).orElse(null);
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - ctx.player().position().x)) + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - ctx.player().position().z));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!ctx.player().onGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        if (targetRotation == null) {
            Vec3 destCenterOffset = new Vec3(destCenter.x + 0.125 * avoid.getX(), destCenter.y, destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), destCenterOffset, ctx.playerRotations()), false));
        }
        return state;
    }

    /**
     * Too high to fall on foot: put a boat down where we stand, get in and drive off the edge. The boat
     * soaks up the landing; once down, BoatProcess climbs out, breaks it and picks it back up.
     */
    private MovementState boatFall(MovementState state) {
        Entity v = ctx.player().getVehicle();
        if (v instanceof AbstractBoat) {
            if (!BoatUtil.isDriver(ctx.player()) || BoatUtil.mobAboard(ctx.player())) {
                // Someone else got in first (or a mob did: we'd steer, but not with it aboard) and steers: get out and don't ride their boat off the cliff.
                boatRide = false;
                logDebug("boat fall: not the driver, getting out");
                state.setInput(Input.SNEAK, true);
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            boatRide = true;
            BoatUtil.restore(ctx);
            if (v.onGround() && Math.abs(v.getY() - dest.getY()) < 0.7) {
                boatRide = false;
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (!v.onGround()) {
                return state; // falling; nothing to steer
            }
            double tx = dest.getX() + 0.5 - v.getX(), tz = dest.getZ() + 0.5 - v.getZ();
            float want = (float) (Mth.atan2(tz, tx) * 180 / Math.PI) - 90;
            float diff = Mth.wrapDegrees(want - v.getYRot());
            if (diff > 4) state.setInput(Input.MOVE_RIGHT, true);
            if (diff < -4) state.setInput(Input.MOVE_LEFT, true);
            if (Math.abs(diff) < 50) state.setInput(Input.MOVE_FORWARD, true);
            if (boatTicks++ > 200) {
                boatRide = false;
                logDebug("boat fall: stuck riding at edge " + ctx.player().position() + " ticks=" + boatTicks);
            return state.setStatus(MovementStatus.UNREACHABLE);
            }
            return state.setTarget(new MovementTarget(new Rotation(want, 10), true));
        }
        if (nearSrc() > 2 && ctx.player().onGround() && boatTicks < 60 && ctx.playerFeet().getY() >= src.getY()) {
            // Handed the movement a step early/late: walk back onto src first.
            boatTicks++;
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(src), ctx.playerRotations()), false));
            return state.setInput(Input.MOVE_FORWARD, true);
        }
        if (nearSrc() > 2 || boatTicks++ > 160) {
            logDebug("boat fall: couldn't place/mount " + ctx.player().position() + " src=" + src + " dest=" + dest + " ticks=" + boatTicks);
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        Entity boat = null;
        for (Entity e : ctx.entities()) {
            if (BoatUtil.free(e) && ctx.player().distanceTo(e) < 3
                    && (boat == null || ctx.player().distanceTo(e) < ctx.player().distanceTo(boat))) boat = e;
        }
        if (boat != null) {
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(boat.getX(), boat.getY() + 0.3, boat.getZ()), ctx.playerRotations()), true));
            if (boatTicks % 4 == 3) {
                net.minecraft.world.InteractionResult r = Minecraft.getInstance().gameMode.interact(ctx.player(), boat, new net.minecraft.world.phys.EntityHitResult(boat), InteractionHand.MAIN_HAND);
                if (boatTicks % 40 == 3) logDebug("boat fall: board " + r + " riding=" + ctx.player().getVehicle());
            }
            return state;
        }
        int slot = BoatUtil.hotbarBoat(ctx);
        if (slot < 0) {
            logDebug("boat fall: no boat " + ctx.player().position() + " ticks=" + boatTicks);
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        ctx.player().getInventory().setSelectedSlot(slot);
        // Boats can't overlap us. With headroom, jump and place underneath at the top of the jump;
        // in a 1-2 high space, back off src and place on its far half instead.
        if (ctx.playerFeet().equals(src) && MovementHelper.canWalkThrough(ctx, src.above(2)) && MovementHelper.canWalkThrough(ctx, src.above(3))) {
            state.setTarget(new MovementTarget(new Rotation(ctx.playerRotations().getYaw(), 90), true));
            if (ctx.player().onGround()) {
                state.setInput(Input.JUMP, true);
            } else if (ctx.player().getY() - src.getY() > 0.7) {
                Minecraft.getInstance().gameMode.useItem(ctx.player(), InteractionHand.MAIN_HAND);
            }
            return state;
        }
        double dx = Math.signum(dest.getX() - src.getX()), dz = Math.signum(dest.getZ() - src.getZ());
        Vec3 aim = new Vec3(src.getX() + 0.5 + dx * 0.35, src.getY(), src.getZ() + 0.5 + dz * 0.35);
        state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations()), true));
        double back = (src.getX() + 0.5 - ctx.player().getX()) * dx + (src.getZ() + 0.5 - ctx.player().getZ()) * dz;
        if (back < 0.8) {
            state.setInput(Input.MOVE_BACK, true);
        } else if (boatTicks % 5 == 4) {
            Minecraft.getInstance().gameMode.useItem(ctx.player(), InteractionHand.MAIN_HAND);
        }
        return state;
    }

    // Block-distance² from src (Vector3i.distanceSq offsets one side by 0.5, which trips on a jump).
    private int nearSrc() {
        BlockPos f = ctx.playerFeet();
        int x = f.getX() - src.getX(), y = f.getY() - src.getY(), z = f.getZ() - src.getZ();
        return x * x + y * y + z * z;
    }

    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = ctx.world().getBlockState(ctx.playerFeet().below(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.getValue(LadderBlock.FACING);
            }
        }
        return null;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we haven't started walking off the edge yet, or if we're in the process of breaking blocks before doing the fall
        // then it's safe to cancel this
        if (ctx.player().getVehicle() instanceof AbstractBoat) {
            return state.getStatus() != MovementStatus.RUNNING;
        }
        // Boat placed but not mounted yet: it's no longer in the inventory, so a cost recheck says
        // "impossible". Don't let that cancel us mid-mount.
        if (Boolean.TRUE.equals(boatMode) && boatTicks > 0 && state.getStatus() == MovementStatus.RUNNING) {
            return false;
        }
        return ctx.playerFeet().equals(src) || state.getStatus() != MovementStatus.RUNNING;
    }

    private static BetterBlockPos[] buildPositionsToBreak(BetterBlockPos src, BetterBlockPos dest) {
        BetterBlockPos[] toBreak;
        int diffX = src.getX() - dest.getX();
        int diffZ = src.getZ() - dest.getZ();
        int diffY = Math.abs(src.getY() - dest.getY());
        toBreak = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BetterBlockPos(src.getX() - diffX, src.getY() + 1 - i, src.getZ() - diffZ);
        }
        return toBreak;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // only break if one of the first three needs to be broken
        // specifically ignore the last one which might be water
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelper.canWalkThrough(ctx, positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
