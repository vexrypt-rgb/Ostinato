package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementPillar;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Slow kinematic, pillaring: the speedrunner's tower. The camera looks straight down the column with the humanized
 * mouse, the player is centred before the first jump, then jumps and drops a block into the cell it just left at the
 * top of the jump. Baritone's pillar crouches to stay on the block; here nothing moves the player sideways in the air.
 * Ladders and water columns stay with the other drivers, and a ceiling in the way is the mining controller's job.
 */
public final class PillarController {

    private static final double CENTRE = 0.14;
    private static final double KEY = 0.012; // desired speed change (blocks/tick) before a key is pressed

    private final IPlayerContext ctx;
    private double lastY;
    private int stuckTicks, cooldown;
    /** Ticks driven, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;
    /** One compact line per driven tick on stdout: dy vy off pitch ground keys place solid(src) mouse-over. */
    public static volatile boolean trace;

    public PillarController(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** @return the path position to continue from if the controller drove this tick, or -1 to let the next driver run */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        }
        return r;
    }

    private boolean solid(BlockPos p) {
        return !ctx.world().getBlockState(p).getCollisionShape(ctx.world(), p).isEmpty();
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || !Baritone.settings().allowPlace.value
                || player.isInWater() || player.isInLava() || player.isFallFlying() || player.isPassenger()
                || player.getAbilities().flying || pathPosition >= path.movements().size()) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        IMovement mv = path.movements().get(pathPosition);
        if (!(mv instanceof MovementPillar)) {
            return -1;
        }
        BetterBlockPos src = mv.getSrc(), dest = mv.getDest();
        if (MovementHelper.isClimbable(ctx.world().getBlockState(src).getBlock())
                || MovementHelper.isWater(ctx.world().getBlockState(src)) || MovementHelper.isWater(ctx.world().getBlockState(dest))) {
            return -1;
        }
        BetterBlockPos feet = ctx.playerFeet();
        if (feet.equals(dest) && player.onGround() && solid(dest.below())) {
            return pathPosition + 1;
        }
        if (player.getY() < src.y - 0.01 || !baritone.getInventoryBehavior().selectThrowawayForLocation(true, src.x, src.y, src.z)) {
            if (trace) {
                System.out.printf("PIL skip @%s y %.2f%n", src, player.getY());
            }
            return -1;
        }
        Movement m = (Movement) mv;
        m.resetBlockCache();
        for (BlockPos p : m.toBreak(new BlockStateInterface(ctx))) {
            if (!MovementHelper.canWalkThrough(ctx, new BetterBlockPos(p))) {
                return -1;
            }
        }
        stuckTicks = Math.abs(player.getY() - lastY) < 0.001 ? stuckTicks + 1 : 0;
        lastY = player.getY();
        if (stuckTicks > 60) {
            stuckTicks = 0;
            cooldown = 80;
            Baritone.settings().movementFault.value.accept("M01", "pillar stuck at " + feet + ", handing back to Baritone");
            return -1;
        }

        Vec3 c = VecUtils.getBlockPosCenter(dest);
        double off = Math.hypot(c.x - player.getX(), c.z - player.getZ());
        boolean grounded = player.onGround();
        Vec3 vel = player.getDeltaMovement();
        double hspeed = Math.hypot(vel.x, vel.z);
        boolean fix = grounded && (off > CENTRE || hspeed > 0.05);
        // the camera only looks down; the column is held with W/A/S/D relative to wherever it already points, so
        // nothing waits for a yaw turn and no momentum is left over for the jump to carry off the column
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(new Rotation(player.getYRot(), 90f), true);
        boolean down = player.getXRot() > 80;
        boolean place = !solid(src) && player.getY() >= src.y + 1.0 && down;
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        double wantX = (c.x - player.getX()) * 0.5 - vel.x * 3, wantZ = (c.z - player.getZ()) * 0.5 - vel.z * 3;
        double yawRad = Math.toRadians(player.getYRot());
        double fwd = -Math.sin(yawRad) * wantX + Math.cos(yawRad) * wantZ, left = Math.cos(yawRad) * wantX + Math.sin(yawRad) * wantZ;
        boolean jump = grounded && !fix && down && player.getY() < dest.y;
        boolean steer = !jump && (off > 0.05 || hspeed > 0.03); // a jump tick carries no sideways push: sprint momentum would take it off the column
        if (player.isSprinting()) {
            player.setSprinting(false); // left over from the walking move before: a sprint jump throws the player off the column
        }
        in.setInputForceState(Input.MOVE_FORWARD, steer && fwd > KEY);
        in.setInputForceState(Input.MOVE_BACK, steer && fwd < -KEY);
        in.setInputForceState(Input.MOVE_LEFT, steer && left > KEY);
        in.setInputForceState(Input.MOVE_RIGHT, steer && left < -KEY);
        in.setInputForceState(Input.JUMP, jump);
        boolean click = place && ctx.isLookingAt(src.below());
        in.setInputForceState(Input.CLICK_RIGHT, click);
        if (trace) {
            var over = ctx.objectMouseOver();
            System.out.printf("PIL @%s dy %.2f vy %.2f off %.2f pit %.0f g%d %s%s%s%s src%d over %s%n", src, player.getY() - src.y,
                    player.getDeltaMovement().y, off, player.getXRot(), grounded ? 1 : 0, fix ? "F" : "", in.isInputForcedDown(Input.JUMP) ? "J" : "",
                    place ? "P" : "", click ? "C" : "", solid(src) ? 1 : 0,
                    over instanceof net.minecraft.world.phys.BlockHitResult h && h.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                            ? h.getBlockPos().toShortString() + h.getDirection().name().charAt(0) : "-");
        }
        return pathPosition;
    }
}
