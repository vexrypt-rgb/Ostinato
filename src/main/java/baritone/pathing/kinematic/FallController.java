package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.InputArbiter;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Slow kinematic, long falls: walks off the edge towards the landing column, steers in the air with W, and when the
 * fall is past what the player survives and the landing is not already water, looks straight down at the ground,
 * places a water bucket and picks it up again once it has landed in it (the water-bucket clutch). The camera moves
 * with the humanized mouse. Boat falls and falls without a bucket stay with Baritone.
 */
public final class FallController {

    private final IPlayerContext ctx;
    private int ticks;
    /** Water went down this fall and the bucket is not back yet; outlives the fall movement itself. */
    private static volatile boolean placedWater; // static: a replan during the fall builds a new executor and controller
    /** Ticks driven, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;
    /** Ostinato: lease while inputArbitration is on. The flag-off path never touches it. */
    private InputArbiter.Lease lease;
    private long heldEpoch;
    /** True after a flag-on frame reached the keys, so handing the tick back clears those keys. */
    private boolean wrote;

    public FallController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** @return the path position to continue from if the controller drove this tick, or -1 to let the next driver run */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        // Ostinato: off, the body below is the previous direct key write. It does not take a lease.
        if (!Baritone.settings().inputArbitration.value) {
            int r = drive(baritone, path, pathPosition);
            if (r >= 0) {
                drivenTicks++;
            } else {
                ticks = 0;
            }
            return r;
        }
        try {
            int r = drive(baritone, path, pathPosition);
            if (r >= 0) {
                drivenTicks++;
            } else {
                ticks = 0;
                endLease(baritone, false);
            }
            return r;
        } catch (RuntimeException e) {
            ticks = 0;
            endLease(baritone, true);
            throw e;
        }
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || player.isInLava() || player.isFallFlying() || player.isPassenger()
                || player.getAbilities().flying || pathPosition >= path.movements().size()) {
            placedWater = false;
            return -1;
        }
        if (placedWater && (player.onGround() || player.isInWater())) {
            // the executor has already moved on to the next movement by the time the player stands in the water: take the water back first
            Inventory inv = player.getInventory();
            int empty = inv.findSlotMatchingItem(new net.minecraft.world.item.ItemStack(Items.BUCKET));
            int water = inv.findSlotMatchingItem(new net.minecraft.world.item.ItemStack(Items.WATER_BUCKET));
            if (PillarController.trace) {
                System.out.printf("FALL pick water%d empty%d t%d pit %.0f%n", water, empty, ticks, player.getXRot());
            }
            if (Inventory.isHotbarSlot(water) || !Inventory.isHotbarSlot(empty) || ticks++ > 60) {
                placedWater = false;
                ticks = 0;
                return -1;
            }
            inv.setSelectedSlot(empty);
            baritone.getLookBehavior().human();
            baritone.getLookBehavior().updateTarget(new Rotation(player.getYRot(), 90f), true);
            if (!press(baritone, false, false, false, false, false, false, player.getXRot() > 80 && ticks % 3 == 0)) {
                return -1;
            }
            return pathPosition;
        }
        if (!(path.movements().get(pathPosition) instanceof MovementFall fall)) {
            return -1;
        }
        BetterBlockPos src = fall.getSrc(), dest = fall.getDest();
        int drop = src.y - dest.y;
        // a path left over from before a goal change is not the player's fall: it has to be on this one's way down
        if (Math.hypot(player.getX() - (src.x + 0.5), player.getZ() - (src.z + 0.5)) > 4 + fall.steerSpan()
                || player.getY() > src.y + 2 || player.getY() < dest.y - 2) {
            return -1;
        }
        if (drop < 2) {
            return -1;
        }
        CalculationContext cc = new CalculationContext(baritone);
        boolean destWater = MovementHelper.isWater(ctx.world().getBlockState(dest));
        boolean bucket = drop > cc.maxFallHeightNoWater && !destWater;
        if (bucket && !(cc.hasWaterBucket && drop <= cc.maxFallHeightBucket + 1)) {
            return -1; // a boat fall, or no way down alive
        }
        BetterBlockPos feet = ctx.playerFeet();
        Vec3 centre = VecUtils.getBlockPosCenter(dest);
        Inventory inv = player.getInventory();
        int water = inv.findSlotMatchingItem(new net.minecraft.world.item.ItemStack(Items.WATER_BUCKET));
        int empty = inv.findSlotMatchingItem(new net.minecraft.world.item.ItemStack(Items.BUCKET));

        boolean inWater = MovementHelper.isWater(ctx.world().getBlockState(feet));
        // landed: in the water we placed (or the sea) the empty bucket takes it back up, wherever in the column it came down
        if (player.onGround() || inWater) {
            if (inWater && bucket && Inventory.isHotbarSlot(empty) && !Inventory.isHotbarSlot(water) && ticks < 60) {
                ticks++;
                inv.setSelectedSlot(empty);
                baritone.getLookBehavior().human();
                baritone.getLookBehavior().updateTarget(new Rotation(player.getYRot(), 90f), true);
                if (!press(baritone, false, false, false, false, false, false, player.getXRot() > 80 && ticks % 3 == 0)) {
                    return -1;
                }
                return pathPosition;
            }
            if (feet.equals(dest)) {
                if (!press(baritone, false, false, false, false, false, false, false)) {
                    return -1;
                }
                return pathPosition + 1;
            }
        }

        boolean air = !player.onGround();
        double fx = player.getX() + player.getDeltaMovement().x, fz = player.getZ() + player.getDeltaMovement().z;
        boolean off = Math.abs(fx - centre.x) > 0.1 || Math.abs(fz - centre.z) > 0.1;
        if (fall.steered() && !air) {
            // walk off into the fall column, not straight at a landing a few columns over
            centre = new Vec3(fall.fallX() + 0.5, src.y, fall.fallZ() + 0.5);
            off = true;
        }
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), centre, ctx.playerRotations());
        boolean[] drift = fall.steered() && air ? fall.airKeys(player) : null;
        // the camera goes down as soon as the feet leave the ground (the mouse takes a few ticks), and the water goes onto
        // whatever block the crosshair is on once it is within reach: at 3 or more blocks a tick the window is a single tick,
        // so the click is not held up waiting for the landing column in particular
        boolean place = bucket && air && Inventory.isHotbarSlot(water);
        float pitch = place ? 90f : 0f;
        if (place) {
            inv.setSelectedSlot(water);
        }
        baritone.getLookBehavior().human();
        // drifting: keep facing the way the keys were worked out for
        baritone.getLookBehavior().updateTarget(new Rotation(drift != null ? player.getYRot() : toDest.getYaw(), pitch), true);
        boolean forward;
        boolean back;
        boolean left;
        boolean right;
        boolean sneak;
        if (drift != null) {
            forward = drift[0];
            back = drift[1];
            left = drift[2];
            right = drift[3];
            sneak = false;
        } else {
            forward = off;
            back = false;
            left = false;
            right = false;
            // hold sneak while dropping fast so the air steering does not overshoot the column
            sneak = off && air && Math.abs(player.getDeltaMovement().y) > 0.4;
        }
        boolean click = false;
        if (place && player.getXRot() > 80 && ctx.objectMouseOver() instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && hit.getDirection() == net.minecraft.core.Direction.UP
                && ctx.playerHead().distanceTo(hit.getLocation()) < ctx.playerController().getBlockReachDistance()) {
            click = true;
        }
        if (!press(baritone, forward, back, left, right, sneak, false, click)) {
            return -1;
        }
        if (click) {
            placedWater = true;
        }
        if (PillarController.trace) {
            System.out.printf("FALL y %.2f vy %.2f pit %.0f water%d %s%n", player.getY(), player.getDeltaMovement().y, player.getXRot(), Inventory.isHotbarSlot(water) ? 1 : 0, click ? "C" : "");
        }
        return pathPosition;
    }

    /**
     * One chord. Off, this is clear-all then the seven keys the fall used to set.
     * On, a rejected claim writes nothing, so a preempting owner keeps its keys.
     *
     * @return false only when the flag is on and this controller did not get the chord
     */
    private boolean press(Baritone baritone, boolean forward, boolean back, boolean left, boolean right,
                          boolean sneak, boolean jump, boolean clickRight) {
        if (!Baritone.settings().inputArbitration.value) {
            var in = baritone.getInputOverrideHandler();
            in.clearAllKeys();
            in.setInputForceState(Input.MOVE_FORWARD, forward);
            in.setInputForceState(Input.MOVE_BACK, back);
            in.setInputForceState(Input.MOVE_LEFT, left);
            in.setInputForceState(Input.MOVE_RIGHT, right);
            in.setInputForceState(Input.SNEAK, sneak);
            in.setInputForceState(Input.JUMP, jump);
            in.setInputForceState(Input.CLICK_RIGHT, clickRight);
            return true;
        }
        InputArbiter arb = baritone.getInputArbiter();
        if (lease == null || heldEpoch != lease.epoch() || !holdsChord(lease)) {
            lease = arb.claim(FallInput.OWNER, FallInput.PRIORITY, false, FallInput.LEASE);
            heldEpoch = lease.epoch();
        }
        if (!holdsChord(lease)) {
            return false;
        }
        Map<Input, Boolean> frame = FallInput.submit(arb, lease,
                FallInput.chord(forward, back, left, right, sneak, jump, clickRight));
        if (frame == null) {
            return false;
        }
        writeApplied(baritone, frame);
        wrote = true;
        return true;
    }

    private static boolean holdsChord(InputArbiter.Lease lease) {
        return lease.holds(InputArbiter.Resource.MOVE)
                && lease.holds(InputArbiter.Resource.JUMP)
                && lease.holds(InputArbiter.Resource.SNEAK)
                && lease.holds(InputArbiter.Resource.CLICK);
    }

    /**
     * Ostinato: release the fall lease when the executor that owns this controller is cancelled or
     * discarded. Without it the lease outlives the executor in the per-Baritone arbiter.
     * No-op when no lease is held, which is always the case with the flag off.
     */
    public void release(Baritone baritone) {
        if (lease != null) {
            ticks = 0;
            endLease(baritone, false);
        }
    }

    /** Release a lease taken while the flag was on. A tick that never wrote leaves the keyboard alone. */
    private void endLease(Baritone baritone, boolean failed) {
        Map<Input, Boolean> frame = FallInput.end(baritone.getInputArbiter(), lease, failed);
        lease = null;
        heldEpoch = 0;
        if (wrote && frame != null) {
            writeApplied(baritone, frame);
        }
        wrote = false;
    }

    private static void writeApplied(Baritone baritone, Map<Input, Boolean> frame) {
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        for (Map.Entry<Input, Boolean> entry : frame.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                in.setInputForceState(entry.getKey(), true);
            }
        }
    }
}
