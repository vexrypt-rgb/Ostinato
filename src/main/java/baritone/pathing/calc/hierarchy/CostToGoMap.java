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

    private final Long2DoubleOpenHashMap dist = new Long2DoubleOpenHashMap();
    public final int goalSx, goalSy, goalSz;
    public int expanded;

    private CostToGoMap(int gx, int gy, int gz) {
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
        int ssx = startX >> 4, ssy = startY >> 4, ssz = startZ >> 4;
        CostToGoMap m = new CostToGoMap(goalX >> 4, goalY >> 4, goalZ >> 4);
        int minSx = Math.min(ssx, m.goalSx) - margin, maxSx = Math.max(ssx, m.goalSx) + margin;
        int minSz = Math.min(ssz, m.goalSz) - margin, maxSz = Math.max(ssz, m.goalSz) + margin;
        int minSy = ctx.worldMinY >> 4, maxSy = ctx.worldMaxY >> 4;
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
                float c = g.outgoing(ctx, ax, ay, az)[d];
                if (!Float.isFinite(c)) continue;
                double nd = top[0] + c;
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
        return costToGoSection(x >> 4, y >> 4, z >> 4);
    }

    /**
     * Section keys on the descent from the start's section to the goal's, each widened by {@code radius} sections;
     * null if the start was not reached.
     */
    public it.unimi.dsi.fastutil.longs.LongOpenHashSet corridor(SectionGraph g, CalculationContext ctx, int x, int y, int z, int radius) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
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
