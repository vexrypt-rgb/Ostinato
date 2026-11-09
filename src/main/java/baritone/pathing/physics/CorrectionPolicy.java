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
import baritone.pathing.unified.UnifiedExecutor;

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
    /**
     * Ostinato 2.0 Phase 06A: the node budget of the one retry a correction-triggered replan gets when its first search
     * comes back empty. Twice the driver's own budget; chosen from the Phase 06 slip diagnostic.
     */
    public static final int RETRY_NODES = 3000;

    private CorrectionPolicy() {}

    /** What the predicted-vs-observed check came to on one tick. */
    public enum Verdict {
        /** No prediction to check: the plan is new this tick, or there is none. */
        UNAVAILABLE(false, false),
        /** The player is where the last tick said it would be. */
        ON_PLAN(false, false),
        /** Drifted, and a correction is due: replay the plan, then {@link #replayed}. Never final. */
        REPLAY(false, true),
        /** Drifted, and the replay from where the player is still reaches the goal: the plan is kept. */
        CORRECTED(false, true),
        /** Drifted, and the replay misses: the correction is spent and the drift replans. */
        MISSED(true, true),
        /** Drifted with the correction already spent, or with the plan done or aimed elsewhere: replan. */
        ESCALATED(true, false);

        /** Whether the drift replans. */
        public final boolean replan;
        /** Whether this spent the plan's correction. */
        public final boolean spent;

        Verdict(boolean replan, boolean spent) {
            this.replan = replan;
            this.spent = spent;
        }

        /** Counts this verdict on the executor's divergence counters. */
        public void count(UnifiedExecutor ux) {
            if (this == REPLAY) {
                throw new IllegalStateException("a replay has to be judged before it is counted");
            }
            if (this == UNAVAILABLE) {
                ux.bump(UnifiedExecutor.Counter.PREDICTION_UNAVAILABLE);
                return;
            }
            ux.bump(UnifiedExecutor.Counter.DIVERGENCE_CHECKS);
            if (this == ON_PLAN) {
                return;
            }
            ux.bump(UnifiedExecutor.Counter.DIVERGENCES);
            if (spent) {
                ux.bump(UnifiedExecutor.Counter.CORRECTIONS);
            }
            if (this == CORRECTED) {
                ux.bump(UnifiedExecutor.Counter.CORRECTIONS_ACCEPTED);
            }
            if (replan) {
                ux.bump(UnifiedExecutor.Counter.ESCALATIONS);
            }
        }
    }

    /**
     * The check before any replay. {@link Verdict#REPLAY} means spend the correction: replay the plan with
     * {@link #replayReaches} and pass the answer to {@link #replayed}.
     *
     * @param predicted  whether last tick left a prediction for this one
     * @param drifted    whether the player left it, by the drift check as it stands
     * @param used       corrections this plan has spent
     * @param planDone   whether the plan has no steps left
     * @param retargeted whether the path moved the goal since the plan was made
     */
    public static Verdict judge(boolean predicted, boolean drifted, int used, boolean planDone, boolean retargeted) {
        if (!predicted) {
            return Verdict.UNAVAILABLE;
        }
        if (!drifted) {
            return Verdict.ON_PLAN;
        }
        // A plan that is done or aimed elsewhere is replanned anyway: a correction would save nothing.
        if (used >= BUDGET || planDone || retargeted) {
            return Verdict.ESCALATED;
        }
        return Verdict.REPLAY;
    }

    /** The verdict once the replay a {@link Verdict#REPLAY} asked for has run. */
    public static Verdict replayed(boolean reached) {
        return reached ? Verdict.CORRECTED : Verdict.MISSED;
    }

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
