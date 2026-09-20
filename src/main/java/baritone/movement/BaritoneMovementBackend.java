package baritone.movement;

import baritone.api.movement.IMovementBackend;
import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;

/**
 * Classic Baritone travel marker for {@link MovementBackends#current()}.
 * <p>
 * {@code pathTo} returns false so {@link baritone.process.CustomGoalProcess} keeps
 * owning classic pathing when this backend is selected. Real Baritone starts go through
 * {@link HybridMovementEngine} (calls {@code ICustomGoalProcess#setGoalAndPath}).
 */
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
