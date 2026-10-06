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
import baritone.Baritone;
import baritone.behavior.InventoryBehavior;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import net.minecraft.world.entity.vehicle.AbstractBoat;

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
        hurt |= result.damage > 0;
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

    private enum FallMode {
        NONE, BUCKET, CLUTCH, HURT
    }

    // a deliberate fall that hurts (experimentalMovement). nobody is saving us, we just fall. sticky, because the plan
    // changing under us at the edge (health went down) is the one thing we check for, see updateState
    private boolean hurt;

    // the clutch ran out of things to try (nothing in reach in time, nothing to hang on), so it's the bucket or nothing
    private boolean clutchGaveUp;

    // the ladder we right-clicked onto the wall, and whether the world agreed that it's there. vines drop nothing without
    // shears so they're never recorded, and a ladder somebody else put up never is either, it's theirs
    private BlockPos ladder;
    private boolean ladderIn;
    // our own clock, so the pickup can give up. pickupStart is -1 until we land with a ladder to fetch, goneAt is the tick
    // the block disappeared and the drop starts counting
    private int ticks, clickedAt, pickupStart = -1, goneAt = -1, pickupMisses;
    // the click lands a tick after we ask for it, so give the block a few before deciding the click missed
    private static final int LADDER_CONFIRM_TICKS = 4;
    // all in, mining plus waiting on the drop. a stuck movement is worse than a lost ladder
    private static final int PICKUP_TICKS = 60;
    // drops can't be picked up for 10 ticks, then it's however long the walk takes
    private static final int DROP_TICKS = 30;
    private static final int PICKUP_MAX_MISSES = 10;

    @Override
    public void reset() {
        super.reset();
        clutchGaveUp = false;
        hurt = false;
        ladder = null;
        ladderIn = false;
        ticks = clickedAt = pickupMisses = 0;
        pickupStart = goneAt = -1;
    }

    private FallMode fallMode() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        if (MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result)) {
            return FallMode.BUCKET;
        }
        if (result.damage > 0) {
            hurt = true;
            return FallMode.HURT; // no bucket, no clutch, nothing to place, so nothing to get wrong either
        }
        if (!result.clutch) {
            return FallMode.NONE;
        }
        if (clutchGaveUp) {
            return context.hasWaterBucket ? FallMode.BUCKET : FallMode.NONE;
        }
        return FallMode.CLUTCH;
    }

    // the same question the planner asked, with the health we have right now
    private boolean stillPossible() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        return result.y == dest.y && result.cost < COST_INF;
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
        ticks++;
        confirmLadder();

        BlockPos playerFeet = ctx.playerFeet();
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations());
        Rotation targetRotation = null;
        BlockState destState = ctx.world().getBlockState(dest);
        Block destBlock = destState.getBlock();

        if (ctx.world().getBlockState(dest.below()).is(Blocks.MAGMA_BLOCK) && MovementHelper.steppingOnBlocks(ctx).stream().allMatch(block -> MovementHelper.canWalkThrough(ctx, block))) {
            state.setInput(Input.SNEAK, true);
        }

        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        if (hurt && !isWater && playerFeet.equals(src) && ctx.player().onGround() && !stillPossible()) {
            // we got hurt (or something else dropped our health) since the plan was made, and this fall would now cost us
            // more than we agreed to. nothing has happened yet, so the executor can just replan
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        FallMode mode = isWater ? FallMode.NONE : fallMode();
        if (mode == FallMode.BUCKET && !playerFeet.equals(dest) && !AltoClefSettings.getInstance().shouldNotPlaceBucketButStillFall()) {
            if (!Inventory.isHotbarSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) || ctx.world().dimension() == Level.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (ctx.player().position().y - dest.getY() < ctx.playerController().getBlockReachDistance() && !ctx.player().onGround()) {
                ctx.player().getInventory().selected = ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER);

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
                    ctx.player().getInventory().selected = ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_EMPTY);
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
                return pickUpLadder(state) ? state.setStatus(MovementStatus.SUCCESS) : state;
            }
        }
        if (mode == FallMode.CLUTCH && !playerFeet.equals(dest) && clutch(state)) {
            return state; // it's doing the steering, the centering below would fight it
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
                net.minecraft.world.InteractionResult r = Minecraft.getInstance().gameMode.interact(ctx.player(), boat, InteractionHand.MAIN_HAND);
                if (boatTicks % 40 == 3) logDebug("boat fall: board " + r + " riding=" + ctx.player().getVehicle());
            }
            return state;
        }
        int slot = BoatUtil.hotbarBoat(ctx);
        if (slot < 0) {
            logDebug("boat fall: no boat " + ctx.player().position() + " ticks=" + boatTicks);
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        ctx.player().getInventory().selected = slot;
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

    // the click is a guess until the block shows up. it was air when we clicked, so a ladder there later is ours
    private void confirmLadder() {
        if (ladder == null || ladderIn) {
            return;
        }
        if (ctx.world().getBlockState(ladder).is(Blocks.LADDER)) {
            ladderIn = true;
        } else if (ticks - clickedAt > LADDER_CONFIRM_TICKS) {
            ladder = null; // the click missed, nothing to fetch
        }
    }

    // after landing: break the ladder we hung on the wall, then stand in the drop until it's in the inventory (same idea as
    // the bucket pickup, the movement isn't done until the thing we spent is back). returns true once the movement can succeed.
    // every way this can go wrong ends in true, a lost ladder beats a path that never moves again
    private boolean pickUpLadder(MovementState state) {
        if (ladder == null || !Baritone.settings().pickupLadders.value) {
            return true;
        }
        if (!ladderIn) {
            return false; // landed before the click showed up, confirmLadder settles it within a few ticks
        }
        if (pickupStart < 0) {
            pickupStart = ticks;
        }
        if (ticks - pickupStart > PICKUP_TICKS) {
            return true;
        }
        BlockState there = ctx.world().getBlockState(ladder);
        if (there.is(Blocks.LADDER)) {
            MovementHelper.switchToBestToolFor(ctx, there);
            Optional<Rotation> look = RotationUtils.reachable(ctx, ladder, ctx.playerController().getBlockReachDistance());
            if (look.isEmpty()) {
                return ++pickupMisses > PICKUP_MAX_MISSES; // behind something, or the look is still catching up
            }
            pickupMisses = 0;
            state.setTarget(new MovementTarget(look.get(), true));
            if (ctx.isLookingAt(ladder) || ctx.playerRotations().isReallyCloseTo(look.get())) {
                state.setInput(Input.CLICK_LEFT, true);
            }
            return false;
        }
        if (goneAt < 0) {
            goneAt = ticks;
        }
        // a drop can't be picked up for 10 ticks, and the pickup box is a block wider than us. nobody to wait for means
        // creative, a full inventory that ate it already, or the gamerule being off
        Vec3 me = ctx.player().position();
        ItemEntity drop = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity item : ctx.world().getEntitiesOfClass(ItemEntity.class, new AABB(ladder).inflate(1.5), e -> e.getItem().is(Items.LADDER))) {
            double d = Math.hypot(item.getX() - me.x, item.getZ() - me.z);
            if (d < best) {
                best = d;
                drop = item;
            }
        }
        if (drop == null || ticks - goneAt > DROP_TICKS) {
            return true;
        }
        if (best > 1 && floorToward(drop.position(), me)) {
            // rolled out of reach, go and stand on it
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), drop.position(), ctx.playerRotations()), false));
            state.setInput(Input.MOVE_FORWARD, true);
        }
        return false;
    }

    // a ladder is not worth a walk off the ledge, so only chase the drop over floor. a block ahead and the drop's own
    // block both need something to stand on, otherwise we sit here and let the timeout end it
    private boolean floorToward(Vec3 drop, Vec3 me) {
        double dx = drop.x - me.x;
        double dz = drop.z - me.z;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6) {
            return true;
        }
        int y = ctx.playerFeet().getY();
        BlockPos ahead = BlockPos.containing(me.x + dx / len * 0.8, y, me.z + dz / len * 0.8);
        BlockPos there = BlockPos.containing(drop.x, y, drop.z);
        return MovementHelper.canWalkOn(ctx, ahead.below()) && MovementHelper.canWalkOn(ctx, there.below());
    }

    // ladder clutch, one tick of it. the whole idea: a vine or ladder in one of the last few cells before the floor wipes the
    // fall distance (see LadderClutch for how, there's two ways and one of them works at terminal velocity). minecraft picks
    // what's under the crosshair at the start of a tick, before our onTick, and then does handleKeybinds (the click, so
    // the block is in the world) before the player ticks. so the click goes on the tick we want the climbable to exist for,
    // and the aim goes on the tick before that, pointed from where our eye is going to be
    // returns true if it took over the inputs this tick
    private boolean clutch(MovementState state) {
        LocalPlayer player = ctx.player();
        if (player.onGround() || player.getDeltaMovement().y >= 0) {
            return false;
        }
        InventoryBehavior inv = ((Baritone) baritone).getInventoryBehavior();
        Item item = inv.pickClutchItem(false);
        if (item == null) {
            return false; // the last one's already in the wall, or somebody ate the vines
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Vec3 pos = player.position();
        Vec3 mot = player.getDeltaMovement();
        int mask = 0;
        for (int k = 0; k < LadderClutch.CELLS && dest.y + k < src.y; k++) {
            BlockState there = bsi.get0(dest.above(k));
            if (MovementHelper.isClimbable(there.getBlock())) {
                return false; // already one in here (us, a tick ago), physics takes it from here
            }
            if (there.isAir() && !AltoClefSettings.getInstance().shouldAvoidPlacingAt(dest.getX(), dest.getY() + k, dest.getZ()) && !walls(bsi, dest.above(k), pos, false).isEmpty()) {
                mask |= 1 << k;
            }
        }
        double reach = ctx.playerController().getBlockReachDistance();
        // what's under the crosshair right now is exactly what a click will hit
        BlockHitResult hit = ctx.objectMouseOver() instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
        int aimed = hit == null ? -1 : aimedCell(bsi, hit, mask);
        if (aimed >= 0) {
            LadderClutch.Plan now = LadderClutch.pick(pos.y, mot.y, player.fallDistance, dest.y, 1 << aimed, 0, reach);
            boolean roomy = item != Items.LADDER || gap(dest.above(aimed), hit.getDirection().getOpposite(), pos) >= LadderClutch.LADDER_CLEARANCE + 0.01;
            if (now != null && now.place() == 0 && roomy) {
                inv.pickClutchItem(true);
                if (item == Items.LADDER && !ladderIn) {
                    BlockPos placed = dest.above(aimed);
                    ladder = new BlockPos(placed.getX(), placed.getY(), placed.getZ()); // no BetterBlockPos in vanilla's hands
                    clickedAt = ticks;
                }
                state.setTarget(new MovementTarget(ctx.playerRotations(), true));
                state.setInput(Input.CLICK_RIGHT, true);
                return true;
            }
        }
        LadderClutch.Plan plan = LadderClutch.pick(pos.y, mot.y, player.fallDistance, dest.y, mask, 1, reach);
        if (plan == null) {
            clutchGaveUp = true;
            return false;
        }
        BlockPos cell = dest.above(plan.cell());
        boolean ladder = item == Items.LADDER;
        if (plan.place() > 1) {
            if (ladder && plan.place() > 3) {
                // sneaking is a third of the air control, and the ladder needs us about 0.6 off the wall (see LadderClutch.WALL_GAP)
                // not within the last couple ticks though, the crouch is a lower eye and the aim is worked out for a standing one
                List<Direction> sides = walls(bsi, cell, pos, false);
                steer(state, cell, sides.get(0), pos, mot);
            }
            return ladder; // a vine doesn't care where we are, the plain centering is fine
        }
        inv.pickClutchItem(true);
        return aim(state, bsi, cell, ladder, pos, mot, reach);
    }

    // line the crosshair up with the wall next to this cell for the next tick, and only if the ray from where we'll be
    // really ends up on the face (same check as attemptToPlaceABlock, except from the next eye instead of this one)
    private boolean aim(MovementState state, BlockStateInterface bsi, BlockPos cell, boolean ladder, Vec3 pos, Vec3 mot, double reach) {
        Vec3 eye = new Vec3(pos.x + mot.x, pos.y + mot.y + ctx.player().getEyeHeight(), pos.z + mot.z);
        Vec3 feet = new Vec3(eye.x, pos.y + mot.y, eye.z);
        for (Direction side : walls(bsi, cell, feet, ladder)) {
            for (double height : AIM_HEIGHTS) {
                Rotation want = RotationUtils.calcRotationFromVec3d(eye, faceSpot(cell, side, eye, height), ctx.playerRotations());
                Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(want);
                Vec3 dir = RotationUtils.calcLookDirectionFromRotation(actual);
                HitResult res = ctx.world().clip(new ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
                if (res instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(cell.relative(side)) && b.getDirection() == side.getOpposite()) {
                    state.setTarget(new MovementTarget(want, true));
                    return true;
                }
            }
        }
        // nothing lines up. sit tight, if it stays that way the plan runs out of ticks and the clutch gives up
        return true;
    }

    private static final double[] AIM_HEIGHTS = {LadderClutch.AIM_HEIGHT, 0.5, 0.2};

    // a hair into the wall, so the ray ends up past the face instead of rounding off short of it
    private static Vec3 faceSpot(BlockPos cell, Direction side, Vec3 eye, double height) {
        double x = side.getStepX() != 0 ? cell.getX() + 0.5 + 0.51 * side.getStepX() : Mth.clamp(eye.x, cell.getX() + 0.15, cell.getX() + 0.85);
        double z = side.getStepZ() != 0 ? cell.getZ() + 0.5 + 0.51 * side.getStepZ() : Mth.clamp(eye.z, cell.getZ() + 0.15, cell.getZ() + 0.85);
        return new Vec3(x, cell.getY() + height, z);
    }

    // which cell a click on the crosshair would put something in, if that's one we planned for
    private int aimedCell(BlockStateInterface bsi, BlockHitResult hit, int mask) {
        Direction face = hit.getDirection();
        BlockPos cell = hit.getBlockPos().relative(face);
        int k = cell.getY() - dest.y;
        if (!face.getAxis().isHorizontal() || cell.getX() != dest.x || cell.getZ() != dest.z || k < 0 || k >= LadderClutch.CELLS || (mask & (1 << k)) == 0) {
            return -1;
        }
        BlockPos wall = hit.getBlockPos();
        return MovementHelper.canPlaceAgainst(bsi, wall) && MovementDescend.clutchWall(bsi.get0(wall)) ? k : -1;
    }

    // walls we could hang something on next to this cell, best first. a ladder is only roomy if we're not so close to the wall
    // that its slab would be inside our hitbox (the placement gets refused, or we'd land on top of it)
    private List<Direction> walls(BlockStateInterface bsi, BlockPos cell, Vec3 pos, boolean roomy) {
        List<Direction> out = new ArrayList<>(4);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos wall = cell.relative(side);
            if (MovementHelper.canPlaceAgainst(bsi, wall) && MovementDescend.clutchWall(bsi.get0(wall)) && (!roomy || gap(cell, side, pos) >= LadderClutch.LADDER_CLEARANCE + 0.02)) {
                out.add(side);
            }
        }
        out.sort(Comparator.comparingDouble(side -> Math.abs(gap(cell, side, pos) - LadderClutch.WALL_GAP)));
        return out;
    }

    // how far we are from the wall plane on this side of the cell
    private static double gap(BlockPos cell, Direction side, Vec3 pos) {
        if (side.getStepX() != 0) {
            return side.getStepX() * (cell.getX() + 0.5 + 0.5 * side.getStepX() - pos.x);
        }
        return side.getStepZ() * (cell.getZ() + 0.5 + 0.5 * side.getStepZ() - pos.z);
    }

    // walk to the spot WALL_GAP off the wall and in the middle along it. air drag is 0.91, so with no more input we'd coast
    // v / 0.09 further, that's the spot we judge
    private void steer(MovementState state, BlockPos cell, Direction side, Vec3 pos, Vec3 mot) {
        double goalX = cell.getX() + 0.5 + side.getStepX() * (0.5 - LadderClutch.WALL_GAP);
        double goalZ = cell.getZ() + 0.5 + side.getStepZ() * (0.5 - LadderClutch.WALL_GAP);
        double ex = goalX - (pos.x + mot.x / 0.09);
        double ez = goalZ - (pos.z + mot.z / 0.09);
        if (Math.hypot(ex, ez) < 0.03) {
            return;
        }
        Vec3 head = ctx.playerHead();
        state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(head, new Vec3(pos.x + ex, head.y, pos.z + ez), ctx.playerRotations()), false));
        state.setInput(Input.MOVE_FORWARD, true);
        state.setInput(Input.SNEAK, true);
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
        // fetching the ladder is on the ground, nothing to lose but a ladder
        return ctx.playerFeet().equals(src) || state.getStatus() != MovementStatus.RUNNING || pickupStart >= 0;
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
