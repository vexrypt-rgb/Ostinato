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

package baritone.pathing.physics;

import baritone.pathing.kinematic.PlayerSim;

import java.util.List;

/**
 * Ostinato 2.0 Phase 06: the one correction the unified executor tries before a drift replan.
 * <p>
 * When the real player leaves the predicted tick, replay what is left of the current plan from where the player
 * really is. If that replay still ends standing on the goal, the plan is kept and the search is skipped. Otherwise,
 * or once the plan has used its one correction, the drift replans as before.
 */
public final class CorrectionPolicy {

    /** One correction a plan. A second drift on the same plan replans. */
    public static final int BUDGET = 1;
    /** How far below the lower of start and goal the replay may go before it counts as a fall. */
    static final double FLOOR = 1.0;

    private CorrectionPolicy() {}

    /**
     * Whether {@code plan} from {@code step} on, flown from {@code from}, ends on the ground within {@code radius}
     * of the goal, the same test the search stopped on, without dropping below the floor on the way.
     *
     * @param scratch a sim to fly in; overwritten
     */
    public static boolean replayReaches(PlayerSim scratch, PlayerSim from, List<PhysicsPathfinder.Action> plan, int step,
                                        double gx, double gy, double gz, double radius) {
        if (plan == null || step < 0 || step >= plan.size()) {
            return false;
        }
        scratch.copyFrom(from);
        double floor = Math.min(from.y, gy) - FLOOR;
        for (int i = step; i < plan.size(); i++) {
            PhysicsPathfinder.Action a = plan.get(i);
            scratch.tick(a.yaw, a.forward, a.sprint, a.jump);
            if (scratch.y < floor) {
                return false;
            }
        }
        double dx = scratch.x - gx, dy = scratch.y - gy, dz = scratch.z - gz;
        return scratch.onGround && dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
