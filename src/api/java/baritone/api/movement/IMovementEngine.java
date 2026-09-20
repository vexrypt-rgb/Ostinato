package baritone.api.movement;

import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * Stable travel boundary for agent layers (TenorClef).
 * Prefer this over calling Baritone processes or Tungsten APIs directly.
 * Mining / digging / builder stay on classic Baritone processes — not this engine.
 */
public interface IMovementEngine {

    /** Start traveling toward a structured goal. */
    PathResult goTo(MovementGoal goal);

    /** Convenience: stand on / path to a block position. */
    default PathResult goTo(BlockPos pos) {
        return goTo(MovementGoal.getToBlock(pos));
    }

    /** Convenience: path using an existing Baritone {@link Goal}. */
    default PathResult goTo(Goal goal) {
        return goTo(MovementGoal.custom(goal));
    }

    /** Follow / chase an entity (Tungsten when available; else Baritone near-goal). */
    default PathResult follow(Entity entity, double maintainDistance) {
        return goTo(MovementGoal.follow(entity, maintainDistance));
    }

    MovementStatus status();

    /** Backend that last accepted a travel request (or selection hint). */
    MovementBackendKind activeBackend();

    boolean isPathing();

    void cancel();

    /** Human-readable status for commands / debug. */
    String statusLine();
}
