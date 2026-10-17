/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc.hierarchy;

import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Ostinato: a coarse level of the pathing hierarchy (64 or 256 blocks per cell with the default 16^3 sections below
 * and factor 4 or 16). The edge A&rarr;B is the cost of the cheapest route in the finer graph from A's centre column to
 * B's centre column, using only fine cells inside A&cup;B (a Dijkstra that stops when B's centre settles). Edges are
 * computed lazily, cached, and dropped when a fine cell inside or next to the coarse cell is invalidated, so changes
 * propagate up level by level (the fine level calls {@link #invalidateFine}).
 */
public final class CoarseGraph implements LevelGraph {

    private final LevelGraph fine;
    private final int factorShift;
    private final int shift;
    private final Long2ObjectOpenHashMap<float[]> edges = new Long2ObjectOpenHashMap<>();
    private volatile long epoch;
    public volatile long computedCells;
    /** Optional next level up, told about our invalidations. */
    public LevelGraph parent;

    public CoarseGraph(LevelGraph fine, int factorShift) {
        this.fine = fine;
        this.factorShift = factorShift;
        this.shift = fine.shift() + factorShift;
    }

    @Override
    public int shift() {
        return shift;
    }

    @Override
    public float[] outgoing(CalculationContext ctx, int cx, int cy, int cz) {
        long k = SectionGraph.key(cx, cy, cz);
        long ep;
        synchronized (this) {
            float[] e = edges.get(k);
            if (e != null) return e;
            ep = epoch;
        }
        float[] e = compute(ctx, cx, cy, cz);
        synchronized (this) {
            if (ep == epoch) edges.put(k, e);
        }
        return e;
    }

    @Override
    public void invalidateFine(int fx, int fy, int fz) {
        int cx = fx >> factorShift, cy = fy >> factorShift, cz = fz >> factorShift;
        synchronized (this) {
            epoch++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                edges.remove(SectionGraph.key(cx + dx, cy + dy, cz + dz));
        }
        LevelGraph p = parent;
        if (p != null) p.invalidateFine(cx, cy, cz);
    }

    public void invalidateAll() {
        synchronized (this) {
            epoch++;
            edges.clear();
        }
        if (parent instanceof CoarseGraph cg) cg.invalidateAll();
    }

    private float[] compute(CalculationContext ctx, int cx, int cy, int cz) {
        computedCells++;
        int n = 1 << factorShift, half = n >> 1;
        int fineMinY = ctx.worldMinY >> fine.shift(), fineMaxY = ctx.worldMaxY >> fine.shift();
        float[] out = new float[26];
        Arrays.fill(out, Float.POSITIVE_INFINITY);
        int ax0 = cx << factorShift, ay0 = cy << factorShift, az0 = cz << factorShift;
        int sy = clamp(ay0 + half, fineMinY, fineMaxY);
        if (sy < ay0 || sy >= ay0 + n) return out; // coarse cell entirely outside the world height
        for (int d = 0; d < 26; d++) {
            int[] o = SectionGraph.DIRS[d];
            int bx0 = (cx + o[0]) << factorShift, by0 = (cy + o[1]) << factorShift, bz0 = (cz + o[2]) << factorShift;
            int ty = clamp(by0 + half, fineMinY, fineMaxY);
            if (ty < by0 || ty >= by0 + n) continue;
            double c = dijkstra(ctx, ax0, ay0, az0, bx0, by0, bz0, n, ax0 + half, sy, az0 + half, bx0 + half, ty, bz0 + half, fineMinY, fineMaxY);
            if (Double.isFinite(c)) out[d] = (float) c;
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static boolean in(int x, int y, int z, int x0, int y0, int z0, int n) {
        return x >= x0 && x < x0 + n && y >= y0 && y < y0 + n && z >= z0 && z < z0 + n;
    }

    /** Fine-level Dijkstra from (sx,sy,sz) to (tx,ty,tz) restricted to coarse cells A (at a0) and B (at b0). */
    private double dijkstra(CalculationContext ctx, int ax, int ay, int az, int bx, int by, int bz, int n,
                            int sx, int sy, int sz, int tx, int ty, int tz, int minY, int maxY) {
        Long2DoubleOpenHashMap dist = new Long2DoubleOpenHashMap();
        dist.defaultReturnValue(Double.NaN);
        LongOpenHashSet done = new LongOpenHashSet();
        PriorityQueue<long[]> pq = new PriorityQueue<>((p, q) -> Double.compare(Double.longBitsToDouble(p[0]), Double.longBitsToDouble(q[0])));
        // sources: every fine cell of A's centre column; target: any fine cell of B's centre column (so a centre
        // that happens to be solid rock or open sky does not distort the edge)
        for (int y = Math.max(minY, ay); y < ay + n && y <= maxY; y++) {
            dist.put(SectionGraph.key(sx, y, sz), 0);
            pq.add(new long[]{Double.doubleToRawLongBits(0), sx, y, sz});
        }
        int budget = 8 * n * n * n; // both cells, generously
        while (!pq.isEmpty() && budget-- > 0) {
            long[] top = pq.poll();
            int x = (int) top[1], y = (int) top[2], z = (int) top[3];
            long k = SectionGraph.key(x, y, z);
            if (!done.add(k)) continue;
            double g = Double.longBitsToDouble(top[0]);
            if (x == tx && z == tz && y >= by && y < by + n) return g;
            float[] e = fine.outgoing(ctx, x, y, z);
            for (int d = 0; d < 26; d++) {
                if (!Float.isFinite(e[d])) continue;
                int[] o = SectionGraph.DIRS[d];
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                if (ny < minY || ny > maxY) continue;
                if (!in(nx, ny, nz, ax, ay, az, n) && !in(nx, ny, nz, bx, by, bz, n)) continue;
                long nk = SectionGraph.key(nx, ny, nz);
                if (done.contains(nk)) continue;
                double nd = g + e[d];
                double old = dist.get(nk);
                if (Double.isNaN(old) || nd < old) {
                    dist.put(nk, nd);
                    pq.add(new long[]{Double.doubleToRawLongBits(nd), nx, ny, nz});
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
