/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package baritone.pathing.physics;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.contract.PredictionTolerance;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.unified.UnifiedExecutor;
import baritone.pathing.viz.PathViz;
import baritone.utils.BlockStateInterface;

import java.util.List;

/**
 * Drives land stretches of Baritone's path with keys planned by {@link PhysicsPathfinder}.
 * Plans to a waypoint a few movements ahead, replays the plan while the real player tracks the
 * simulated one, and re-plans on drift. Baritone keeps block-level control everywhere else.
 */
public final class PhysicsTravel {

    private static final int LOOKAHEAD = 6;
    private static final double DRIFT = 0.1;
    private static final int NODE_BUDGET = 1500;
    /** The deepest drop taken here: the furthest a player falls unhurt. */
    private static final int MAX_DROP = 3;

    private final IPlayerContext ctx;
    private final ClientWorld world;
    private final PlayerSim real, predicted;
    private final PhysicsPathfinder finder;
    private List<PhysicsPathfinder.Action> plan;
    private int step, target = -1, cooldown;
    /** Jump cooldown left by our own presses; the client keeps it private. */
    private int jumpTicks;
    /** Ostinato 2.0 Phase 04: the mode of the tick {@link #predicted} ran; null while predictionTolerance is off. */
    private PredictionTolerance.Mode predictedMode;
    /** Ostinato 2.0 Phase 06: corrections spent on the current plan, and the sim the correction replays in (made on first use). */
    private int corrections;
    private PlayerSim scratch;
    /** Ostinato 2.0 Phase 06A: the deeper planner of the one retry after a correction-triggered replan found no path (made on first use). */
    private PhysicsPathfinder retryFinder;
    /** Ostinato 2.0 Phase 04 validation: searches started, and how many of them the drift check started. Counters only. */
    public static final java.util.concurrent.atomic.AtomicLong plans = new java.util.concurrent.atomic.AtomicLong(),
            driftReplans = new java.util.concurrent.atomic.AtomicLong();

    public PhysicsTravel(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
        this.real = new PlayerSim(world);
        this.predicted = new PlayerSim(world);
        this.finder = new PhysicsPathfinder(world, NODE_BUDGET).viz(PathViz.get());
    }

    /**
     * Ostinato 2.0 Phase 04: whether the real player left the predicted tick. A null tolerance is the old check, the
     * summed axis error over {@link #DRIFT}, which a null mode also falls back to; otherwise the Euclidean error over the bound for {@code mode}.
     */
    static boolean drifted(PlayerSim predicted, PlayerSim real, PredictionTolerance tolerance, PredictionTolerance.Mode mode) {
        if (tolerance == null || mode == null) {
            return Math.abs(predicted.x - real.x) + Math.abs(predicted.y - real.y) + Math.abs(predicted.z - real.z) > DRIFT;
        }
        return tolerance.exceeds(mode, real.x - predicted.x, real.y - predicted.y, real.z - predicted.z);
    }

