/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc.hierarchy;

import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import java.util.PriorityQueue;

/**
 * Ostinato: remaining cost to the goal per 16^3 section, from one backward Dijkstra over the {@link SectionGraph}
 * (restricted to a box around start and goal). Use it as an A* heuristic ({@link #costToGo}), as a planner's
 * terminal cost, or to trace a corridor ({@link #corridor}). Values are ticks, NaN where the search did not reach.
 */
public final class CostToGoMap {

    /** Ostinato: which cells (in this map's level coordinates) a search may use. */
    @FunctionalInterface
    public interface CellFilter {
        boolean test(int cx, int cy, int cz);
    }

    private final Long2DoubleOpenHashMap dist = new Long2DoubleOpenHashMap();
    public final int goalSx, goalSy, goalSz;
    public int expanded;

    /** Ostinato: log2 of the cell size in blocks (4 = sections). */
    public final int shift;

    private CostToGoMap(int gx, int gy, int gz, int shift) {
        this.shift = shift;
        dist.defaultReturnValue(Double.NaN);
        goalSx = gx;
        goalSy = gy;
        goalSz = gz;
    }

    /**
     * Backward Dijkstra from the goal's section. The box spans start..goal sections plus {@code margin} sections
     * horizontally; it stops early once the start section is settled and {@code extra} more have been settled.
     */
    public static CostToGoMap build(SectionGraph g, CalculationContext ctx, int startX, int startY, int startZ, int goalX, int goalY, int goalZ, int margin, long deadlineMs) {
        return build(g, ctx, startX, startY, startZ, goalX, goalY, goalZ, margin, deadlineMs, (CellFilter) null);
    }

    /**
     * Ostinato: the same on any {@link LevelGraph} level (cell = 1 &lt;&lt; shift blocks), optionally limited to cells
     * {@code allowed} accepts (used to refine progressively: a finer level only searches inside the coarser corridor).
     */
    public static CostToGoMap build(LevelGraph g, CalculationContext ctx, int startX, int startY, int startZ, int goalX, int goalY, int goalZ, int margin, long deadlineMs,
                                    CellFilter allowed) {
        return build(g, ctx, startX, startY, startZ, goalX, goalY, goalZ, margin, deadlineMs, allowed, null, 1);
    }

    /**
     * Ostinato: second-candidate (k-diverse) variant. Edges leaving a cell in {@code penalised} cost {@code factor}
     * times more, so the descent from the start avoids the first candidate's route where a comparable one exists.
     */
    public static CostToGoMap build(LevelGraph g, CalculationContext ctx, int startX, int startY, int startZ, int goalX, int goalY, int goalZ, int margin, long deadlineMs,
                                    CellFilter allowed, it.unimi.dsi.fastutil.longs.LongOpenHashSet penalised, double factor) {
        int sh = g.shift();
        int ssx = startX >> sh, ssy = startY >> sh, ssz = startZ >> sh;
        CostToGoMap m = new CostToGoMap(goalX >> sh, goalY >> sh, goalZ >> sh, sh);
        int minSx = Math.min(ssx, m.goalSx) - margin, maxSx = Math.max(ssx, m.goalSx) + margin;
        int minSz = Math.min(ssz, m.goalSz) - margin, maxSz = Math.max(ssz, m.goalSz) + margin;
        int minSy = ctx.worldMinY >> sh, maxSy = ctx.worldMaxY >> sh;
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        m.dist.put(SectionGraph.key(m.goalSx, m.goalSy, m.goalSz), 0);
        pq.add(new double[]{0, m.goalSx, m.goalSy, m.goalSz});
        it.unimi.dsi.fastutil.longs.LongOpenHashSet done = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        long startKey = SectionGraph.key(ssx, ssy, ssz);
        double stopAt = Double.POSITIVE_INFINITY;
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int bx = (int) top[1], by = (int) top[2], bz = (int) top[3];
            long bk = SectionGraph.key(bx, by, bz);
            if (!done.add(bk)) continue;
            if (top[0] > stopAt) break;
            m.expanded++;
            if (bk == startKey) stopAt = top[0] * 1.5 + 200; // settle a band beyond the start, for the corridor's sides
            if ((m.expanded & 15) == 0 && System.currentTimeMillis() > deadlineMs) break;
            for (int d = 0; d < 26; d++) {
                int[] o = SectionGraph.DIRS[d];
                int ax = bx - o[0], ay = by - o[1], az = bz - o[2]; // A -> B along direction d
                if (ax < minSx || ax > maxSx || az < minSz || az > maxSz || ay < minSy || ay > maxSy) continue;
                long ak = SectionGraph.key(ax, ay, az);
                if (done.contains(ak)) continue;
                if (allowed != null && !allowed.test(ax, ay, az)) continue;
                float c = g.outgoing(ctx, ax, ay, az)[d];
                if (!Float.isFinite(c)) continue;
                double nd = top[0] + (penalised != null && penalised.contains(ak) ? c * factor : c);
                double old = m.dist.get(ak);
                if (Double.isNaN(old) || nd < old) {
                    m.dist.put(ak, nd);
                    pq.add(new double[]{nd, ax, ay, az});
                }
            }
        }
        return m;
    }

    public double costToGoSection(int sx, int sy, int sz) {
        return dist.get(SectionGraph.key(sx, sy, sz));
    }

    /** Remaining cost (ticks) from the section holding block (x,y,z); NaN if unknown. */
    public double costToGo(int x, int y, int z) {
        return costToGoSection(x >> shift, y >> shift, z >> shift);
    }

    /**
     * Section keys on the descent from the start's section to the goal's, each widened by {@code radius} sections;
     * null if the start was not reached.
     */
    public it.unimi.dsi.fastutil.longs.LongOpenHashSet corridor(LevelGraph g, CalculationContext ctx, int x, int y, int z, int radius) {
        int sx = x >> shift, sy = y >> shift, sz = z >> shift;
        if (Double.isNaN(costToGoSection(sx, sy, sz))) return null;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet out = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int steps = 0; steps < 100_000; steps++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) for (int dz = -radius; dz <= radius; dz++)
                out.add(SectionGraph.key(sx + dx, sy + dy, sz + dz));
            if (sx == goalSx && sy == goalSy && sz == goalSz) return out;
            float[] e = g.outgoing(ctx, sx, sy, sz);
            double best = Double.POSITIVE_INFINITY;
            int bd = -1;
            for (int d = 0; d < 26; d++) {
                int[] o = SectionGraph.DIRS[d];
                double h = costToGoSection(sx + o[0], sy + o[1], sz + o[2]);
                if (Double.isNaN(h) || !Float.isFinite(e[d])) continue;
                if (e[d] + h < best) {
                    best = e[d] + h;
                    bd = d;
                }
            }
            if (bd < 0) return null;
            sx += SectionGraph.DIRS[bd][0];
            sy += SectionGraph.DIRS[bd][1];
            sz += SectionGraph.DIRS[bd][2];
        }
        return null;
    }
}
