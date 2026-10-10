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
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementTraverse;
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

    public PhysicsTravel(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
        this.real = new PlayerSim(world);
        this.predicted = new PlayerSim(world);
        this.finder = new PhysicsPathfinder(world, NODE_BUDGET);
    }

    /** @return the path position to continue from if this drove the tick, or -1 to let Baritone run it */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        if (!Baritone.settings().physicsTravel.value || ctx.player().isInWater() || ctx.player().isInLava()
                || ctx.player().onClimbable() || ctx.player().isFallFlying() || ctx.player().isPassenger()) {
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

        boolean drifted = plan != null && step > 0
                && Math.abs(predicted.x - real.x) + Math.abs(predicted.y - real.y) + Math.abs(predicted.z - real.z) > DRIFT;
        if (plan == null || step >= plan.size() || drifted || end != target) {
            target = end;
            step = 0;
            plan = finder.plan(real, goal.x + 0.5, goal.y, goal.z + 0.5, 0.35);
            if (plan == null || plan.isEmpty()) {
                cooldown = 20;
                return drop();
            }
        }
        PhysicsPathfinder.Action a = plan.get(step++);
        predicted.copyFrom(real);
        predicted.tick(a.yaw, a.forward, a.sprint, a.jump);
        jumpTicks = predicted.jumpTicks;
        // Set yaw now: the look behaviour applies a tick late, and the plan assumes this tick's yaw.
        ctx.player().setYRot(a.yaw);
        baritone.getLookBehavior().updateTarget(new Rotation(a.yaw, 0), false);
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
