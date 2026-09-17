package baritone.movement;

import baritone.api.movement.IMovementBackend;
import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;

/** Marker: do not intercept — CustomGoalProcess uses classic Baritone pathing. */
public final class BaritoneMovementBackend implements IMovementBackend {

    public static final BaritoneMovementBackend INSTANCE = new BaritoneMovementBackend();

    private BaritoneMovementBackend() {}

    @Override public String id() { return "baritone"; }
    @Override public boolean isAvailable() { return true; }
    @Override public boolean pathTo(Goal goal) { return false; }
    @Override public boolean pathTo(BlockPos pos) { return false; }
    @Override public boolean isPathing() { return false; }
    @Override public void cancel() {}
}
