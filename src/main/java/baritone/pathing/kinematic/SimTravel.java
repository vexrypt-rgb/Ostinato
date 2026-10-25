package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementChainJump;
import baritone.pathing.movement.movements.MovementClimbJump;
import baritone.pathing.movement.movements.MovementJump;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementTas;
import baritone.utils.BlockStateInterface;

/**
 * Ostinato: replays a {@link SimProbe} plan on a jump the other layers declined. One search per spot, then the
 * cache. A miss returns -1 so Baritone's movement backup still runs that tick.
 */
public final class SimTravel {

    private final IPlayerContext ctx;
    private final ClientWorld world;
    private final SimProbe.Cache cache = new SimProbe.Cache();
    private boolean[] plan;
    private int step;
    private int cooldown;
    private float yaw;
    /** Where the last pressed tick said the player would be. A drift throws the plan away. */
    private double predX, predY, predZ;
    private boolean havePred;

    public SimTravel(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
    }

    /** @return the path position to continue from, or -1 when this layer does not take the tick */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        if (!Baritone.settings().simTravel.value || ctx.player().isInWater() || ctx.player().isInLava()
                || ctx.player().onClimbable() || ctx.player().isFallFlying() || ctx.player().isPassenger()) {
            return drop();
        }
        if (pathPosition < 0 || pathPosition >= path.movements().size()) {
            return drop();
        }
        Movement m = (Movement) path.movements().get(pathPosition);
        if (!jumpLike(m)) {
            return drop();
        }
        if (cooldown > 0 && plan == null) {
            cooldown--;
            return -1;
        }
        world.reset();
        PlayerSim body = new PlayerSim(world);
        ClientWorld.readPlayer(ctx, body);
        if (needsBlocks(m)) {
            return drop();
        }
        BetterBlockPos dest = m.getDest();
        if (plan != null && step < plan.length) {
            if (drifted(body)) {
                plan = null;
                cooldown = 10;
                return -1;
            }
            return press(baritone, path, pathPosition, body);
        }
        yaw = SimProbe.yawToward(body.x, body.z, dest.x, dest.z);
        SimProbe.Result result = cache.solve(world, body, yaw, dest.x, dest.y, dest.z);
        if (!result.found()) {
            cooldown = 40;
            plan = null;
            return -1;
        }
        if (result.ticks() == 0) {
            return pathPosition;
        }
        plan = result.jumps;
        step = 0;
        return press(baritone, path, pathPosition, body);
    }

    private int press(Baritone baritone, IPath path, int pathPosition, PlayerSim body) {
        boolean jump = plan[step++];
        PlayerSim predicted = new PlayerSim(world).copyFrom(body);
        predicted.tick(yaw, true, true, jump);
        predX = predicted.x;
        predY = predicted.y;
        predZ = predicted.z;
        havePred = true;
        ctx.player().setYRot(yaw);
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, jump);
        if (step >= plan.length) {
            plan = null;
            havePred = false;
        }
        BetterBlockPos feet = ctx.playerFeet();
        int end = Math.min(path.movements().size() - 1, pathPosition + 4);
        for (int i = end; i > pathPosition; i--) {
            if (path.positions().get(i).equals(feet)) {
                return i;
            }
        }
        return pathPosition;
    }

    private boolean drifted(PlayerSim body) {
        if (!havePred || step <= 1) {
            return false;
        }
        return Math.abs(body.x - predX) + Math.abs(body.y - predY) + Math.abs(body.z - predZ) > 0.45;
    }

    private boolean needsBlocks(Movement m) {
        if (m.toBreakCached != null && m.toPlaceCached != null) {
            return !m.toBreakCached.isEmpty() || !m.toPlaceCached.isEmpty();
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        return !m.toBreak(bsi).isEmpty() || !m.toPlace(bsi).isEmpty();
    }

    private int drop() {
        plan = null;
        step = 0;
        havePred = false;
        return -1;
    }

    static boolean jumpLike(Movement m) {
        return m instanceof MovementParkour || m instanceof MovementJump || m instanceof MovementChainJump
                || m instanceof MovementClimbJump || m instanceof MovementAscend || m instanceof MovementTas;
    }

    /** Test hook: how many uncached searches this driver has run. */
    public int searches() {
        return cache.searches;
    }
}
