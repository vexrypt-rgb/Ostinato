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


package baritone.gui.hud;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.gui.model.HudFormat;
import baritone.pathing.path.PathExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Snapshot of the primary Baritone's pathing state for the HUD and the screen's mini status card. */
public final class PathStatus {

    public enum State { IDLE, CALCULATING, PATHING, WAITING }

    public State state = State.IDLE;
    public String goal = "";
    public double distance = Double.NaN;
    public int position, length;
    public boolean nextPlanned;
    public double etaGoal = Double.NaN, etaSegment = Double.NaN;
    public String mover = "";
    public String movement = "", nextMovement = "";
    public String process = "";

    public boolean active() {
        return state == State.PATHING || state == State.CALCULATING;
    }

    public static PathStatus capture(IBaritone ib) {
        PathStatus st = new PathStatus();
        if (!(ib instanceof Baritone)) {
            return st;
        }
        Baritone b = (Baritone) ib;
        PathingBehavior pb = b.getPathingBehavior();
        Goal goal = pb.getGoal();
        boolean calculating = pb.getInProgress().isPresent();
        PathExecutor cur = pb.getCurrent();
        if (cur != null) {
            st.state = State.PATHING;
        } else if (calculating) {
            st.state = State.CALCULATING;
        } else if (goal != null) {
            st.state = State.WAITING;
        }
        if (goal != null) {
            int[] pos = null;
            if (goal instanceof IGoalRenderPos) {
                BlockPos p = ((IGoalRenderPos) goal).getGoalPos();
                pos = new int[]{p.getX(), p.getY(), p.getZ()};
                if (b.getPlayerContext().entity() != null) {
                    Vec3 feet = b.getPlayerContext().playerFeetAsVec();
                    st.distance = Math.sqrt(p.distToCenterSqr(feet.x, feet.y, feet.z));
                }
            }
            st.goal = HudFormat.goal(goal.getClass().getSimpleName(), pos, goal.toString());
        }
        if (cur != null) {
            IPath path = cur.getPath();
            st.length = path.movements().size();
            st.position = Math.min(cur.getPosition(), st.length);
            if (st.position < st.length) {
                st.movement = path.movements().get(st.position).getClass().getSimpleName();
            }
            if (st.position + 1 < st.length) {
                st.nextMovement = path.movements().get(st.position + 1).getClass().getSimpleName();
            }
            st.mover = cur.getLastDriver().name();
            st.etaSegment = pb.ticksRemainingInSegment().orElse(Double.NaN);
        }
        st.etaGoal = pb.estimatedTicksToGoal().orElse(Double.NaN);
        st.nextPlanned = pb.getNext() != null;
        IBaritoneProcess proc = b.getPathingControlManager().mostRecentInControl().orElse(null);
        if (proc != null) {
            try {
                st.process = proc.displayName();
            } catch (Throwable t) {
                st.process = proc.getClass().getSimpleName();
            }
        }
        return st;
    }
}
