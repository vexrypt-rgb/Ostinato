package baritone.movement;

import baritone.api.IBaritone;
import baritone.api.movement.IMovementEngine;
import baritone.api.movement.MovementBackendKind;
import baritone.api.movement.MovementFailureReason;
import baritone.api.movement.MovementGoal;
import baritone.api.movement.MovementStatus;
import baritone.api.movement.PathResult;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.Helper;

/**
 * Travel for agent layers on Baritone custom-goal pathing (which the kinematic and physics travel drivers fly).
 * Mining / builder stay on classic Baritone processes.
 */
public final class HybridMovementEngine implements IMovementEngine, Helper {

    private final IBaritone baritone;
    private MovementStatus lastStatus = MovementStatus.IDLE;
    private MovementGoal activeGoal;

    public HybridMovementEngine(IBaritone baritone) {
        this.baritone = baritone;
    }

    /** Factory used by TenorClef / commands. */
    public static HybridMovementEngine forBaritone(IBaritone baritone) {
        return new HybridMovementEngine(baritone);
    }

    @Override
    public PathResult goTo(MovementGoal goal) {
        if (goal == null) {
            lastStatus = MovementStatus.FAILED;
            return PathResult.failed(MovementFailureReason.UNSUPPORTED_GOAL, MovementBackendKind.BARITONE, "null goal");
        }
        if (goal.getKind() == MovementGoal.Kind.FOLLOW_ENTITY && goal.getEntity() == null) {
            lastStatus = MovementStatus.FAILED;
            return PathResult.failed(MovementFailureReason.UNSUPPORTED_GOAL, MovementBackendKind.BARITONE, "null entity");
        }
        Goal baritoneGoal = goal.toBaritoneGoal();
        if (baritoneGoal == null) {
            lastStatus = MovementStatus.FAILED;
            return PathResult.failed(MovementFailureReason.UNSUPPORTED_GOAL, MovementBackendKind.BARITONE, "cannot convert goal");
        }
        try {
            if (baritone == null) {
                lastStatus = MovementStatus.FAILED;
                return PathResult.failed(MovementFailureReason.BACKEND_UNAVAILABLE, MovementBackendKind.BARITONE, "no IBaritone");
            }
            baritone.getCustomGoalProcess().setGoalAndPath(baritoneGoal);
            activeGoal = goal;
            lastStatus = MovementStatus.PATHING;
            return PathResult.accepted(MovementBackendKind.BARITONE, MovementStatus.PATHING);
        } catch (Throwable t) {
            lastStatus = MovementStatus.FAILED;
            logDirect("HybridMovementEngine: Baritone path exception: " + t);
            return PathResult.failed(MovementFailureReason.EXCEPTION, MovementBackendKind.BARITONE, String.valueOf(t));
        }
    }

    @Override
    public MovementStatus status() {
        if (lastStatus == MovementStatus.PATHING) {
            if (!isPathing()) {
                // Idle after path — treat as arrived only if we still have a goal context;
                // callers should verify with world position.
                lastStatus = MovementStatus.IDLE;
            }
        }
        return lastStatus;
    }

    @Override
    public MovementBackendKind activeBackend() {
        return MovementBackendKind.BARITONE;
    }

    @Override
    public boolean isPathing() {
        if (baritone == null) {
            return false;
        }
        try {
            if (baritone.getCustomGoalProcess().isActive()) {
                return true;
            }
            return baritone.getPathingBehavior().isPathing();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void cancel() {
        if (baritone != null) {
            try {
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
            } catch (Throwable ignored) {
            }
        }
        activeGoal = null;
        lastStatus = MovementStatus.CANCELLED;
    }

    @Override
    public String statusLine() {
        return "HybridMovementEngine backend=" + MovementBackendKind.BARITONE
                + " status=" + lastStatus
                + " pathing=" + isPathing();
    }

    public MovementGoal activeGoal() {
        return activeGoal;
    }
}
