/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.secondo;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.secondo.SecondoPolicy.Decision;
import baritone.secondo.SecondoPolicy.Mode;
import net.minecraft.core.BlockPos;

/**
 * Ostinato: the same goals {@code FollowProcess} already uses.
 * Follow and combat-stay are {@link GoalNear} (FollowProcess.towards when it is not walking into the target).
 * A drop is {@link GoalBlock}, which is what FollowProcess.pickup uses ({@code into}).
 * The radius is {@code secondoFollowDistance}, not the global {@code followRadius}, so {@code #follow} is unchanged.
 */
public final class SecondoGoals {

    private SecondoGoals() {
    }

    /** Null when the policy is off, so the process defers and pathing is left alone. */
    public static Goal toGoal(Decision d) {
        if (d == null || !d.paths()) {
            return null;
        }
        BlockPos pos = new BlockPos(d.x(), d.y(), d.z());
        if (d.mode() == Mode.ASSIST_PICKUP) {
            return new GoalBlock(pos);
        }
        return new GoalNear(pos, d.range());
    }
}
