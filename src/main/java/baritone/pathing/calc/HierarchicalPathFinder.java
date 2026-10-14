/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.hierarchy.CoarseGraph;
import baritone.pathing.calc.hierarchy.CostToGoMap;
import baritone.pathing.calc.hierarchy.SectionGraph;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * Ostinato: HPA*-style planner (setting {@code pathingHierarchical}, off by default). It builds a section-level
 * {@link CostToGoMap} with one backward Dijkstra from the goal over the {@link SectionGraph}, traces the abstract route
 * from the start, and runs the stock {@link AStarPathFinder} restricted to that corridor (route sections widened by
 * {@code pathingHierarchyCorridorRadius}). With {@code pathingHierarchyHeuristicFactor} &gt; 0 the block-level
 * heuristic becomes max(h, factor * costToGo(section)). If the goal has no single position, the abstract search does
 * not reach the start, or the corridor search does not reach the goal, it falls back to plain A* with the time left.
 */
public final class HierarchicalPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final CalculationContext ctx;
    private final SectionGraph graph;
    private final double weight;
    private AStarPathFinder inner;
    /** What the last calculate0 did: "corridor", "fallback:&lt;reason&gt;". For logs and the benchmark. */
    public String outcome = "";
    public CostToGoMap costToGo;
    /** Ostinato: the coarse-level map when pathingHierarchyLevels &gt;= 2. */
    public CostToGoMap coarseMap;

    public HierarchicalPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context, SectionGraph graph, double weight) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.ctx = context;
        this.graph = graph;
        this.weight = weight;
    }

    private static final class GuidedGoal implements Goal {
        final Goal goal;
        final CostToGoMap map;
        final double factor;

        GuidedGoal(Goal goal, CostToGoMap map, double factor) {
            this.goal = goal;
            this.map = map;
            this.factor = factor;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return goal.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double h = goal.heuristic(x, y, z);
            double c = map.costToGo(x, y, z);
            return Double.isNaN(c) ? h : Math.max(h, factor * c);
        }
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        long t0 = System.currentTimeMillis();
        if (!(goal instanceof IGoalRenderPos)) {
            return fallback("goal has no position", primaryTimeout, failureTimeout, t0);
        }
        BlockPos gp = ((IGoalRenderPos) goal).getGoalPos();
        // Ostinato: short hops gain nothing from the abstract layers (their build cost dominates); plan them directly.
        double dx = gp.getX() - startX, dz = gp.getZ() - startZ;
        if (Math.sqrt(dx * dx + dz * dz) < Baritone.settings().pathingHierarchyMinDistance.value) {
            return fallback("short hop", primaryTimeout, failureTimeout, t0);
        }
        int margin = Baritone.settings().pathingHierarchyMargin.value;
        CostToGoMap.CellFilter allowed = null;
        int levels = Baritone.settings().pathingHierarchyLevels.value;
        if (levels >= 2) {
            // Ostinato: progressive refinement. Plan on the coarse level first, then let the section level search
            // only the sections whose coarse cell lies in the coarse corridor.
            int fs = Baritone.settings().pathingHierarchyCoarseFactorShift.value;
            CoarseGraph coarse = graph.coarse(fs);
            coarseMap = CostToGoMap.build(coarse, ctx, startX, startY, startZ, gp.getX(), gp.getY(), gp.getZ(), 1, t0 + failureTimeout / 4, null);
            if (cancelRequested) return Optional.empty();
            LongOpenHashSet cc = coarseMap.corridor(coarse, ctx, startX, startY, startZ, 1);
            if (cc != null) {
                allowed = (x, y, z) -> cc.contains(SectionGraph.key(x >> fs, y >> fs, z >> fs));
                margin = Math.max(margin, (1 << fs) * 2); // the coarse corridor bounds the search now
            }
        }
        costToGo = CostToGoMap.build(graph, ctx, startX, startY, startZ, gp.getX(), gp.getY(), gp.getZ(), margin, t0 + failureTimeout / 2, allowed);
        if (cancelRequested) return Optional.empty();
        LongOpenHashSet corridor = costToGo.corridor(graph, ctx, startX, startY, startZ, Baritone.settings().pathingHierarchyCorridorRadius.value);
        if (corridor == null) {
            return fallback("no abstract route", primaryTimeout, failureTimeout, t0);
        }
        double factor = Baritone.settings().pathingHierarchyHeuristicFactor.value;
        Goal guided = factor > 0 ? new GuidedGoal(goal, costToGo, factor) : goal;
        inner = new AStarPathFinder(realStart, startX, startY, startZ, guided, favoring, ctx, weight);
        inner.nodeFilter = (x, y, z) -> corridor.contains(SectionGraph.key(x >> 4, y >> 4, z >> 4));
        long used = System.currentTimeMillis() - t0;
        Optional<IPath> p = inner.calculate0(Math.max(1, primaryTimeout - used), Math.max(1, failureTimeout - used));
        if (cancelRequested) return Optional.empty();
        if (p.isPresent() && goal.isInGoal(p.get().getDest())) {
            outcome = "corridor";
            return p;
        }
        if (Baritone.settings().pathingHierarchyCandidates.value >= 2 && System.currentTimeMillis() - t0 < primaryTimeout / 2) {
            // Ostinato: second candidate. Re-run the abstract search with the first route's sections penalised, so the
            // descent picks a different way (around the wall the first corridor hit), and try that corridor.
            LongOpenHashSet route = costToGo.corridor(graph, ctx, startX, startY, startZ, 0);
            CostToGoMap alt = CostToGoMap.build(graph, ctx, startX, startY, startZ, gp.getX(), gp.getY(), gp.getZ(), margin, t0 + failureTimeout / 2, allowed,
                    route, Baritone.settings().pathingHierarchyCandidatePenalty.value);
            LongOpenHashSet c2 = alt.corridor(graph, ctx, startX, startY, startZ, Baritone.settings().pathingHierarchyCorridorRadius.value);
            if (c2 != null && !c2.equals(corridor)) {
                inner = new AStarPathFinder(realStart, startX, startY, startZ, factor > 0 ? new GuidedGoal(goal, alt, factor) : goal, favoring, ctx, weight);
                inner.nodeFilter = (x, y, z) -> c2.contains(SectionGraph.key(x >> 4, y >> 4, z >> 4));
                used = System.currentTimeMillis() - t0;
                p = inner.calculate0(Math.max(1, primaryTimeout - used), Math.max(1, failureTimeout - used));
                if (cancelRequested) return Optional.empty();
                if (p.isPresent() && goal.isInGoal(p.get().getDest())) {
                    outcome = "corridor2";
                    return p;
                }
            }
        }
        return fallback("corridor search missed the goal", primaryTimeout, failureTimeout, t0);
    }

    private Optional<IPath> fallback(String why, long primaryTimeout, long failureTimeout, long t0) {
        outcome = "fallback:" + why;
        logDebug("Hierarchical planner: " + why + ", plain A*");
        inner = new AStarPathFinder(realStart, startX, startY, startZ, goal, favoring, ctx, weight);
        long used = System.currentTimeMillis() - t0;
        return inner.calculate0(Math.max(1, primaryTimeout - used), Math.max(1, failureTimeout - used));
    }

    @Override
    public void cancel() {
        super.cancel();
        AStarPathFinder i = inner;
        if (i != null) i.cancel();
    }

    @Override
    public Optional<IPath> pathToMostRecentNodeConsidered() {
        AStarPathFinder i = inner;
        return i == null ? Optional.empty() : i.pathToMostRecentNodeConsidered();
    }

    @Override
    public Optional<IPath> bestPathSoFar() {
        AStarPathFinder i = inner;
        return i == null ? Optional.empty() : i.bestPathSoFar();
    }
}
