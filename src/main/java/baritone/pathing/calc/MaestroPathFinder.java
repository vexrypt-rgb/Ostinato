/*
 * Ostinato: new file (Maestro). Not part of upstream Baritone.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.TerminalCostGoal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.CostCalibration;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
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
 * Maestro: an action-aware alternative to {@link AStarPathFinder} over the same movement graph and the same move costs.
 * <p>
 * A state is a cell and the heading the bot arrived with (16 sectors of 22.5°, or none after a straight vertical move or
 * at the start). An edge pays its move cost plus a turn charge for the change of heading,
 * {@code maestroTurnCost * 6 * (angle / 180)^2} ticks: a straight continuation is free, a right angle is 1.5 ticks at
 * the default 1, a reversal 6. The charge is never negative and the heuristic is the goal's own, so on this larger state
 * space the search is still admissible (optimal for weight 1), and it prefers routes the executor can hold its sprint
 * and line through. States are sparse: only those reached exist, a cell holds one array of 17 slots, filled only for the headings it is reached with.
 * <p>
 * Budgets: the caller's primary and failure timeouts and a state cap ({@code maestroMaxStates}); a capped search ends
 * like a timed-out one (best partial path). Cancellation is checked every node. When Maestro finds nothing, or throws,
 * it falls back to {@link AStarPathFinder} with whatever is left of the failure budget, so it can never find less than
 * the established planner given that time. Lazy jump checks are off here (every move is checked when offered); Legato
 * smoothing runs as usual on the result (it is path post-processing), while Legato search (Theta*) is A*-only.
 */
public final class MaestroPathFinder extends AbstractNodeCostSearch {

    /** Heading sectors; {@link #NONE} is "no heading". */
    static final int SECTORS = 16;
    static final int NONE = SECTORS;

    private final Favoring favoring;
    private final CalculationContext calcContext;
    private final double heuristicWeight;
    private final double baseWeight;
    private final double turnCost;
    /** The dearest turn (a reversal): a state this much dearer than a sibling heading can never win. */
    private final double maxTurn;
    private final int maxStates;
    private LongSet dead;
    /** Per cell: the states by heading, created on first reach. */
    private final Long2ObjectOpenHashMap<PathNode[]> states = new Long2ObjectOpenHashMap<>();
    int stateCount;
    private long pruned;
    /** Tests only: off compares against the unpruned search. */
    static boolean prune = true;
    private volatile AStarPathFinder fallback;

    public static final AtomicLong searches = new AtomicLong(), fallbacks = new AtomicLong(), exceptions = new AtomicLong(),
            capped = new AtomicLong(), statesCreated = new AtomicLong(), statesPruned = new AtomicLong();

