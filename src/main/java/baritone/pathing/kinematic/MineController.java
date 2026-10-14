package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementDownward;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * Slow kinematic, breaking: when the next move has blocks in the way, stands, turns the camera onto the nearest one
 * with the humanized mouse, swaps to the best tool and mines it, then lets the walking drivers take the move once the
 * way is clear. Replaces the break step of Baritone's walking moves; it waits for falling blocks the same way.
 */
public final class MineController {

    private final IPlayerContext ctx;
    private int stuckTicks, cooldown;
    private BlockPos lastTarget;
    /** Ticks driven, so callers can verify the mode is in use. */
    public static volatile long drivenTicks;

    public MineController(IPlayerContext ctx) {
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

    private static boolean mines(IMovement mv) {
        return mv instanceof MovementTraverse || mv instanceof MovementAscend || mv instanceof MovementDescend
                || mv instanceof MovementDiagonal || mv instanceof MovementDownward || mv instanceof MovementFall
                || mv instanceof baritone.pathing.movement.movements.MovementPillar;
    }

    /** The dig-down: the block underfoot is gone, so centre over the hole with the camera down the shaft and drop. */
    private int drop(Baritone baritone, Movement m, int pathPosition) {
        var in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        if (ctx.playerFeet().equals(m.getDest()) && ctx.player().onGround()) {
            return pathPosition + 1;
        }
        double cx = m.getDest().x + 0.5, cz = m.getDest().z + 0.5;
        double off = Math.hypot(cx - ctx.player().getX(), cz - ctx.player().getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-(cx - ctx.player().getX()), cz - ctx.player().getZ()));
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(new Rotation(off > 0.1 ? yaw : ctx.player().getYRot(), 60f), true);
        in.setInputForceState(Input.MOVE_FORWARD, off > 0.12);
        return pathPosition;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        Player player = ctx.player();
        if (!Baritone.settings().slowKinematic.value || !Baritone.settings().allowBreak.value
                || player.isInWater() || player.isInLava() || player.isFallFlying() || player.isPassenger()
                || player.getAbilities().flying) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        if (pathPosition >= path.movements().size() || !mines(path.movements().get(pathPosition))) {
            return -1;
        }
        if (!player.onGround() && !(path.movements().get(pathPosition) instanceof MovementDownward)) {
            return -1;
        }
        Movement m = (Movement) path.movements().get(pathPosition);
        if (!m.getValidPositions().contains(ctx.playerFeet())) {
            return -1;
        }
        m.resetBlockCache();
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        BlockPos target = null;
        for (BlockPos p : m.toBreak(bsi)) {
            if (!MovementHelper.canWalkThrough(ctx, new baritone.api.utils.BetterBlockPos(p))) {
                target = p;
                break;
            }
        }
        var in = baritone.getInputOverrideHandler();
        if (target == null) {
            if (m instanceof MovementDownward && !MovementHelper.isClimbable(ctx.world().getBlockState(m.getDest()).getBlock())
                    && MovementHelper.canWalkThrough(ctx, m.getDest())) {
                return drop(baritone, m, pathPosition);
            }
            return -1;
        }
        if (!ctx.player().onGround()) {
            return -1; // mining happens standing; only the drop itself is driven in the air
        }
        in.clearAllKeys();
        if (Baritone.settings().pauseMiningForFallingBlocks.value
                && !ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(target)).isEmpty()) {
            return pathPosition; // wait for it to land
        }
        stuckTicks = target.equals(lastTarget) ? stuckTicks + 1 : 0;
        lastTarget = target;
        if (stuckTicks > 400) { // a tool-less stone is slow, but not this slow
            stuckTicks = 0;
            cooldown = 80;
            Baritone.settings().movementFault.value.accept("M01", "mining stuck at " + target + ", handing back to Baritone");
            return -1;
        }
        MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, target));
        Optional<Rotation> reach = RotationUtils.reachable(ctx, target, ctx.playerController().getBlockReachDistance());
        final BlockPos tgt = target;
        Rotation rot = reach.orElseGet(() -> RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(tgt), ctx.playerRotations()));
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(rot, true);
        boolean aimed = ctx.isLookingAt(target) || ctx.playerRotations().isReallyCloseTo(rot);
        in.setInputForceState(Input.CLICK_LEFT, aimed);
        return pathPosition;
    }
}
