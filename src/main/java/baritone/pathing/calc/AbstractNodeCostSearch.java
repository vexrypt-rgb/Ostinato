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

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.RouteReuse;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Optional;

/**
 * Any pathfinding algorithm that keeps track of nodes recursively by their cost (e.g. A*, dijkstra)
 *
 * @author leijurv
 */
public abstract class AbstractNodeCostSearch implements IPathFinder, Helper {

    protected final BetterBlockPos realStart;
    protected final int startX;
    protected final int startY;
    protected final int startZ;

    protected final Goal goal;

    private final CalculationContext context;

    /**
     * Nodes live in 16x8x16 cells and only the cells go in the map, so neighbouring lookups hit the cell just used
     * instead of missing the cache in one big position-to-node map. Ported from Soprano
     * (https://github.com/AverWasTaken/soprano, LGPL-3.0, by AverWasTaken),
     * src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/107">Issue #107</a>
     */
    private final Long2ObjectOpenHashMap<PathNode[]> cells;
    /**
     * Ostinato: one 16×16 slice per y inside the same cell key, allocated only for a y the search touches.
     * Null when {@code piCheapPathing} is off. {@link #cells} is null when it is on.
     */
    private final Long2ObjectOpenHashMap<PathNode[][]> piCells;
    /** Ostinato: snapshot of piCheapPathing. Off keeps the stock 16×8×16 cells. */
    protected final boolean piCheap;
    private PathNode[] prevCell;
    private long prevCellKey = Long.MIN_VALUE;
    private PathNode[][] prevSlices;
    private PathNode[] prevSlice;
    private long prevPiKey = Long.MIN_VALUE;
    private int prevSliceY = Integer.MIN_VALUE;
    private int numNodesCreated;
    private static final PathNode[] NO_SLICE = new PathNode[0];

    private static final int CELL_BITS_X = 4;
    private static final int CELL_BITS_Y = 3;
    private static final int CELL_BITS_Z = 4;
    private static final int CELL_SIZE = 1 << (CELL_BITS_X + CELL_BITS_Y + CELL_BITS_Z);

    /** Ostinato: every node the search has reached. */
    protected Iterable<PathNode> nodes() {
        if (piCells != null) {
            return () -> new PiNodeIter(piCells);
        }
        java.util.ArrayList<PathNode> all = new java.util.ArrayList<>(numNodesCreated);
        for (PathNode[] cell : cells.values()) {
            for (int i = 0; i < cell.length; i++) {
                if (cell[i] != null) {
                    all.add(cell[i]);
                }
            }
        }
        return all;
    }

    protected PathNode startNode;

    protected PathNode mostRecentConsidered;

