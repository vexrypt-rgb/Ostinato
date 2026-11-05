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
        if (ctx.player().isInWater() || ctx.player().isInLava()
                || ctx.player().onClimbable() || ctx.player().isFallFlying() || ctx.player().isPassenger()) {
            return drop();
        }
        // Ostinato: the old switch still forces physics when the stack did not ask. Auto asks on its own.
        if (!stackAsked && !Baritone.settings().physicsTravel.value) {
            return drop();
        }
        if (cooldown > 0) {
            cooldown--;
            return drop();
        }
        world.reset();
        ClientWorld.readPlayer(ctx, real);
        real.jumpTicks = jumpTicks;

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
        if (end == pathPosition) return drop();
        BetterBlockPos goal = path.positions().get(end);

        boolean drifted = plan != null && step > 0 && drifted(predicted, real, PredictionTolerance.active(), predictedMode);
        PathViz viz = PathViz.get();
        if (plan == null || step >= plan.size() || drifted || end != target) {
            String why = plan == null ? null : drifted ? "drift" : end != target ? "new target" : "plan done";
            target = end;
            step = 0;
            plan = finder.nativeSearch(Baritone.settings().nativeAcceleration.value).plan(real, goal.x + 0.5, goal.y, goal.z + 0.5, 0.35);
            if (plan == null || plan.isEmpty()) {
                cooldown = 20;
                return drop();
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

    private int drop() {
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
