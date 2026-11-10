/*
 * Ostinato: new file (Ostinato 2.0 Phase 05, simulation-derived A*). Not part of upstream Baritone.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.TerminalCostGoal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.bastion.EdgeCost;
import baritone.pathing.CostCalibration;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.JumpFailures;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The legacy A* over the same movement graph, with walking, turning and one-block jumps priced by {@link SimEdge}
 * instead of by their closed-form costs (docs/ostinato2/PHASE05_AUDIT.md). Opt-in ({@code simEdgeSearch}); the
 * default planner is unchanged.
 * <p>
 * A state is a cell and the motion class of the body that arrived there ({@link SimEdge#slot}: rest, 16 velocity
 * headings x walk/sprint x ground/air, or unknown). Each state keeps the body of its cheapest arrival, and every
 * simulated edge out of it starts from that body, so momentum, yaw and sprint carry from one edge to the next. Edges
 * the sim does not cover keep their legacy cost (with the usual calibration) and leave the body unknown; a simulated
 * edge from an unknown body assumes the legacy steady state.
 * <p>
 * Everything else is Maestro's: same budgets, a state cap ({@code simEdgeMaxStates}), the dead set, Cadenza terminal
 * costs, no lazy jump checks, no Theta*, and a fallback to {@link AStarPathFinder} when it finds nothing or throws.
 * Dominance pruning uses {@link SimEdge#MOMENTUM_BOUND} and is approximate (audit §3). Edges are simulated only within
 * {@code simEdgeHorizon} blocks of the start: further out the body is unknown anyway by the time the bot gets there
 * (it replans), and simulating everywhere made long searches time out (docs/ostinato2/BENCHMARKS.md, Phase 05).
 */