    protected final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];

    private volatile boolean isFinished;

    protected boolean cancelRequested;

    /** Ostinato: the last search stopped on its time limit with nodes still open, not on an exhausted open set. */
    protected volatile boolean timedOut;

    /**
     * Ostinato: the last search emptied its open set without reaching the goal, with every move out of every node it
     * expanded tried: no node it expanded can reach the goal (within this search's sim budgets).
     */
    protected volatile boolean exhausted;

    /** Replay a stored route only when this search's costs were not shaped by mobs. */
    protected boolean allowReuse;

    /**
     * Ostinato: when this search runs out of time and its best partial segment opens with a drop it cannot climb back,
     * report a failure (so a longer search follows) rather than a segment that jumps down blind. Set by the caller
     * when such a longer search is still allowed and the goal is close enough for it to settle.
     */
    public boolean holdAtDrops;

    /** A drop this deep cannot be climbed back without placing blocks. */
    static final int IRREVERSIBLE_DROP = 3;

    /**
     * This is really complicated and hard to explain. I wrote a comment in the old version of MineBot but it was so
     * long it was easier as a Google Doc (because I could insert charts).
     *
     * @see <a href="https://docs.google.com/document/d/1WVHHXKXFdCR1Oz__KtK8sFqyvSwJN_H4lftkHFgmzlc/edit">here</a>
     */
    protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};

    /** 1 / COEFFICIENTS, so the inner loop multiplies instead of dividing (from Soprano's AbstractNodeCostSearch). */
    protected static final double[] INVERSE_COEFFICIENTS = new double[COEFFICIENTS.length];

    static {
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            INVERSE_COEFFICIENTS[i] = 1 / COEFFICIENTS[i];
        }
    }

    /**
     * If a path goes less than 5 blocks and doesn't make it to its goal, it's not worth considering.
     */
    protected static final double MIN_DIST_PATH = 5;

    /**
     * there are floating point errors caused by random combinations of traverse and diagonal over a flat area
     * that means that sometimes there's a cost improvement of like 10 ^ -16
     * it's not worth the time to update the costs, decrease-key the heap, potentially repropagate, etc
     * <p>
     * who cares about a hundredth of a tick? that's half a millisecond for crying out loud!
     */
    protected static final double MIN_IMPROVEMENT = 0.01;

    AbstractNodeCostSearch(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, CalculationContext context) {
        this.realStart = realStart;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.goal = goal;
        this.context = context;
        // Ostinato: the setting is read once. Off allocates the stock map and nothing else.
        this.piCheap = Baritone.settings().piCheapPathing.value;
        int mapSize = Baritone.settings().pathingMapDefaultSize.value;
        float load = Baritone.settings().pathingMapLoadFactor.value;
        if (piCheap) {
            this.piCells = new Long2ObjectOpenHashMap<>(mapSize, load);
            this.cells = null;
        } else {
            this.cells = new Long2ObjectOpenHashMap<>(mapSize, load);
            this.piCells = null;
        }
    }

    public void cancel() {
        cancelRequested = true;
    }

    /** Ostinato: true when the search ran out of time rather than out of nodes, so more time could still find a path. */
    public boolean timedOut() {
        return timedOut;
    }

    /** Ostinato: see {@link #exhausted}. */
    public boolean exhausted() {
        return exhausted;
    }

    /** Ostinato: the positions this search expanded (reached and looked out of), as {@link BetterBlockPos#longHash}. */
    public it.unimi.dsi.fastutil.longs.LongOpenHashSet expandedPositions() {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet out = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (PathNode n : nodes()) {
            if (n.expandedCost < baritone.api.pathing.movement.ActionCosts.COST_INF) {
                out.add(BetterBlockPos.longHash(n.x, n.y, n.z));
            }
        }
        return out;
    }

    @Override
    public synchronized PathCalculationResult calculate(long primaryTimeout, long failureTimeout) {
        if (isFinished) {
            throw new IllegalStateException("Path finder cannot be reused!");
        }
        cancelRequested = false;
        context.claimSearchCaches();
        try {
            long t0 = System.nanoTime(); // Ostinato: planning-time stats
            IPath path = allowReuse ? RouteReuse.cut(startX, startY, startZ, goal, context) : null;
            if (path != null) {
                logDebug("Reused a route of " + path.length() + " from " + startX + " " + startY + " " + startZ);
            } else {
                path = calculate0(primaryTimeout, failureTimeout).map(IPath::postProcess).orElse(null);
            }
            baritone.utils.PlanStats.astarNs.addAndGet(System.nanoTime() - t0);
            if (cancelRequested) {
                return new PathCalculationResult(PathCalculationResult.Type.CANCELLATION);
            }
            if (path == null) {
                return new PathCalculationResult(PathCalculationResult.Type.FAILURE);
            }
            int previousLength = path.length();
            path = path.cutoffAtLoadedChunks(context.bsi);
            if (path.length() < previousLength) {
                Helper.HELPER.logDebug("Cutting off path at edge of loaded chunks");
                Helper.HELPER.logDebug("Length decreased by " + (previousLength - path.length()));
            } else {
                Helper.HELPER.logDebug("Path ends within loaded chunks");
            }
            previousLength = path.length();
            path = path.staticCutoff(goal);
            if (path.length() < previousLength) {
                Helper.HELPER.logDebug("Static cutoff " + previousLength + " to " + path.length());
            }
            if (timedOut && !goal.isInGoal(path.getDest())) {
                // Ostinato: a search cut short knows nothing of what lies past a drop it cannot climb back: walk up to
                // the edge only, and let the next search (from the edge, with more time) decide whether to go down.
                // A 400ms first search on cubed level 6 dropped four blocks into a pit sealed off from the goal; the
                // search that proved it sealed finished after the bot had already fallen in.
                int drop = firstIrreversibleDrop(path);
                if (drop > 0) {
                    Helper.HELPER.logDebug("Stopping the segment at the drop at " + path.positions().get(drop) + " until a search sees past it");
                    path = new baritone.pathing.path.CutoffPath(path, drop);
                } else if (drop == 0 && holdAtDrops) {
                    Helper.HELPER.logDebug("Not dropping from " + path.getSrc() + " on a search that ran out of time");
                    return new PathCalculationResult(PathCalculationResult.Type.FAILURE);
                }
            }
            if (goal.isInGoal(path.getDest())) {
                if (allowReuse) {
                    RouteReuse.remember(path, context);
                }
                return new PathCalculationResult(PathCalculationResult.Type.SUCCESS_TO_GOAL, path);
            } else {
                return new PathCalculationResult(PathCalculationResult.Type.SUCCESS_SEGMENT, path);
            }
        } catch (Exception e) {
            Helper.HELPER.logDirect("Pathing exception: " + e);
            e.printStackTrace();
            return new PathCalculationResult(PathCalculationResult.Type.EXCEPTION);
        } finally {
            // this is run regardless of what exception may or may not be raised by calculate0
            isFinished = true;
            context.releaseSearchCaches();
        }
    }

    protected abstract Optional<IPath> calculate0(long primaryTimeout, long failureTimeout);

    /** Ostinato: the index of the first position the path drops {@link #IRREVERSIBLE_DROP} or more blocks from, or -1. */
    static int firstIrreversibleDrop(IPath path) {
        java.util.List<BetterBlockPos> pos = path.positions();
        for (int i = 0; i + 1 < pos.size(); i++) {
            if (pos.get(i + 1).y <= pos.get(i).y - IRREVERSIBLE_DROP) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Determines the distance squared from the specified node to the start
     * node. Intended for use in distance comparison, rather than anything that
     * considers the real distance value, hence the "sq".
     *
     * @param n A node
     * @return The distance, squared
     */
    protected double getDistFromStartSq(PathNode n) {
        int xDiff = n.x - startX;
        int yDiff = n.y - startY;
        int zDiff = n.z - startZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
    }

    /**
     * Attempts to search the block position hashCode long to {@link PathNode} map
     * for the node mapped to the specified pos. If no node is found,
     * a new node is created.
     *
     * @param x        The x position of the node
     * @param y        The y position of the node
     * @param z        The z position of the node
     * @param hashCode The hash code of the node, provided by {@link BetterBlockPos#longHash(int, int, int)}
     * @return The associated node
     * @see <a href="https://github.com/cabaletta/baritone/issues/107">Issue #107</a>
     */

    protected PathNode getNodeAtPosition(int x, int y, int z, long hashCode) {
        if (piCheap) {
            return nodeInSlice(x, y, z, true);
        }
        long cellKey = ((long) ((x >> CELL_BITS_X) & 0xFFFFFFF) << 36)
                | ((long) ((z >> CELL_BITS_Z) & 0xFFFFFFF) << 8)
                | ((y >> CELL_BITS_Y) & 0xFF);
        PathNode[] cell;
        if (cellKey == prevCellKey) {
            cell = prevCell;
        } else {
            cell = cells.get(cellKey);
            if (cell == null) {
                cell = new PathNode[CELL_SIZE];
                cells.put(cellKey, cell);
            }
            prevCell = cell;
            prevCellKey = cellKey;
        }
        int index = ((y & ((1 << CELL_BITS_Y) - 1)) << (CELL_BITS_X + CELL_BITS_Z))
                | ((z & ((1 << CELL_BITS_Z) - 1)) << CELL_BITS_X)
                | (x & ((1 << CELL_BITS_X) - 1));
        PathNode node = cell[index];
        if (node == null) {
            node = new PathNode(x, y, z, goal);
            cell[index] = node;
            numNodesCreated++;
        }
        return node;
    }

    /**
     * Ostinato: the node at this position if the search has already created it. Does not allocate.
     * Used by pi-cheap edge pruning; the stock search does not call it.
     */
    protected PathNode peekNodeAtPosition(int x, int y, int z) {
        if (piCheap) {
            return nodeInSlice(x, y, z, false);
        }
        long cellKey = ((long) ((x >> CELL_BITS_X) & 0xFFFFFFF) << 36)
                | ((long) ((z >> CELL_BITS_Z) & 0xFFFFFFF) << 8)
                | ((y >> CELL_BITS_Y) & 0xFF);
        PathNode[] cell = cellKey == prevCellKey ? prevCell : cells.get(cellKey);
        if (cell == null) {
            return null;
        }
        int index = ((y & ((1 << CELL_BITS_Y) - 1)) << (CELL_BITS_X + CELL_BITS_Z))
                | ((z & ((1 << CELL_BITS_Z) - 1)) << CELL_BITS_X)
                | (x & ((1 << CELL_BITS_X) - 1));
        return cell[index];
    }

    /** Ostinato: 16×16 slice for one y. {@code create} false returns null and allocates nothing. */
    private PathNode nodeInSlice(int x, int y, int z, boolean create) {
        long cellKey = ((long) ((x >> CELL_BITS_X) & 0xFFFFFFF) << 36)
                | ((long) ((z >> CELL_BITS_Z) & 0xFFFFFFF) << 8)
                | ((y >> CELL_BITS_Y) & 0xFF);
        int yIn = y & ((1 << CELL_BITS_Y) - 1);
        PathNode[][] slices;
        if (prevSlices != null && cellKey == prevPiKey) {
            slices = prevSlices;
        } else {
            slices = piCells.get(cellKey);
            if (slices == null) {
                if (!create) {
                    return null;
                }
                slices = new PathNode[1 << CELL_BITS_Y][];
                piCells.put(cellKey, slices);
            }
            prevSlices = slices;
            prevPiKey = cellKey;
            prevSlice = null;
            prevSliceY = Integer.MIN_VALUE;
        }
        PathNode[] slice;
        if (yIn == prevSliceY && prevSlice != null) {
            slice = prevSlice;
        } else {
            slice = slices[yIn];
            if (slice == null) {
                if (!create) {
                    return null;
                }
                slice = new PathNode[1 << (CELL_BITS_X + CELL_BITS_Z)];
                slices[yIn] = slice;
            }
            prevSlice = slice;
            prevSliceY = yIn;
        }
        int index = ((z & ((1 << CELL_BITS_Z) - 1)) << CELL_BITS_X) | (x & ((1 << CELL_BITS_X) - 1));
        PathNode node = slice[index];
        if (node == null && create) {
            node = new PathNode(x, y, z, goal);
            slice[index] = node;
            numNodesCreated++;
        }
        return node;
    }

    @Override
    public Optional<IPath> pathToMostRecentNodeConsidered() {
        return Optional.ofNullable(mostRecentConsidered).map(node -> new Path(realStart, startNode, node, 0, goal, context));
    }

    @Override
    public Optional<IPath> bestPathSoFar() {
        return bestSoFar(false, 0);
    }

    protected Optional<IPath> bestSoFar(boolean logInfo, int numNodes) {
        if (startNode == null) {
            return Optional.empty();
        }
        double bestDist = 0;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (bestSoFar[i] == null) {
                continue;
            }
            double dist = getDistFromStartSq(bestSoFar[i]);
            if (dist > bestDist) {
                bestDist = dist;
            }
            if (dist > MIN_DIST_PATH * MIN_DIST_PATH) { // square the comparison since distFromStartSq is squared
                if (logInfo) {
                    if (COEFFICIENTS[i] >= 3) {
                        System.out.println("Warning: cost coefficient is greater than three! Probably means that");
                        System.out.println("the path I found is pretty terrible (like sneak-bridging for dozens of blocks)");
                        System.out.println("But I'm going to do it anyway, because yolo");
                    }
                    System.out.println("Path goes for " + Math.sqrt(dist) + " blocks");
                    logDebug("A* cost coefficient " + COEFFICIENTS[i]);
                }
                return Optional.of(new Path(realStart, startNode, bestSoFar[i], numNodes, goal, context));
            }
        }
        // instead of returning bestSoFar[0], be less misleading
        // if it actually won't find any path, don't make them think it will by rendering a dark blue that will never actually happen
        if (logInfo) {
            logDebug("Even with a cost coefficient of " + COEFFICIENTS[COEFFICIENTS.length - 1] + ", I couldn't get more than " + Math.sqrt(bestDist) + " blocks");
            logDebug("No path found =(");
            logNotification("No path found =(", true);
        }
        return Optional.empty();
    }

    @Override
    public final boolean isFinished() {
        return isFinished;
    }

    @Override
    public final Goal getGoal() {
        return goal;
    }

    public BetterBlockPos getStart() {
        return new BetterBlockPos(startX, startY, startZ);
    }

    protected int mapSize() {
        return numNodesCreated;
    }

    /** Ostinato: walks y-slices without copying every node into a list. */
    private static final class PiNodeIter implements java.util.Iterator<PathNode> {
        private final java.util.Iterator<PathNode[][]> cells;
        private PathNode[][] slices;
        private PathNode[] slice = NO_SLICE;
        private int sy = 8;
        private int i;
        private PathNode next;

        PiNodeIter(Long2ObjectOpenHashMap<PathNode[][]> map) {
            this.cells = map.values().iterator();
            advance();
        }

        private void advance() {
            next = null;
            while (true) {
                while (i < slice.length) {
                    PathNode n = slice[i++];
                    if (n != null) {
                        next = n;
                        return;
                    }
                }
                if (slices != null && sy + 1 < slices.length) {
                    sy++;
                    PathNode[] s = slices[sy];
                    slice = s == null ? NO_SLICE : s;
                    i = 0;
                    continue;
                }
                if (!cells.hasNext()) {
                    return;
                }
                slices = cells.next();
                sy = -1;
                slice = NO_SLICE;
                i = 0;
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public PathNode next() {
            if (next == null) {
                throw new java.util.NoSuchElementException();
            }
            PathNode n = next;
            advance();
            return n;
        }
    }
}
