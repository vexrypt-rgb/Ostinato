package baritone.api.movement;

import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;

/**
 * Alternate travel executor. Classic Baritone remains the mining/digging engine.
 */
public interface IMovementBackend {

    String id();

    boolean isAvailable();

    /** Start traveling toward a goal. False = backend declines (fall back). */
    boolean pathTo(Goal goal);

    boolean pathTo(BlockPos pos);

    boolean isPathing();

    void cancel();
}