public final class SimEdgePathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final CalculationContext calcContext;
    private final double heuristicWeight;
    private final double baseWeight;
    private final int maxStates;
    /** Edges are simulated only out of cells this close to the start (Chebyshev, blocks); 0: everywhere. */
    private final int horizon;
    private LongSet dead;
    private SimEdge.Body startBody;
    private final Long2ObjectOpenHashMap<PathNode[]> states = new Long2ObjectOpenHashMap<>();
    int stateCount;
    private long pruned, inconsistent, simulated, legacyEdges, simFailed;
    /** Tests only: off compares against the unpruned search. */
    static boolean prune = true;
    private volatile AStarPathFinder fallback;
    SimEdge edge;
    /** Phase 05B profile ({@link SimEdge#PROFILE} only): expansions, re-expansions at a lower cost, cells expanded, legacy move evaluations. */
    private long profNodes, profReopened, profApply, profApplyNs;
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet profCells, profApplyKeys;

    public static final AtomicLong searches = new AtomicLong(), fallbacks = new AtomicLong(), exceptions = new AtomicLong(),
            capped = new AtomicLong(), statesCreated = new AtomicLong(), statesPruned = new AtomicLong(),
            sims = new AtomicLong(), simTicks = new AtomicLong(), simEdges = new AtomicLong(), legacy = new AtomicLong(),
            simMisses = new AtomicLong(), inconsistencies = new AtomicLong();

    public SimEdgePathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context, double heuristicWeight) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
        allowReuse = !favoring.hasAvoidance();
        this.baseWeight = heuristicWeight;
        this.heuristicWeight = heuristicWeight * (1 + Baritone.settings().pathTieBreak.value);
        this.maxStates = Baritone.settings().simEdgeMaxStates.value;
        this.horizon = Math.max(0, Baritone.settings().simEdgeHorizon.value);
    }

    public void avoidDead(LongSet dead) {
        this.dead = dead;
    }

    /** The real body at the start cell (the player's), or null to start unknown (a next segment). */
    public void startBody(SimEdge.Body body) {
        this.startBody = body;
    }

    @Override
    public void cancel() {
        super.cancel();
        AStarPathFinder f = fallback;
        if (f != null) {
            f.cancel();
        }
    }

    private PathNode state(int x, int y, int z, long hash, int slot) {
        PathNode[] hs = states.get(hash);
        if (hs == null) {
            hs = new PathNode[SimEdge.SLOTS];
            states.put(hash, hs);
        }
        PathNode n = hs[slot];
        if (n == null) {
            PathNode sibling = null;
            for (PathNode s : hs) {
                if (s != null) {
                    sibling = s;
                    break;
                }
            }
            n = sibling == null ? new PathNode(x, y, z, goal) : new PathNode(sibling);
            n.body = new SimEdge.Body().unknown();
            n.body.slot = slot;
            hs[slot] = n;
            stateCount++;
        }
        return n;
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        long start = System.currentTimeMillis();
        searches.incrementAndGet();
        Optional<IPath> result;
        try {
            result = search(primaryTimeout, failureTimeout);
        } catch (RuntimeException e) {
            exceptions.incrementAndGet();
            logDebug("Sim-edge search failed (" + e + "); falling back to A*");
            result = Optional.empty();
        } finally {
            statesCreated.addAndGet(stateCount);
            statesPruned.addAndGet(pruned);
            inconsistencies.addAndGet(inconsistent);
            simEdges.addAndGet(simulated);
            legacy.addAndGet(legacyEdges);
            simMisses.addAndGet(simFailed);
            if (edge != null) {
                sims.addAndGet(edge.sims);
                simTicks.addAndGet(edge.simTicks);
            }
        }
        if (SimEdge.PROFILE && edge != null) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "SIMEDGEPROF start=%d,%d,%d known=%b ms=%d nodes=%d reopened=%d cells=%d states=%d pruned=%d simulated=%d legacy=%d simMisses=%d"
                            + " sims=%d/%d/%d reached=%d/%d/%d simTicks=%d/%d/%d simUs=%d/%d/%d dupSims=%d apply=%d applyDistinct=%d applyUs=%d timedOut=%b found=%b",
                    startX, startY, startZ, startBody != null && startBody.known, System.currentTimeMillis() - start, profNodes, profReopened,
                    profCells == null ? 0 : profCells.size(), stateCount, pruned, simulated, legacyEdges, simFailed,
                    edge.kindSims[0], edge.kindSims[1], edge.kindSims[2], edge.kindReached[0], edge.kindReached[1], edge.kindReached[2],
                    edge.kindTicks[0], edge.kindTicks[1], edge.kindTicks[2], edge.kindNs[0] / 1000, edge.kindNs[1] / 1000, edge.kindNs[2] / 1000,
                    edge.dupExact, profApply, profApplyKeys == null ? 0 : profApplyKeys.size(), profApplyNs / 1000, timedOut, result.isPresent()));
        }
        if (result.isPresent() || cancelRequested) {
            return result;
        }
        long left = failureTimeout - (System.currentTimeMillis() - start);
        if (left <= 0) {
            return result;
        }
        fallbacks.incrementAndGet();
        AStarPathFinder f = new AStarPathFinder(realStart, startX, startY, startZ, goal, favoring, calcContext, baseWeight);
        if (dead != null) {
            f.avoidDead(dead);
        }
        fallback = f;
        if (cancelRequested) {
            return Optional.empty();
        }
        long primaryLeft = Math.max(0, primaryTimeout - (System.currentTimeMillis() - start));
        Optional<IPath> p = f.calculate0(Math.min(primaryLeft, left), left);
        timedOut = f.timedOut();
        exhausted = f.exhausted();
        return p;
    }

    private Optional<IPath> search(long primaryTimeout, long failureTimeout) {
        boolean lazy = calcContext.lazyJumps;
        calcContext.lazyJumps = false;
        try {
            return search0(primaryTimeout, failureTimeout);
        } finally {
            calcContext.lazyJumps = lazy;
        }
    }

    private Optional<IPath> search0(long primaryTimeout, long failureTimeout) {
        edge = new SimEdge(new BsiWorld(calcContext.bsi), calcContext);
        edge.sprintAscends = Baritone.settings().sprintAscends.value;
        int minY = calcContext.minY;
        int height = calcContext.maxY;
        SimEdge.Body first = new SimEdge.Body().unknown();
        if (startBody != null && startBody.known) {
            first.set(startBody);
        }
        startNode = state(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ), SimEdge.slot(first));
        startNode.body.set(first);
        startNode.cost = 0;
        startNode.combinedCost = heuristicWeight * startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        SimEdge.Result sim = new SimEdge.Result();
        SimEdge.Body unknown = new SimEdge.Body().unknown();
        BetterWorldBorder worldBorder = calcContext.worldBorder;
        long startTime = System.currentTimeMillis();
        long primaryTimeoutTime = startTime + primaryTimeout;
        long failureTimeoutTime = startTime + failureTimeout;
        boolean failing = true;
        int numNodes = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int pathingMaxChunkBorderFetch = Baritone.settings().pathingMaxChunkBorderFetch.value;
        double minimumImprovement = Baritone.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
        Moves[] allMoves = Moves.all();
        double[] moveScale = new double[allMoves.length];
        int[] kinds = new int[allMoves.length];
        for (int i = 0; i < allMoves.length; i++) {
            moveScale[i] = CostCalibration.scale(allMoves[i].family());
            kinds[i] = SimEdge.kind(allMoves[i]);
        }
        TerminalCostGoal terminalGoal = goal instanceof TerminalCostGoal tg ? tg : null;
        while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested) {
            if ((numNodes & 63) == 0) {
                long now = System.currentTimeMillis();
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    timedOut = true;
                    break;
                }
            }
            if (maxStates > 0 && stateCount >= maxStates) {
                capped.incrementAndGet();
                timedOut = true;
                break;
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            boolean wasExpanded = currentNode.expandedCost == currentNode.cost;
            if (SimEdge.PROFILE) {
                profNodes++;
                if (!wasExpanded && currentNode.expandedCost < ActionCosts.COST_INF) profReopened++;
                if (profCells == null) {
                    profCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    profApplyKeys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                }
                profCells.add(BetterBlockPos.longHash(currentNode.x, currentNode.y, currentNode.z));
            }
            currentNode.expandedCost = currentNode.cost;
            double charge = 0;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                PathNode from = currentNode.previous == null ? currentNode : currentNode.previous;
                charge = terminalGoal == null ? 0 : terminalGoal.terminalCost(currentNode.x, currentNode.y, currentNode.z, from.x, from.y, from.z);
                if (!(charge > 0) || currentNode.terminalPaidAt == currentNode.cost) {
                    logDebug("Sim-edge search took " + (System.currentTimeMillis() - startTime) + "ms, " + numNodes + " nodes, " + stateCount
                            + " states, " + simulated + " simulated edges, " + legacyEdges + " legacy");
                    return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
                }
                currentNode.terminalPaidAt = currentNode.cost;
                currentNode.terminalCharge = charge;
                baritone.pathing.cadenza.CadenzaStats.terminalCharge();
            }
            if (charge > 0 && wasExpanded) {
                currentNode.combinedCost = currentNode.cost + heuristicWeight * currentNode.estimatedCostToGoal + charge;
                openSet.insert(currentNode);
                continue;
            }
            if (charge == 0 && dominated(currentNode.x, currentNode.y, currentNode.z, currentNode.cost, currentNode)) {
                pruned++;
                continue;
            }
            SimEdge.Body body = currentNode.body;
            // past the horizon every edge is legacy and every state unknown: one state per cell, as in A*
            boolean near = horizon == 0 || Math.max(Math.abs(currentNode.x - startX), Math.abs(currentNode.z - startZ)) <= horizon;
            for (Moves moves : allMoves) {
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                    if (!moves.dynamicXZ) {
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
                long a0 = SimEdge.PROFILE ? System.nanoTime() : 0;
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                double actionCost = res.cost;
                boolean deferred = res.check != null;
                if (deferred && actionCost < ActionCosts.COST_INF) {
                    double v = res.check.verify();
                    actionCost = v;
                }
                if (SimEdge.PROFILE) {
                    profApplyNs += System.nanoTime() - a0;
                    profApply++;
                    profApplyKeys.add(BetterBlockPos.longHash(currentNode.x, currentNode.y, currentNode.z) * 131 + moves.ordinal());
                }
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (moves.dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) {
                    continue;
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                if (dead != null && dead.contains(hashCode)) {
                    continue;
                }
                double failMul = JumpFailures.multiplier(JumpFailures.moveKey(currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z), startTime);
                int kind = kinds[moves.ordinal()];
                SimEdge.Body end;
                if (near && kind != SimEdge.NONE && !deferred && edge.eligible(kind, currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z)) {
                    if (edge.run(body, kind, currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z, sim)) {
                        // the simulated duration, plus the bastion's hazard penalty the legacy cost would carry; no
                        // CostCalibration scale: that corrects the closed-form costs toward the sim, which this is
                        double penalty = !EdgeCost.enabled ? 0 : kind == SimEdge.TRAVERSE
                                ? EdgeCost.traverse(calcContext, res.x, res.y, res.z) : EdgeCost.penalty(calcContext, res.x, res.y, res.z);
                        actionCost = (sim.cost + penalty) * failMul;
                        end = sim.end;
                        simulated++;
                    } else {
                        simFailed++;
                        actionCost *= moveScale[moves.ordinal()] * failMul;
                        end = unknown;
                        legacyEdges++;
                    }
                } else {
                    actionCost *= moveScale[moves.ordinal()] * failMul;
                    end = unknown;
                    legacyEdges++;
                }
                if (!(actionCost > 0)) {
                    throw new IllegalStateException(moves + " calculated implausible cost " + actionCost);
                }
                double favor = 1;
                if (isFavoring) {
                    favor = cellFavor(res.x, res.y, res.z, hashCode);
                    actionCost *= favor;
                }
                double tentativeCost = currentNode.cost + actionCost;
                if (dominated(res.x, res.y, res.z, tentativeCost, null)) {
                    pruned++;
                    continue;
                }
                PathNode neighbor = state(res.x, res.y, res.z, hashCode, SimEdge.slot(end));
                neighbor.favor = favor;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
                    if (currentNode.estimatedCostToGoal - neighbor.estimatedCostToGoal > actionCost + 1e-6) {
                        inconsistent++; // audit §5: measured, not corrected
                    }
                    int slot = neighbor.body.slot;
                    neighbor.body.set(end);
                    neighbor.body.slot = slot;
                    neighbor.previous = currentNode;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + heuristicWeight * neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                    }
                    if (noteBest(neighbor, bestHeuristicSoFar, minimumImprovement)) {
                        failing = false;
                    }
                }
            }
            if (charge > 0) {
                currentNode.combinedCost = currentNode.cost + heuristicWeight * currentNode.estimatedCostToGoal + charge;
                if (currentNode.isOpen()) {
                    openSet.update(currentNode);
                } else {
                    openSet.insert(currentNode);
                }
            }
        }
        if (cancelRequested) {
            return Optional.empty();
        }
        exhausted = openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !timedOut;
        return bestSoFar(true, numNodes);
    }

    /** Another state of this cell is cheaper by more than any body difference can cost (approximate, audit §3). */
    private boolean dominated(int x, int y, int z, double cost, PathNode self) {
        PathNode[] hs = prune ? states.get(BetterBlockPos.longHash(x, y, z)) : null;
        if (hs == null) {
            return false;
        }
        double bound = cost - SimEdge.MOMENTUM_BOUND;
        for (PathNode n : hs) {
            if (n != null && n != self && n.cost <= bound) {
                return true;
            }
        }
        return false;
    }

    private double cellFavor(int x, int y, int z, long hash) {
        PathNode[] hs = states.get(hash);
        if (hs != null) {
            for (PathNode n : hs) {
                if (n != null && n.favor != 0) {
                    return n.favor;
                }
            }
        }
        return favoring.calculate(x, y, z);
    }

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

    /** The search's own cost of the best path to this state's cell, for tests. */
    double costAt(int x, int y, int z) {
        PathNode[] hs = states.get(BetterBlockPos.longHash(x, y, z));
        double best = ActionCosts.COST_INF;
        if (hs != null) {
            for (PathNode n : hs) {
                if (n != null) best = Math.min(best, n.cost);
            }
        }
        return best;
    }

    public long simulatedEdges() {
        return simulated;
    }

    public long legacyEdges() {
        return legacyEdges;
    }

    public long inconsistent() {
        return inconsistent;
    }

    public boolean fellBack() {
        return fallback != null;
    }

    @Override
    public it.unimi.dsi.fastutil.longs.LongOpenHashSet expandedPositions() {
        if (fallback != null) {
            return fallback.expandedPositions();
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet out = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (var e : states.long2ObjectEntrySet()) {
            for (PathNode n : e.getValue()) {
                if (n != null && n.expandedCost < ActionCosts.COST_INF) {
                    out.add(e.getLongKey());
                    break;
                }
            }
        }
        return out;
    }

    @Override
    protected int mapSize() {
        return stateCount;
    }
}
