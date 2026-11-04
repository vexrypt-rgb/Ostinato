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
import baritone.pathing.physics.PhysicsTravel;
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
    /** The client tick the last plan step was pressed on, and the landing the plan was solved for. */
    private int pressedAt;
    private BetterBlockPos planDest;
    /**
     * Ostinato Presto v4.1, read by the live harness's trace only: the last solve (from where, at what yaw, toward
     * which landing, which plan, whether the cache answered) and the step pressed. Primitives, set in place.
     */
    public double traceFromX = Double.NaN, traceFromZ = Double.NaN;
    public float traceYaw = Float.NaN;
    public int traceDestX, traceDestZ, tracePlanLen = -1, traceStep = -1;
    public long tracePlanBits;
    public boolean traceHit;

    public SimTravel(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
    }

    /** Whether the last tick pressed keys (a plan step): they stay held until someone clears them. */
    private boolean pressed;

    /** @return the path position to continue from, or -1 when this layer does not take the tick */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        boolean held = pressed;
        pressed = false;
        int r = tick0(baritone, path, pathPosition);
        if (releases(held, pressed)) {
            // Ostinato Presto v4: stepping aside, let go of the plan's forward, sprint and jump rather than leave them
            // held for whoever takes the tick next. A landed plan advances the path but PathExecutor returns for
            // this tick, so that successful handover must release the old keys too.
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        return r;
    }

    /**
     * Ostinato Presto v4.1: whether this tick must let go of the keys: the last tick pressed a plan step and this one
     * did not, whatever it returned (declined, dropped, or a landed plan's successful advance).
     */
    static boolean releases(boolean heldBefore, boolean pressedNow) {
        return heldBefore && !pressedNow;
    }

    private int tick0(Baritone baritone, IPath path, int pathPosition) {
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
        if (onLanding(body, dest)) {
            // the jump is done: advance the path as press does once the feet are on its node, or leave what is left
            // (a walk) to the movement. presto-v4-fix4: dropping with the feet already on dest handed the tick to a
            // Jump that had never seen its own take-off; it walked back to its run-up and into the gap (0 of 16 laps)
            return landed(path, pathPosition);
        }
        int now = ctx.player().tickCount;
        if (plan != null && stale(pressedAt, now, planDest, dest)) {
            // Ostinato Presto v4.1: another driver had the ticks in between, or this is a different jump. The plan's
            // yaw and jump timing were for where the player stood then; solve again from here.
            plan = null;
            havePred = false;
        }
        if (plan != null && step < plan.length) {
            if (drifted(body)) {
                plan = null;
                cooldown = 10;
                return -1;
            }
            return press(baritone, path, pathPosition, body);
        }
        yaw = SimProbe.yawToward(body.x, body.z, dest.x, dest.z);
        if (body.onGround && PhysicsTravel.offPlan(baritone.getLookBehavior().getAimProcessor().peekRotation(new Rotation(yaw, 0)).getYaw(), yaw)) {
            // Ostinato Presto v4.1: the move this tick goes the way the look has turned to (32° a tick), not the plan's.
            // presto-v4.1 final1 laps 21, 22: after a jolt Baritone's Jump had turned back west toward its run-up; this
            // driver then pressed W and sprint east at once and the walk swung through south (north), off the one-wide
            // walkway at x 33. Turn in place first, as PhysicsTravel does, and solve once facing the plan's way.
            ctx.player().setYRot(yaw);
            baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
            baritone.getInputOverrideHandler().clearAllKeys();
            plan = null;
            havePred = false;
            return pathPosition;
        }
        int searched = cache.searches;
        SimProbe.Result result = cache.solve(world, body, yaw, dest.x, dest.y, dest.z);
        traceFromX = body.x;
        traceFromZ = body.z;
        traceYaw = yaw;
        traceDestX = dest.x;
        traceDestZ = dest.z;
        traceHit = cache.searches == searched;
        tracePlanLen = result.jumps == null ? -1 : result.jumps.length;
        tracePlanBits = 0;
        for (int i = 0; i < tracePlanLen && i < 64; i++) {
            if (result.jumps[i]) tracePlanBits |= 1L << i;
        }
        if (!result.found()) {
            cooldown = 40;
            plan = null;
            return -1;
        }
        if (result.ticks() == 0) {
            // Ostinato Presto v4: already on the landing. Claiming the tick here (as this did) kept the last plan's
            // forward and sprint held with nothing steering: on the landing by the box but with the feet a cell to the
            // side, the path never advanced and the sprint carried on across a one-wide walkway and off its far side
            // (presto-v4-fix2 lap 0, Jump 40>44). Advance if the feet are on the node, else let the movement finish it.
            return landed(path, pathPosition);
        }
        plan = result.jumps;
        step = 0;
        planDest = dest;
        return press(baritone, path, pathPosition, body);
    }

    private int press(Baritone baritone, IPath path, int pathPosition, PlayerSim body) {
        pressed = true;
        pressedAt = ctx.player().tickCount;
        traceStep = step;
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
        int ahead = ahead(path.positions(), pathPosition, ctx.playerFeet(), body.onGround);
        return ahead > 0 ? ahead : pathPosition;
    }

    /** The furthest of the next four path nodes the feet stand in, or -1. */
    static int ahead(java.util.List<BetterBlockPos> positions, int pathPosition, BetterBlockPos feet, boolean grounded) {
        // A path has one more position than movements. Include its final destination only after touchdown:
        // completing the path in mid-air would release the steering before the jump has landed.
        int end = Math.min(positions.size() - (grounded ? 1 : 2), pathPosition + 4);
        for (int i = end; i > pathPosition; i--) {
            if (positions.get(i).equals(feet)) {
                return i;
            }
        }
        return -1;
    }

    /** On the landing: the path moves on if the feet reached a later node; otherwise the movement takes the rest. */
    private int landed(IPath path, int pathPosition) {
        int ahead = ahead(path.positions(), pathPosition, ctx.playerFeet(), true);
        if (ahead < 0) {
            return drop();
        }
        plan = null;
        step = 0;
        havePred = false;
        pressed = false; // tick() releases the last plan's keys during this handover
        return ahead;
    }

    /**
     * Ostinato Presto v4: on the ground at the landing's height with the box over the landing block. A jump's plan
     * aims at the landing's centre; replayed or searched again from here (the box can also touch the next block, so
     * the probe does not count it landed) it ran back toward a centre the player had already passed, snapping the look
     * round, and the reversal went off the walkway's side (presto-v4-fix3 lap 3: landed Jump 40>44 at x 44.7, turned
     * west at x 45.3, fell at z -0.41).
     */
    static boolean onLanding(PlayerSim s, BetterBlockPos dest) {
        double h = PlayerSim.HALF_WIDTH;
        return s.onGround && PlayerSim.nodeHeight(s.y, dest.y)
                && s.x + h > dest.x && s.x - h < dest.x + 1 && s.z + h > dest.z && s.z - h < dest.z + 1;
    }

    /**
     * Ostinato Presto v4.1: an open-loop plan is only good on the tick after its last step, for the jump it was solved
     * for. presto-v4.1 run 2 laps 1, 13, 18 (and run 1 lap 13): stalled against the step up at x 20.7, this driver
     * solved "J........" for Ascend 20>21 at yaw -104 and pressed one step before the kinematic controller took the
     * ticks back. The rest stayed stored; twenty blocks on, at Jump 40>44 (which the controller declines), it replayed
     * that plan's yaw with no jump, and the player walked off at x 41.4. Within one step drift() can't catch it.
     */
    static boolean stale(int pressedAt, int now, BetterBlockPos solvedFor, BetterBlockPos dest) {
        return now != pressedAt + 1 || solvedFor == null || !solvedFor.equals(dest);
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
