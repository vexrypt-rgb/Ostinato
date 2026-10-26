/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.training.CompassNeedle;
import baritone.training.HeldCompass;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;

/**
 * Ostinato: follow the held or hotbar compass while {@code trainingLegit} and {@code compassFollow} are on.
 * The goal is the block the needle is drawn toward. Priority sits under an explicit goal, so {@code #goto} keeps control.
 */
public final class CompassFollowProcess extends BaritoneProcessHelper {

    /** Arrive on a neighbour of the needle target, not inside a tracked player. */
    private static final int RANGE = 2;

    private CompassNeedle.Reading reading = CompassNeedle.read(CompassNeedle.Kind.NONE, false, "", 0, 0, 0, "", 0, 0, 0);

    public CompassFollowProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (!Baritone.settings().trainingLegit.value || !Baritone.settings().compassFollow.value) {
            return false;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        reading = HeldCompass.read(ctx);
        return reading.aimed;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        reading = HeldCompass.read(ctx);
        if (!reading.aimed) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        Goal goal = new GoalNear(new BlockPos(reading.x, reading.y, reading.z), RANGE);
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        reading = CompassNeedle.read(CompassNeedle.Kind.NONE, false, "", 0, 0, 0, "", 0, 0, 0);
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY - 1;
    }

    @Override
    public String displayName0() {
        if (!reading.aimed) {
            return "Compass";
        }
        return "Compass " + reading.kind + " " + reading.bearing + " " + reading.x + " " + reading.y + " " + reading.z;
    }
}
