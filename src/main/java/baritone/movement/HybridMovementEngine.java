package baritone.movement;

import baritone.api.IBaritone;
import baritone.api.movement.IMovementBackend;
import baritone.api.movement.IMovementEngine;
import baritone.api.movement.MovementBackendKind;
import baritone.api.movement.MovementBackendSelector;
import baritone.api.movement.MovementFailureReason;
import baritone.api.movement.MovementGoal;
import baritone.api.movement.MovementStatus;
import baritone.api.movement.PathResult;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.Helper;
import net.minecraft.world.entity.Entity;

/**
 * Prefer Tungsten travel when available/appropriate; else Baritone custom-goal pathing.
 * Never throws if Tungsten is absent. Mining / builder stay on classic Baritone processes.
 */
public final class HybridMovementEngine implements IMovementEngine, Helper {

    private final IBaritone baritone;
    private MovementBackendKind lastBackend = MovementBackendKind.BARITONE;
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
            return PathResult.failed(MovementFailureReason.UNSUPPORTED_GOAL, lastBackend, "null goal");
        }
        MovementBackendKind preference = MovementBackends.preference();
        boolean tungAvail = TungstenMovementBackend.INSTANCE.isAvailable();
        MovementBackendKind chosen = MovementBackendSelector.select(preference, tungAvail);

        if (goal.getKind() == MovementGoal.Kind.FOLLOW_ENTITY) {
            return followInternal(goal, chosen, tungAvail);
        }

        Goal baritoneGoal = goal.toBaritoneGoal();
        if (baritoneGoal == null) {
            lastStatus = MovementStatus.FAILED;
            return PathResult.failed(MovementFailureReason.UNSUPPORTED_GOAL, chosen, "cannot convert goal");
        }

        if (chosen == MovementBackendKind.TUNGSTEN) {
            PathResult tung = tryTungstenPath(goal, baritoneGoal);
            if (tung.isAccepted()) {
                return tung;
            }
            // Soft-fail → Baritone; never crash.
            logDirect("HybridMovementEngine: Tungsten declined/failed; falling back to Baritone");
        }

        return startBaritone(goal, baritoneGoal);
    }

    private PathResult followInternal(MovementGoal goal, MovementBackendKind chosen, boolean tungAvail) {
        Entity entity = goal.getEntity();
        if (entity == null) {
            lastStatus = MovementStatus.FAILED;
            return PathResult.failed(MovementFailureReason.UNSUPPORTED_GOAL, chosen, "null entity");
        }
        if (chosen == MovementBackendKind.TUNGSTEN && tungAvail) {
            try {
                boolean ok = TungstenMovementBackend.INSTANCE.follow(entity, goal.getMaintainDistance());
                if (ok) {
                    activeGoal = goal;
                    lastBackend = MovementBackendKind.TUNGSTEN;
                    lastStatus = MovementStatus.PATHING;
                    return PathResult.accepted(MovementBackendKind.TUNGSTEN, MovementStatus.PATHING, "tungsten follow");
                }
            } catch (Throwable t) {
                logDirect("HybridMovementEngine: Tungsten follow exception: " + t);
            }
            logDirect("HybridMovementEngine: Tungsten follow failed; falling back to Baritone");
        }
        Goal near = goal.toBaritoneGoal();
        if (near == null) {
            lastStatus = MovementStatus.FAILED;
            return PathResult.failed(MovementFailureReason.UNSUPPORTED_GOAL, MovementBackendKind.BARITONE, "follow->near failed");
        }
        return startBaritone(goal, near);
    }

    private PathResult tryTungstenPath(MovementGoal goal, Goal baritoneGoal) {
        try {
            IMovementBackend tung = TungstenMovementBackend.INSTANCE;
            if (!tung.isAvailable()) {
                return PathResult.failed(MovementFailureReason.BACKEND_UNAVAILABLE, MovementBackendKind.TUNGSTEN, tung.id());
            }
            boolean ok = tung.pathTo(baritoneGoal);
            if (!ok) {
                return PathResult.failed(
                        MovementBackendSelector.mapFailure(true, true),
                        MovementBackendKind.TUNGSTEN,
                        "tungsten pathTo declined");
            }
            activeGoal = goal;
            lastBackend = MovementBackendKind.TUNGSTEN;
            lastStatus = MovementStatus.PATHING;
            return PathResult.accepted(MovementBackendKind.TUNGSTEN, MovementStatus.PATHING);
        } catch (Throwable t) {
            logDirect("HybridMovementEngine: Tungsten path exception: " + t);
            return PathResult.failed(MovementFailureReason.EXCEPTION, MovementBackendKind.TUNGSTEN, String.valueOf(t));
        }
    }

    private PathResult startBaritone(MovementGoal goal, Goal baritoneGoal) {
        try {
            if (baritone == null) {
                lastStatus = MovementStatus.FAILED;
                return PathResult.failed(MovementFailureReason.BACKEND_UNAVAILABLE, MovementBackendKind.BARITONE, "no IBaritone");
            }
            // Cancel Ostinato-owned Tungsten travel before classic pathing.
            if (lastBackend == MovementBackendKind.TUNGSTEN) {
                safeCancelTungsten();
            }
            baritone.getCustomGoalProcess().setGoalAndPath(baritoneGoal);
            activeGoal = goal;
            lastBackend = MovementBackendKind.BARITONE;
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
        return lastBackend;
    }

    @Override
    public boolean isPathing() {
        if (lastBackend == MovementBackendKind.TUNGSTEN) {
            try {
                if (TungstenMovementBackend.INSTANCE.isPathing()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
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
        safeCancelTungsten();
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

    private void safeCancelTungsten() {
        try {
            TungstenMovementBackend.INSTANCE.cancel();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String statusLine() {
        return "HybridMovementEngine backend=" + lastBackend
                + " status=" + lastStatus
                + " pathing=" + isPathing()
                + " | " + MovementBackends.statusLine();
    }

    public MovementGoal activeGoal() {
        return activeGoal;
    }
}
