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
import baritone.utils.BoatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Slow kinematic, boat falls: a drop too high for a water bucket (or with no bucket) is survived by putting a boat down
 * where the player stands, boarding it and driving off the edge; the boat soaks up the landing. The camera is turned
 * with the humanized mouse. While this runs it holds {@link MovementFall#boatRide} so BoatProcess does not climb out.
 */
public final class BoatFallController {

    private final IPlayerContext ctx;
    private int lastPos = -1, boatTicks, cooldown;
    /** Ticks driven, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;

    public BoatFallController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** @return the path position to continue from if the controller drove this tick, or -1 to let the next driver run */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
            MovementFall.boatRide = true;
            MovementFall.boatRideTick = ctx.player().tickCount;
        }
        return r;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || player.isInLava() || player.isFallFlying() || player.getAbilities().flying
                || pathPosition >= path.movements().size() || !(path.movements().get(pathPosition) instanceof MovementFall fall)) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        BetterBlockPos src = fall.getSrc(), dest = fall.getDest();
        int drop = src.y - dest.y;
        CalculationContext cc = new CalculationContext(baritone);
        boolean destWater = MovementHelper.isWater(ctx.world().getBlockState(dest));
        boolean bucket = drop > cc.maxFallHeightNoWater && !destWater;
        if (drop < 2 || !bucket || cc.hasWaterBucket && drop <= cc.maxFallHeightBucket + 1) {
            return -1; // survivable on foot or with a bucket, which is the fall controller's
        }
        if (pathPosition != lastPos) {
            lastPos = pathPosition;
            boatTicks = 0;
        }
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        var look = baritone.getLookBehavior();
        look.human();

        Entity v = player.getVehicle();
        if (v instanceof AbstractBoat) {
            if (!BoatUtil.isDriver(player) || BoatUtil.mobAboard(player)) {
                in.setInputForceState(Input.SNEAK, true); // someone else steers: get out, do not ride their boat off the cliff
                cooldown = 80;
                return -1;
            }
            BoatUtil.restore(ctx);
            if (v.onGround() && Math.abs(v.getY() - dest.getY()) < 0.7) {
                return pathPosition + 1;
            }
            if (!v.onGround()) {
                return pathPosition; // falling; nothing to steer
            }
            double tx = dest.getX() + 0.5 - v.getX(), tz = dest.getZ() + 0.5 - v.getZ();
            float want = (float) (Mth.atan2(tz, tx) * 180 / Math.PI) - 90;
            float diff = Mth.wrapDegrees(want - v.getYRot());
            in.setInputForceState(Input.MOVE_RIGHT, diff > 4);
            in.setInputForceState(Input.MOVE_LEFT, diff < -4);
            in.setInputForceState(Input.MOVE_FORWARD, Math.abs(diff) < 50);
            if (boatTicks++ > 200) {
                cooldown = 80;
                Baritone.settings().movementFault.value.accept("M01", "boat fall stuck riding at the edge, handing back to Baritone");
                return -1;
            }
            look.updateTarget(new Rotation(want, 10), true);
            return pathPosition;
        }
        if (player.isPassenger()) {
            return -1;
        }
        BetterBlockPos feet = ctx.playerFeet();
        int nx = feet.x - src.x, ny = feet.y - src.y, nz = feet.z - src.z;
        int near = nx * nx + ny * ny + nz * nz;
        if (near > 2 && player.onGround() && boatTicks < 60 && feet.y >= src.y) {
            boatTicks++; // handed the movement a step off: walk back onto src first
            look.updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(src), ctx.playerRotations()), true);
            in.setInputForceState(Input.MOVE_FORWARD, true);
            return pathPosition;
        }
        if (near > 2 || boatTicks++ > 160) {
            cooldown = 80;
            Baritone.settings().movementFault.value.accept("M01", "boat fall could not place or mount a boat, handing back to Baritone");
            return -1;
        }
        Entity boat = null;
        for (Entity e : ctx.entities()) {
            if (BoatUtil.free(e) && player.distanceTo(e) < 3 && (boat == null || player.distanceTo(e) < player.distanceTo(boat))) {
                boat = e;
            }
        }
        if (boat != null) {
            look.updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(boat.getX(), boat.getY() + 0.3, boat.getZ()), ctx.playerRotations()), true);
            if (boatTicks % 4 == 3) {
                Minecraft.getInstance().gameMode.interact(player, boat, new net.minecraft.world.phys.EntityHitResult(boat), InteractionHand.MAIN_HAND);
            }
            return pathPosition;
        }
        int slot = BoatUtil.hotbarBoat(ctx);
        if (slot < 0) {
            return -1; // no boat: nothing keeps this alive
        }
        player.getInventory().setSelectedSlot(slot);
        if (feet.equals(src) && MovementHelper.canWalkThrough(ctx, src.above(2)) && MovementHelper.canWalkThrough(ctx, src.above(3))) {
            look.updateTarget(new Rotation(player.getYRot(), 90), true);
            if (player.onGround()) {
                in.setInputForceState(Input.JUMP, true);
            } else if (player.getY() - src.getY() > 0.7 && player.getXRot() > 80) {
                Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            return pathPosition;
        }
        double dx = Math.signum(dest.getX() - src.getX()), dz = Math.signum(dest.getZ() - src.getZ());
        Vec3 aim = new Vec3(src.getX() + 0.5 + dx * 0.35, src.getY(), src.getZ() + 0.5 + dz * 0.35);
        look.updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations()), true);
        double back = (src.getX() + 0.5 - player.getX()) * dx + (src.getZ() + 0.5 - player.getZ()) * dz;
        if (back < 0.8) {
            in.setInputForceState(Input.MOVE_BACK, true);
        } else if (boatTicks % 5 == 4) {
            Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        return pathPosition;
    }
}
