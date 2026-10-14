package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementDownward;
import baritone.pathing.movement.movements.MovementPillar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Slow kinematic, ladders and vines: climbs a run of {@link MovementPillar} moves that sit on climbable blocks with
 * the jump key (the climb speed is the vanilla cap, 0.2 blocks per tick), and descends a run of
 * {@link MovementDownward} moves onto climbable blocks by letting go, which slides at the vanilla 0.15 blocks per
 * tick. The camera looks up or down the climb with the humanized mouse, and a short press of W keeps the player
 * centred in the column so it never slides off.
 */
public final class LadderController {

    private static final int MAX_MOVES = 24;
    private static final double CENTRE = 0.18;

    private final IPlayerContext ctx;
    private double lastX, lastY, lastZ;
    private int stuckTicks, cooldown;
    /** Ticks driven, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;

    public LadderController(IPlayerContext ctx) {
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

    private boolean climbable(BetterBlockPos p) {
        Block b = ctx.world().getBlockState(p).getBlock();
        return MovementHelper.isClimbable(b);
    }

    private boolean upRun(IMovement mv) {
        return mv instanceof MovementPillar && climbable(mv.getSrc());
    }

    private boolean downRun(IMovement mv) {
        return mv instanceof MovementDownward && climbable(mv.getDest());
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || player.isInWater() || player.isInLava() || player.isFallFlying()
                || player.isPassenger() || player.getAbilities().flying) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        List<IMovement> moves = path.movements();
        if (pathPosition >= moves.size()) {
            return -1;
        }
        boolean up = upRun(moves.get(pathPosition));
        boolean down = !up && downRun(moves.get(pathPosition));
        if (!up && !down) {
            return -1;
        }
        // advance past moves whose destination the player already stands in
        int pos = pathPosition;
        for (int i = Math.min(moves.size() - 1, pathPosition + MAX_MOVES); i >= pathPosition; i--) {
            if (ctx.playerFeet().equals(moves.get(i).getDest()) && (up ? upRun(moves.get(i)) : downRun(moves.get(i)))) {
                pos = i + 1;
                break;
            }
        }
        if (pos >= moves.size() || !(up ? upRun(moves.get(pos)) : downRun(moves.get(pos)))) {
            return pos > pathPosition ? pos : -1;
        }
        IMovement cur = moves.get(pos);

        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double moved = (px - lastX) * (px - lastX) + (py - lastY) * (py - lastY) + (pz - lastZ) * (pz - lastZ);
        lastX = px;
        lastY = py;
        lastZ = pz;
        stuckTicks = moved < 0.0004 ? stuckTicks + 1 : 0;
        if (stuckTicks > 40) {
            stuckTicks = 0;
            cooldown = 60;
            Baritone.settings().movementFault.value.accept("M01", "ladder stuck at " + ctx.playerFeet() + ", handing back to Baritone");
            return -1;
        }

        double cx = cur.getDest().x + 0.5, cz = cur.getDest().z + 0.5;
        double off = Math.hypot(cx - px, cz - pz);
        double yaw = Math.toDegrees(Math.atan2(-(cx - px), cz - pz));
        boolean centre = off > CENTRE;
        // up: look up the climb. down: look down it. Either way the camera is a mouse move, never a snap.
        float pitch = up ? -40f : 40f;
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(new Rotation((float) (centre ? yaw : player.getYRot()), pitch), true);
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        in.setInputForceState(Input.MOVE_FORWARD, centre);
        in.setInputForceState(Input.JUMP, up);
        return pos;
    }
}
