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

package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementLine;
import baritone.pathing.movement.movements.MovementStraight;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.pathing.legato.LegatoFeasibility;
import baritone.pathing.legato.LegatoSmoother;
import baritone.pathing.legato.LegatoStats;
import baritone.Baritone;
import baritone.pathing.path.CutoffPath;
import baritone.utils.pathing.PathBase;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * A node based implementation of IPath
 *
 * @author leijurv
 */
class Path extends PathBase {

    /**
     * The start position of this path
     */
    private final BetterBlockPos start;

    /**
     * The end position of this path
     */
    private final BetterBlockPos end;

    /**
     * The blocks on the path. Guaranteed that path.get(0) equals start and
     * path.get(path.size()-1) equals end
     */
    private List<BetterBlockPos> path;

    private List<Movement> movements;

    private final List<PathNode> nodes;

    private final Goal goal;

    private final int numNodes;

    private final CalculationContext context;

    private volatile boolean verified;

    Path(BetterBlockPos realStart, PathNode start, PathNode end, int numNodes, Goal goal, CalculationContext context) {
        this.end = new BetterBlockPos(end.x, end.y, end.z);
        this.numNodes = numNodes;
        this.movements = new ArrayList<>();
        this.goal = goal;
        this.context = context;

        PathNode current = end;
        List<BetterBlockPos> tempPath = new ArrayList<>();
        List<PathNode> tempNodes = new ArrayList<>();
        while (current != null) {
            tempNodes.add(current);
            tempPath.add(new BetterBlockPos(current.x, current.y, current.z));
            current = current.previous;
        }

        // If the position the player is at is different from the position we told A* to start from,
        // and A* gave us no movements, then add a fake node that will allow a movement to be created
        // that gets us to the single position in the path.
        // See PathingBehavior#createPathfinder and https://github.com/cabaletta/baritone/pull/4519
        var startNodePos = new BetterBlockPos(start.x, start.y, start.z);
        if (!realStart.equals(startNodePos) && start.equals(end)) {
            this.start = realStart;
            PathNode fakeNode = new PathNode(realStart.x, realStart.y, realStart.z, goal);
            fakeNode.cost = 0;
            tempNodes.add(fakeNode);
            tempPath.add(realStart);
        } else {
            this.start = startNodePos;
        }

        // Nodes are traversed last to first so we need to reverse the list
        this.path = Lists.reverse(tempPath);
        this.nodes = Lists.reverse(tempNodes);
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    private boolean assembleMovements() {
        if (path.isEmpty() || !movements.isEmpty()) {
            throw new IllegalStateException("Path must not be empty");
        }
        for (int i = 0; i < path.size() - 1; i++) {
            double cost = nodes.get(i + 1).cost - nodes.get(i).cost;
            Movement move = nodes.get(i + 1).legato
                    ? straight(path.get(i), path.get(i + 1), cost)
                    : runBackwards(path.get(i), path.get(i + 1), cost);
            if (move == null) {
                return true;
            } else {
                movements.add(move);
            }
        }
        return false;
    }

    /** Ostinato Legato: an any-angle search edge; null (path cut here) if the line no longer holds. */
    private Movement straight(BetterBlockPos src, BetterBlockPos dest, double cost) {
        Movement move = new MovementStraight(context.getBaritone(), src, dest);
        double c = move.calculateCost(context);
        if (c >= ActionCosts.COST_INF) {
            Helper.HELPER.logDebug("Legato line became impossible during calculation " + src + " " + dest);
            return null;
        }
        move.override(Math.min(c, cost));
        return move;
    }

    private Movement runBackwards(BetterBlockPos src, BetterBlockPos dest, double cost) {
        // Several Moves can share an offset (e.g. SWIM_*_DOWN and a walking descend), so the first
        // match by position may not be the move the search used. Take the cheapest feasible one.
        Movement best = null, first = null;
        double bestCost = ActionCosts.COST_INF;
        for (Moves moves : Moves.values()) {
            Movement move = moves.apply0(context, src);
            if (move != null && move.getDest().equals(dest)) {
                if (first == null) first = move;
                double c = move.calculateCost(context);
                if (c < bestCost) {
                    best = move;
                    bestCost = c;
                }
            }
        }
        if (best == null) {
            // Ostinato: a jump the search used but the jump slots no longer list (MovementJump.toward)
            best = baritone.pathing.movement.movements.MovementJump.toward(context, src, dest);
            if (best != null) {
                bestCost = best.calculateCost(context);
            }
        }
        if (best == null && first != null) {
            best = first; // none feasible now: keep the old first-match behaviour
            bestCost = first.calculateCost(context);
        }
        if (best != null) {
            // have to calculate the cost at calculation time so we can accurately judge whether a cost increase happened between cached calculation and real execution
            // however, taking into account possible favoring that could skew the node cost, we really want the stricter limit of the two
            // so we take the minimum of the path node cost difference, and the calculated cost
            best.override(Math.min(bestCost, cost));
            return best;
        }
        // this is no longer called from bestPathSoFar, now it's in postprocessing
        Helper.HELPER.logDebug("Movement became impossible during calculation " + src + " " + dest + " " + dest.subtract(src));
        return null;
    }

    @Override
    public IPath postProcess() {
        if (verified) {
            throw new IllegalStateException("Path must not be verified twice");
        }
        verified = true;
        boolean failed = assembleMovements();
        movements.forEach(m -> m.checkLoadedChunk(context));

        if (failed) { // at least one movement became impossible during calculation
            CutoffPath res = new CutoffPath(this, movements().size());
            if (res.movements().size() != movements.size()) {
                throw new IllegalStateException("Path has wrong size after cutoff");
            }
            return res;
        }
        if (context.legatoSmooth) {
            smooth();
        }
        sanityCheck();
        return this;
    }

    /**
     * Ostinato Legato: replaces runs of plain flat walking moves with straight lines where the swept player box fits
     * (see {@link LegatoSmoother}). Every other move is kept exactly; positions stay one more than the moves.
     */
    private void smooth() {
        long t0 = System.nanoTime();
        int n = movements.size();
        if (n < 2) {
            return;
        }
        boolean[] eligible = new boolean[n];
        for (int i = 0; i < n; i++) {
            Movement m = movements.get(i);
            eligible[i] = (m instanceof MovementTraverse || m instanceof MovementDiagonal || m instanceof MovementLine
                    || m instanceof MovementStraight) && m.getSrc().y == m.getDest().y && m.getCost() < ActionCosts.COST_INF
                    && nothingToBreakOrPlace(m);
        }
        LegatoFeasibility.Result check = new LegatoFeasibility.Result();
        List<BetterBlockPos> positions = path;
        List<Movement> moves = movements;
        LegatoSmoother.Plan plan = LegatoSmoother.smooth(new LegatoSmoother.Route() {
            public int moves() { return n; }
            public int x(int p) { return positions.get(p).x; }
            public int y(int p) { return positions.get(p).y; }
            public int z(int p) { return positions.get(p).z; }
            public boolean eligible(int m) { return eligible[m]; }
            public double cost(int m) { return moves.get(m).getCost(); }
        }, (x0, y, z0, x1, z1) -> LegatoFeasibility.evaluate(context, x0, y, z0, x1, z1, check).cost);
        if (plan.unchanged()) {
            LegatoStats.smoothed(0, 0, System.nanoTime() - t0);
            return;
        }
        List<BetterBlockPos> newPath = new ArrayList<>(plan.size);
        List<Movement> newMoves = new ArrayList<>(plan.size - 1);
        newPath.add(path.get(0));
        for (int k = 1; k < plan.size; k++) {
            int step = plan.move[k - 1];
            if (step >= 0) {
                newPath.add(path.get(step + 1));
                newMoves.add(movements.get(step));
                continue;
            }
            BetterBlockPos a = newPath.get(k - 1);
            BetterBlockPos b = new BetterBlockPos(plan.x[k], plan.y[k], plan.z[k]);
            MovementStraight line = new MovementStraight(context.getBaritone(), a, b);
            line.override(line.calculateCost(context));
            line.checkLoadedChunk(context);
            newPath.add(b);
            newMoves.add(line);
            if (Baritone.settings().legatoDebug.value) {
                Helper.HELPER.logDebug("Legato line " + a + " -> " + b);
            }
        }
        LegatoStats.smoothed(plan.lines, plan.replaced, System.nanoTime() - t0);
        path = newPath;
        movements = newMoves;
    }

    /** A plain walk: nothing in the way to mine and solid ground at the end (no bridge block to place). Reads only. */
    private boolean nothingToBreakOrPlace(Movement m) {
        for (net.minecraft.core.BlockPos p : m.toBreakAll()) {
            if (!MovementHelper.canWalkThrough(context.bsi, p.getX(), p.getY(), p.getZ())) {
                return false;
            }
        }
        BetterBlockPos d = m.getDest();
        return MovementHelper.canWalkOn(context.bsi, d.x, d.y - 1, d.z);
    }

    @Override
    public List<IMovement> movements() {
        if (!verified) {
            // edge case note: this is called during verification
            throw new IllegalStateException("Path not yet verified");
        }
        return Collections.unmodifiableList(movements);
    }

    @Override
    public List<BetterBlockPos> positions() {
        return Collections.unmodifiableList(path);
    }

    /** Ostinato: total A* cost (ticks) of the end node, for benchmarks and cost-equality tests. New, replaces nothing. */
    public double totalCost() {
        return nodes.get(nodes.size() - 1).cost;
    }

    @Override
    public int getNumNodesConsidered() {
        return numNodes;
    }

    @Override
    public BetterBlockPos getSrc() {
        return start;
    }

    @Override
    public BetterBlockPos getDest() {
        return end;
    }
}
