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

import baritone.pathing.movement.JumpFailures;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.movements.MovementTas;
import baritone.pathing.CostCalibration;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;

import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;

    /** Ostinato: optional corridor for the hierarchical planner; nodes it rejects are never expanded into. Null = stock. */
    public interface NodeFilter {
        boolean allow(int x, int y, int z);
    }

    NodeFilter nodeFilter;

    /**
     * Ostinato: positions an earlier exhausted search toward the same goal proved cannot reach it; never expanded into.
     * Null = stock.
     */
    private it.unimi.dsi.fastutil.longs.LongSet dead;

    /** Ostinato: an unchecked offer was dropped as one too many, so an empty open set proves nothing. */
    private boolean lossy;

    public void avoidDead(it.unimi.dsi.fastutil.longs.LongSet dead) {
        this.dead = dead;
    }
    private final CalculationContext calcContext;

    /**
     * Ostinato: weight on h in the open-set key (f = g + weight * h), i.e. explicit weighted A*. 1 is stock Baritone.
     * With an admissible h the path found costs at most weight times the optimum; the best-so-far partial-path
     * coefficients still use the unweighted h. See Settings#pathingFarSegmentWeight.
     */
    private final double heuristicWeight;

    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
        this(realStart, startX, startY, startZ, goal, favoring, context, 1);
    }

    // Ostinato: the weighted constructor; the original one above delegates here with weight 1 (unchanged behaviour).
    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context, double heuristicWeight) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
        allowReuse = !favoring.hasAvoidance();
        // Ostinato: tie-break toward the goal (lower h wins among equal f) by nudging the weight up a hair (pathTieBreak)
        this.heuristicWeight = heuristicWeight * (1 + Baritone.settings().pathTieBreak.value);
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        int minY = calcContext.minY;
        calcContext.lazyJumps = Baritone.settings().lazyJumpChecks.value;
        int height = calcContext.maxY;
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = heuristicWeight * startNode.estimatedCostToGoal; // Ostinato: weighted (weight 1 = stock)
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = calcContext.worldBorder;
        long startTime = System.currentTimeMillis();
        boolean slowPath = Baritone.settings().slowPath.value;
        if (slowPath) {
            logDebug("slowPath is on, path timeout will be " + Baritone.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
        }
        long primaryTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : primaryTimeout);
        long failureTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout);
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        int numEmptyChunk = 0;
        int numRejected = 0;
        int numFavored = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        int pathingMaxChunkBorderFetch = Baritone.settings().pathingMaxChunkBorderFetch.value; // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
        double minimumImprovement = Baritone.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
        Moves[] allMoves = Moves.all();
        // One scale per move for this search. The old lookup built a family string and hit the map on every neighbor.
        double[] moveScale = new double[allMoves.length];
        for (int i = 0; i < allMoves.length; i++) {
            moveScale[i] = CostCalibration.scale(allMoves[i].family());
        }
        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    timedOut = true;
                    break;
                }
            }
            if (slowPath) {
                try {
                    Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
                } catch (InterruptedException ignored) {}
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            if (currentNode.pending != null) {
                // Ostinato: popped on an unchecked offer: check it now. Passed at the offered cost, the node expands as
                // usual; otherwise it goes back in at whatever it is worth now (its checked cost or the next offer).
                PendingOffer o = currentNode.pending;
                currentNode.pending = o.next;
                double v = o.check.verify();
                double checked = v >= ActionCosts.COST_INF ? ActionCosts.COST_INF : o.cost + (v - o.raw) * o.mult;
                if (currentNode.cost - checked > minimumImprovement) {
                    currentNode.cost = checked;
                    currentNode.previous = o.prev;
                    prune(currentNode, minimumImprovement);
                    if (noteBest(currentNode, bestHeuristicSoFar, minimumImprovement)) {
                        failing = false;
                    }
                }
                if (v != o.raw || currentNode.pending != null || currentNode.cost != checked) {
                    double key = currentNode.pending != null ? currentNode.pending.cost : currentNode.cost;
                    if (currentNode.pending != null || currentNode.expandedCost - currentNode.cost > minimumImprovement) {
                        if (key < ActionCosts.COST_INF) {
                            currentNode.combinedCost = key + heuristicWeight * currentNode.estimatedCostToGoal;
                            openSet.insert(currentNode);
                        }
                    }
                    continue;
                }
            }
            currentNode.expandedCost = currentNode.cost;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                logDebug(profileLine(numNodes, numMovementsConsidered, numRejected, numEmptyChunk, numFavored));
                logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
                return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
            }
            for (Moves moves : allMoves) {
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
                    if (!moves.dynamicXZ) { // only increment the counter if the movement would have gone out of bounds guaranteed
                        numEmptyChunk++;
                    }
                    continue;
                }
                if (!moves.dynamicXZ && !worldBorder.entirelyContains(newX, newZ)) {
                    continue;
                }
                if (currentNode.y + moves.yOffset > height || currentNode.y + moves.yOffset < minY) {
                    continue;
                }
                res.reset();
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                numMovementsConsidered++;
                double actionCost = res.cost;
                if (actionCost >= ActionCosts.COST_INF) {
                    numRejected++;
                    continue;
                }
                actionCost *= moveScale[moves.ordinal()];
                actionCost *= JumpFailures.multiplier(JumpFailures.moveKey(currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z), startTime);
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s calculated implausible cost %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            actionCost));
                }
                // check destination after verifying it's not COST_INF -- some movements return COST_INF without adjusting the destination
                if (moves.dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) { // see issue #218
                    continue;
                }
                if (!moves.dynamicXZ && (res.x != newX || res.z != newZ)) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at x z %s %s instead of %s %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            SettingsUtil.maybeCensor(res.x),
                            SettingsUtil.maybeCensor(res.z),
                            SettingsUtil.maybeCensor(newX),
                            SettingsUtil.maybeCensor(newZ)));
                }
                if (!moves.dynamicY && res.y != currentNode.y + moves.yOffset) {
                    throw new IllegalStateException(String.format(
                            "%s from %s %s %s ended at y %s instead of %s",
                            moves,
                            SettingsUtil.maybeCensor(currentNode.x),
                            SettingsUtil.maybeCensor(currentNode.y),
                            SettingsUtil.maybeCensor(currentNode.z),
                            SettingsUtil.maybeCensor(res.y),
                            SettingsUtil.maybeCensor(currentNode.y + moves.yOffset)));
                }
                if (nodeFilter != null && !nodeFilter.allow(res.x, res.y, res.z)) { // Ostinato: corridor (null in stock planning)
                    continue;
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                if (dead != null && dead.contains(hashCode)) {
                    continue;
                }
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                if (isFavoring) {
                    // see issue #18
                    // Favoring depends only on the destination cell (map plus live mobs), so look it up once per node.
                    // The once-per-node cache is from Soprano (https://github.com/AverWasTaken/soprano, LGPL-3.0).
                    numFavored++;
                    if (neighbor.favor == 0) {
                        neighbor.favor = favoring.calculate(res.x, res.y, res.z);
                    }
                    actionCost *= neighbor.favor;
                }
                double tentativeCost = currentNode.cost + actionCost;
                if (res.check != null) {
                    // Ostinato: an unchecked move: queued on the node, which keeps its checked cost and parent for now
                    if (neighbor.cost - tentativeCost > minimumImprovement && offer(neighbor, new PendingOffer(tentativeCost, res.cost, actionCost / res.cost, currentNode, res.check))) {
                        neighbor.combinedCost = neighbor.pending.cost + heuristicWeight * neighbor.estimatedCostToGoal;
                        if (neighbor.isOpen()) {
                            openSet.update(neighbor);
                        } else {
                            openSet.insert(neighbor);
                        }
                    }
                    continue;
                }
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    prune(neighbor, minimumImprovement); // Ostinato: offers no cheaper than this need no check
                    neighbor.combinedCost = (neighbor.pending != null ? neighbor.pending.cost : tentativeCost) + heuristicWeight * neighbor.estimatedCostToGoal; // Ostinato: weighted (weight 1 = stock)
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);//dont double count, dont insert into open set if it's already there
                    }
                    if (noteBest(neighbor, bestHeuristicSoFar, minimumImprovement)) {
                        failing = false;
                    }
                }
            }
        }
        if (cancelRequested) {
            return Optional.empty();
        }
        exhausted = openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && nodeFilter == null && !lossy;
        System.out.println(numMovementsConsidered + " movements considered");
        System.out.println("Open set size: " + openSet.size());
        System.out.println("PathNode map size: " + mapSize());
        System.out.println((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
        Optional<IPath> result = bestSoFar(true, numNodes);
        MovementTas.frontierLater(calcContext, nodes(), goal);
        if (result.isPresent()) {
            logDebug(profileLine(numNodes, numMovementsConsidered, numRejected, numEmptyChunk, numFavored));
            logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        }
        return result;
    }

    /**
     * Ostinato: max unchecked offers queued on one node; a costlier one is dropped, for good. Four lost cubed level 7's
     * only route once jumps past fence rails were offered too: they filled the queue and failed their sims.
     */
    private static final int MAX_PENDING = 16;

    /** Queues an unchecked offer in cost order; false when it is dropped as one too many. */
    private boolean offer(PathNode node, PendingOffer o) {
        PendingOffer prev = null, at = node.pending;
        int n = 0;
        while (at != null && at.cost <= o.cost) {
            prev = at;
            at = at.next;
            n++;
        }
        if (n >= MAX_PENDING) {
            lossy = true;
            return false;
        }
        o.next = at;
        if (prev == null) {
            node.pending = o;
        } else {
            prev.next = o;
        }
        for (PendingOffer e = o; e.next != null; e = e.next) {
            if (++n >= MAX_PENDING) {
                lossy = true;
                e.next = null;
                break;
            }
        }
        return true;
    }

    /** Drops queued offers no cheaper than the node's checked cost. */
    private static void prune(PathNode node, double minimumImprovement) {
        PendingOffer prev = null;
        for (PendingOffer e = node.pending; e != null; prev = e, e = e.next) {
            if (!(node.cost - e.cost > minimumImprovement)) {
                if (prev == null) {
                    node.pending = null;
                } else {
                    prev.next = null;
                }
                return;
            }
        }
    }

    /** Updates the best node by each coefficient; true when one of them is past MIN_DIST_PATH from the start. */
    private boolean noteBest(PathNode node, double[] bestHeuristicSoFar, double minimumImprovement) {
        boolean far = false;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double heuristic = node.estimatedCostToGoal + node.cost * INVERSE_COEFFICIENTS[i];
            if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                bestHeuristicSoFar[i] = heuristic;
                bestSoFar[i] = node;
                if (getDistFromStartSq(node) > MIN_DIST_PATH * MIN_DIST_PATH) {
                    far = true;
                }
            }
        }
        return far;
    }

    private static String profileLine(int nodes, int movements, int rejected, int emptyChunks, int favored) {
        SearchProfile profile = new SearchProfile();
        profile.nodes = nodes;
        profile.movements = movements;
        profile.rejected = rejected;
        profile.emptyChunks = emptyChunks;
        profile.favored = favored;
        return profile.line();
    }
}
