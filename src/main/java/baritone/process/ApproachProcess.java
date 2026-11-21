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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;

/**
 * Ostinato: Phase 2c part 2, walk-to-verify. Moves a swarm bot toward a shared observation it has to check where it
 * is, along routes planned in an approach context ({@link CalculationContext#restrictForApproach()}: no breaking, no
 * placing, no fall over {@link CalculationContext#APPROACH_MAX_FALL}). Idle unless the swarm sets a goal, so every other
 * path is planned exactly as before. When the planner finds no such route, the approach stops and says so
 * ({@link #noRoute()}), so the lease can end unknown at once instead of waiting out the budget.
 */
public final class ApproachProcess extends BaritoneProcessHelper {

    private Goal goal;
    private boolean requested;
    private boolean noRoute;

    public ApproachProcess(Baritone baritone) {
        super(baritone);
    }

    /** Start (or restart) walking toward {@code goal}. */
    public synchronized void approach(Goal goal) {
        this.goal = goal;
        this.requested = true;
        this.noRoute = false;
    }

    /** Stop walking; the next process in line takes over. */
    public synchronized void stop() {
        this.goal = null;
        this.requested = false;
    }

    public synchronized Goal goal() {
        return goal;
    }

    /** True once the planner found no approach route to the current goal (cleared by the next {@link #approach}). */
    public synchronized boolean noRoute() {
        return noRoute;
    }

    @Override
    public synchronized boolean isActive() {
        return goal != null;
    }

    @Override
    public synchronized PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (goal == null) {
            return null;
        }
        if (calcFailed) {
            noRoute = true;
            Goal g = goal;
            goal = null;
            logDirect("swarm-lease: no approach route to " + g + " without breaking, placing or falling more than "
                    + CalculationContext.APPROACH_MAX_FALL + " blocks");
            return new PathingCommand(g, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        CalculationContext context = CalculationContext.forApproach(baritone);
        if (requested) {
            requested = false;
            return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, context);
        }
        return new PathingCommandContext(goal, PathingCommandType.SET_GOAL_AND_PATH, context);
    }

    @Override
    public synchronized void onLostControl() {
        goal = null;
        requested = false;
    }

    @Override
    public String displayName0() {
        Goal g = goal;
        return "Approach " + (g == null ? "(none)" : g);
    }
}