    /** @return the path position to continue from if this drove the tick, or -1 to let the next layer run it */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        return tick(baritone, path, pathPosition, false);
    }

    /**
     * Ostinato: {@code stackAsked} means the travel stack already chose physics, so {@code physicsTravel} is not
     * required. The stack tries this only after the kinematic family declines the spot.
     */
    public int tick(Baritone baritone, IPath path, int pathPosition, boolean stackAsked) {
        // Ostinato 2.0 Phase 06: null while unifiedExecutor is off, and every use below is behind that null.
        UnifiedExecutor ux = Baritone.settings().unifiedExecutor.value ? baritone.getUnifiedExecutor() : null;
        if (ctx.player().isInWater() || ctx.player().isInLava()
                || ctx.player().onClimbable() || ctx.player().isFallFlying() || ctx.player().isPassenger()) {
            return drop(ux, pathPosition);
        }
        // Ostinato: the old switch still forces physics when the stack did not ask. Auto asks on its own.
        if (!stackAsked && !Baritone.settings().physicsTravel.value) {
            return drop(ux, pathPosition);
        }
        if (cooldown > 0) {
            cooldown--;
            return drop(ux, pathPosition);
        }
        world.reset();
        ClientWorld.readPlayer(ctx, real);
        real.jumpTicks = jumpTicks;
        if (ux != null && ux.cancelDue(real.onGround)) {
            // Ostinato 2.0 Phase 06: a cancel has been refused two ticks running and the player is on its feet. Stop
            // here, keys up, and report the tick safe, so the cancel goes through next tick instead of waiting for a hand-back.
            baritone.getInputOverrideHandler().clearAllKeys();
            ux.stopForCancel();
            plan = null;
            target = -1;
            return pathPosition;
        }

        int end = pathPosition;
        List<?> moves = path.movements();
        BlockStateInterface bsi = null;
        while (end < moves.size() && end - pathPosition < LOOKAHEAD) {
            Movement m = (Movement) moves.get(end);
            if (!land(m)) break;
            if (m.toBreakCached == null || m.toPlaceCached == null) {
                if (bsi == null) bsi = new BlockStateInterface(ctx);
                m.toBreak(bsi);
                m.toPlace(bsi);
            }
            // The search knows physics only: what has to be dug or bridged stays with Baritone.
            if (!m.toBreakCached.isEmpty() || !m.toPlaceCached.isEmpty()) break;
            end++;
        }
        if (end == pathPosition) return drop(ux, pathPosition);
        BetterBlockPos goal = path.positions().get(end);
        // Ostinato 2.0 Phase 06: the lease before anything is planned or pressed. Refused, the next layer drives.
        if (ux != null && !ux.beginWrite(path, pathPosition)) return drop(ux, pathPosition);

        boolean drifted = plan != null && step > 0 && drifted(predicted, real, PredictionTolerance.active(), predictedMode);
        if (ux != null) {
            drifted = checkDivergence(ux, drifted, end, goal);
        }
        PathViz viz = PathViz.get();
        if (plan == null || step >= plan.size() || drifted || end != target) {
            String why = plan == null ? null : drifted ? "drift" : end != target ? "new target" : "plan done";
            target = end;
            step = 0;
            corrections = 0;
            plans.incrementAndGet();
            if (drifted) driftReplans.incrementAndGet();
            plan = finder.nativeSearch(Baritone.settings().nativeAcceleration.value).plan(real, goal.x + 0.5, goal.y, goal.z + 0.5, 0.35);
            if ((plan == null || plan.isEmpty()) && ux != null && drifted) {
                // Ostinato 2.0 Phase 06A: the correction sent this replan and it found nothing. Search once more, deeper;
                // not a plan or a drift replan of its own. Empty again, the failure below runs as before.
                ux.bump(UnifiedExecutor.Counter.CORRECTION_RETRIES);
                if (retryFinder == null) retryFinder = new PhysicsPathfinder(world, CorrectionPolicy.RETRY_NODES).viz(viz);
                plan = retryFinder.nativeSearch(Baritone.settings().nativeAcceleration.value).plan(real, goal.x + 0.5, goal.y, goal.z + 0.5, 0.35);
                if (plan != null && !plan.isEmpty()) ux.bump(UnifiedExecutor.Counter.CORRECTION_RETRY_PLANS);
            }
            if (plan == null || plan.isEmpty()) {
                cooldown = 20;
                if (ux != null) ux.fail(UnifiedExecutor.Cause.EMPTY_PLAN);
                return drop(ux, pathPosition);
            }
            if (viz.searchOn()) {
                // Ostinato path viz (P3): the states the plan predicts, one per tick
                viz.newPlan(real.x, real.y, real.z, why);
                PlayerSim p = new PlayerSim(world).copyFrom(real);
                viz.plannedPoint(p.x, p.y, p.z);
                for (int i = 0; i < plan.size() && i < PathViz.MAX_PLAN - 1; i++) {
                    PhysicsPathfinder.Action pa = plan.get(i);
                    p.tick(pa.yaw, pa.forward, pa.sprint, pa.jump);
                    viz.plannedPoint(p.x, p.y, p.z);
                }
            }
        }
        PhysicsPathfinder.Action a = plan.get(step);
        // Set yaw now: the look behaviour applies a tick late, and the plan assumes this tick's yaw.
        ctx.player().setYRot(a.yaw);
        baritone.getLookBehavior().updateTarget(new Rotation(a.yaw, 0), false);
        if (real.onGround && offPlan(baritone.getLookBehavior().getAimProcessor().peekRotation(new Rotation(a.yaw, 0)).getYaw(), a.yaw)) {
            // Ostinato Presto v4: the move this tick goes the aim processor's way, at most 32° a tick from the last
            // sent yaw, not the plan's. Turn in place first: with the keys down the walk went out sideways
            // (presto-v4-fix4 laps 2, 5: a lap started facing back, the yaw swung through south, z 0.5 to 1.15 in four
            // ticks, off the one-wide walkway). The plan step waits; the drift check replans if the turn cost it.
            baritone.getInputOverrideHandler().clearAllKeys();
            if (ux != null) ux.endWrite(false, false, false);
            predicted.copyFrom(real);
            predictedMode = PredictionTolerance.active() == null ? null : PredictionTolerance.classify(real, false, false);
            predicted.tick(a.yaw, false, false, false);
            return pathPosition;
        }
        step++;
        viz.actualPoint(real.x, real.y, real.z);
        viz.physTick(real.x, real.y, real.z, real.vx, real.vy, real.vz, real.onGround, real.climbable(), a.yaw,
                (a.forward ? PathViz.IN_W : 0) | (a.sprint ? PathViz.IN_SPRINT : 0) | (a.jump ? PathViz.IN_JUMP : 0));
        predicted.copyFrom(real);
        predictedMode = PredictionTolerance.active() == null ? null : PredictionTolerance.classify(real, a.forward, a.sprint);
        predicted.tick(a.yaw, a.forward, a.sprint, a.jump);
        jumpTicks = predicted.jumpTicks;
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, a.forward);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, a.sprint);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, a.jump);
        if (ux != null) ux.endWrite(a.forward, a.sprint, a.jump);

        BetterBlockPos feet = ctx.playerFeet();
        for (int i = end; i > pathPosition; i--) {
            if (path.positions().get(i).equals(feet)) return Math.min(i, moves.size() - 1);
        }
        return pathPosition;
    }

    /** Beyond this the yaw the move uses is not the plan's: {@link #offPlan}. */
    static final float TURN_GATE = 45f;

    /** Whether a move at {@code moveYaw} is too far off the plan's {@code planYaw} to press forward on. */
    public static boolean offPlan(float moveYaw, float planYaw) {
        float d = (moveYaw - planYaw) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return Math.abs(d) > TURN_GATE;
    }

    /**
     * Ostinato 2.0 Phase 06, slices B and C: count the predicted-vs-observed check, and spend at most
     * {@link CorrectionPolicy#BUDGET} correction per plan on a drift. A correction keeps the plan when replaying its
     * remaining steps from the observed state still ends on the goal; otherwise the drift escalates to the replan below,
     * as it always has. Uses the drift check as it is: the Phase 04 tolerance stays whatever its own setting says.
     *
     * @return whether to replan for drift
     */
    private boolean checkDivergence(UnifiedExecutor ux, boolean drifted, int end, BetterBlockPos goal) {
        boolean predictedTick = plan != null && step > 0;
        CorrectionPolicy.Verdict v = CorrectionPolicy.judge(predictedTick, drifted, corrections,
                predictedTick && step >= plan.size(), end != target);
        if (v == CorrectionPolicy.Verdict.REPLAY) {
            corrections++;
            if (scratch == null) {
                scratch = new PlayerSim(world);
            }
            v = CorrectionPolicy.replayed(CorrectionPolicy.replayReaches(scratch, real, plan, step,
                    goal.x + 0.5, goal.y, goal.z + 0.5, 0.35));
        }
        v.count(ux);
        return v.replan;
    }

    /** Hands the controls back. With the unified executor on, the keys this driver pressed are lifted first. */
    private int drop(UnifiedExecutor ux, int pathPosition) {
        if (ux != null) ux.handback(UnifiedExecutor.Cause.DROP, pathPosition);
        jumpTicks = 0;
        plan = null;
        target = -1;
        return -1;
    }

    private static boolean land(Movement m) {
        if (m instanceof MovementDescend || m instanceof MovementFall) {
            // Further down hurts, and a fall that long was planned with a bucket or a clutch to end it.
            return m.getSrc().y - m.getDest().y <= MAX_DROP;
        }
        return m instanceof MovementTraverse || m instanceof MovementAscend
                || m instanceof MovementDiagonal || m instanceof MovementParkour;
    }
}