    public MaestroPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context, double heuristicWeight) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
        allowReuse = !favoring.hasAvoidance();
        this.baseWeight = heuristicWeight;
        this.heuristicWeight = heuristicWeight * (1 + Baritone.settings().pathTieBreak.value);
        this.turnCost = Math.max(0, Baritone.settings().maestroTurnCost.value);
        this.maxTurn = turn(0, SECTORS / 2, turnCost);
        this.maxStates = Baritone.settings().maestroMaxStates.value;
    }

    public void avoidDead(LongSet dead) {
        this.dead = dead;
    }

    @Override
    public void cancel() {
        super.cancel();
        AStarPathFinder f = fallback;
        if (f != null) {
            f.cancel();
        }
    }

    /** The heading sector of a horizontal displacement, or {@link #NONE} for none. */
    static int sector(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return NONE;
        }
        double a = Math.atan2(dz, dx); // -pi..pi
        int s = (int) Math.round(a / (2 * Math.PI) * SECTORS);
        return Math.floorMod(s, SECTORS);
    }

    /** The turn charge from one heading to the next, in ticks (0 from or to {@link #NONE}). */
    static double turn(int from, int to, double turnCost) {
        if (from == NONE || to == NONE || from == to) {
            return 0;
        }
        int d = Math.abs(from - to);
        d = Math.min(d, SECTORS - d);
        double frac = d / (SECTORS / 2.0); // 0..1 of a reversal
        return turnCost * 6 * frac * frac;
    }

    private PathNode state(int x, int y, int z, long hash, int heading) {
        PathNode[] hs = states.get(hash);
        if (hs == null) {
            hs = new PathNode[SECTORS + 1];
            states.put(hash, hs);
        }
        PathNode n = hs[heading];
        if (n == null) {
            // the heuristic depends on the cell only: copy it from a sibling instead of asking the goal again
            PathNode sibling = null;
            for (PathNode s : hs) {
                if (s != null) {
                    sibling = s;
                    break;
                }
            }
            n = sibling == null ? new PathNode(x, y, z, goal) : new PathNode(sibling);
            hs[heading] = n;
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
            logDebug("Maestro failed (" + e + "); falling back to A*");
            result = Optional.empty();
        } finally {
            statesCreated.addAndGet(stateCount);
            statesPruned.addAndGet(pruned);
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
        int minY = calcContext.minY;
        int height = calcContext.maxY;
        startNode = state(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ), NONE);
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
        for (int i = 0; i < allMoves.length; i++) {
            moveScale[i] = CostCalibration.scale(allMoves[i].family());
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
                timedOut = true; // more budget could still find a path, like a timeout
                break;
            }
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;
            boolean wasExpanded = currentNode.expandedCost == currentNode.cost;
            currentNode.expandedCost = currentNode.cost;
            double charge = 0;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                // Cadenza terminal costs, exactly as in A*: charged on the first pop, the search ends on the paid pop
                PathNode from = currentNode.previous == null ? currentNode : currentNode.previous; // Cadenza v2: arrival heading
                charge = terminalGoal == null ? 0 : terminalGoal.terminalCost(currentNode.x, currentNode.y, currentNode.z, from.x, from.y, from.z);
                if (!(charge > 0) || currentNode.terminalPaidAt == currentNode.cost) {
                    logDebug("Maestro took " + (System.currentTimeMillis() - startTime) + "ms, " + numNodes + " nodes, " + stateCount + " states");
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
            int heading = headingSlot(currentNode);
            if (charge == 0 && dominated(currentNode.x, currentNode.y, currentNode.z, currentNode.cost, currentNode)) {
                pruned++; // a sibling heading got cheaper by a full turn since this state was queued
                continue;
            }
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
                moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                double actionCost = res.cost;
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (res.check != null) {
                    // lazy checks are off for this search; a move that still defers its check is verified here
                    double v = res.check.verify();
                    if (v >= ActionCosts.COST_INF) {
                        continue;
                    }
                    actionCost = v;
                }
                actionCost *= moveScale[moves.ordinal()];
                actionCost *= JumpFailures.multiplier(JumpFailures.moveKey(currentNode.x, currentNode.y, currentNode.z, res.x, res.y, res.z), startTime);
                if (!(actionCost > 0)) {
                    throw new IllegalStateException(moves + " calculated implausible cost " + actionCost);
                }
                if (moves.dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) {
                    continue;
                }
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                if (dead != null && dead.contains(hashCode)) {
                    continue;
                }
                int next = sector(res.x - currentNode.x, res.z - currentNode.z);
                double favor = 1;
                if (isFavoring) {
                    favor = cellFavor(res.x, res.y, res.z, hashCode);
                    actionCost *= favor;
                }
                double tentativeCost = currentNode.cost + actionCost + turn(heading, next, turnCost);
                if (dominated(res.x, res.y, res.z, tentativeCost, null)) {
                    pruned++;
                    continue;
                }
                PathNode neighbor = state(res.x, res.y, res.z, hashCode, next);
                neighbor.favor = favor;
                if (neighbor.cost - tentativeCost > minimumImprovement) {
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

    /**
     * Whether a state of this cell at {@code cost} can be skipped: another heading here is cheaper by at least a full
     * reversal, so whatever follows costs no more from there (exact: the search stays optimal).
     */
    private boolean dominated(int x, int y, int z, double cost, PathNode self) {
        PathNode[] hs = prune ? states.get(BetterBlockPos.longHash(x, y, z)) : null;
        if (hs == null) {
            return false;
        }
        double bound = cost - maxTurn;
        for (PathNode n : hs) {
            if (n != null && n != self && n.cost <= bound) {
                return true;
            }
        }
        return false;
    }

    /** A cell's favoring multiplier, shared by its heading states (asked of the favoring once per cell). */
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

    /** The heading a state arrived with: its slot in its cell's array. */
    private int headingSlot(PathNode n) {
        PathNode[] hs = states.get(BetterBlockPos.longHash(n.x, n.y, n.z));
        for (int i = 0; i <= SECTORS; i++) {
            if (hs[i] == n) {
                return i;
            }
        }
        throw new IllegalStateException("state not in its cell");
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
