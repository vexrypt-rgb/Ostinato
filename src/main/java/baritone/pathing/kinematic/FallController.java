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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

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

    public FallController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** @return the path position to continue from if the controller drove this tick, or -1 to let the next driver run */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        } else {
            ticks = 0;
        }
        return r;
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
            var in = baritone.getInputOverrideHandler();
            inv.setSelectedSlot(empty);
            baritone.getLookBehavior().human();
            baritone.getLookBehavior().updateTarget(new Rotation(player.getYRot(), 90f), true);
            in.clearAllKeys();
            in.setInputForceState(Input.CLICK_RIGHT, player.getXRot() > 80 && ticks % 3 == 0);
            return pathPosition;
        }
        if (!(path.movements().get(pathPosition) instanceof MovementFall fall)) {
            return -1;
        }
        BetterBlockPos src = fall.getSrc(), dest = fall.getDest();
        int drop = src.y - dest.y;
        // a path left over from before a goal change is not the player's fall: it has to be on this one's way down
        if (Math.hypot(player.getX() - (src.x + 0.5), player.getZ() - (src.z + 0.5)) > 4
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
        var in = baritone.getInputOverrideHandler();
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
                in.clearAllKeys();
                in.setInputForceState(Input.CLICK_RIGHT, player.getXRot() > 80 && ticks % 3 == 0);
                return pathPosition;
            }
            if (feet.equals(dest)) {
                in.clearAllKeys();
                return pathPosition + 1;
            }
        }

        boolean air = !player.onGround();
        double fx = player.getX() + player.getDeltaMovement().x, fz = player.getZ() + player.getDeltaMovement().z;
        boolean off = Math.abs(fx - centre.x) > 0.1 || Math.abs(fz - centre.z) > 0.1;
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), centre, ctx.playerRotations());
        // the camera goes down as soon as the feet leave the ground (the mouse takes a few ticks), and the water goes onto
        // whatever block the crosshair is on once it is within reach: at 3 or more blocks a tick the window is a single tick,
        // so the click is not held up waiting for the landing column in particular
        boolean place = bucket && air && Inventory.isHotbarSlot(water);
        float pitch = place ? 90f : 0f;
        if (place) {
            inv.setSelectedSlot(water);
        }
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(new Rotation(toDest.getYaw(), pitch), true);
        in.clearAllKeys();
        in.setInputForceState(Input.MOVE_FORWARD, off);
        // hold sneak while dropping fast so the air steering does not overshoot the column
        in.setInputForceState(Input.SNEAK, off && air && Math.abs(player.getDeltaMovement().y) > 0.4);
        boolean click = false;
        if (place && player.getXRot() > 80 && ctx.objectMouseOver() instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && hit.getDirection() == net.minecraft.core.Direction.UP
                && ctx.playerHead().distanceTo(hit.getLocation()) < ctx.playerController().getBlockReachDistance()) {
            click = true;
        }
        in.setInputForceState(Input.CLICK_RIGHT, click);
        if (click) {
            placedWater = true;
        }
        if (PillarController.trace) {
            System.out.printf("FALL y %.2f vy %.2f pit %.0f water%d %s%n", player.getY(), player.getDeltaMovement().y, player.getXRot(), Inventory.isHotbarSlot(water) ? 1 : 0, click ? "C" : "");
        }
        return pathPosition;
    }
}
