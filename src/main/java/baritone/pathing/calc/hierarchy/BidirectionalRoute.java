/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc.hierarchy;

import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.PriorityQueue;

/**
 * Ostinato: bidirectional Dijkstra between the start's and the goal's cell on one {@link LevelGraph} level (setting
 * {@code pathingHierarchyBidirectional}). The edges are explicit cell-to-cell costs, so the reverse direction is exact
 * (a cell A precedes B along direction d with cost {@code outgoing(A)[d]}, as in {@link CostToGoMap}), and with the
 * stopping rule top(forward) + top(backward) &gt;= best the route cost equals the one-sided search's. It settles two
 * balls of half the radius instead of one, so far fewer cells have their edges computed. It yields only the route,
 * not a cost-to-go map, so the planner uses it when {@code pathingHierarchyHeuristicFactor} is 0.
 */
public final class BidirectionalRoute {

    /** Cells from start to goal (inclusive, x,y,z triples), the route's cost in ticks, and how many cells were settled. */
    public record Result(int[] cells, double cost, int expanded) {

        public int length() {
            return cells.length / 3;
        }

        /** The route's cells, each widened by {@code radius} cells (the A* corridor). */
        public LongOpenHashSet corridor(int radius) {
            LongOpenHashSet out = new LongOpenHashSet();
            for (int i = 0; i < cells.length; i += 3) {
                int x = cells[i], y = cells[i + 1], z = cells[i + 2];
                for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) for (int dz = -radius; dz <= radius; dz++)
                    out.add(SectionGraph.key(x + dx, y + dy, z + dz));
            }
            return out;
        }
    }

    private BidirectionalRoute() {}

    /** Same box, filter and penalty arguments as {@link CostToGoMap#build}; null when no route was found in time. */
    public static Result search(LevelGraph g, CalculationContext ctx, int startX, int startY, int startZ, int goalX, int goalY, int goalZ, int margin, long deadlineMs,
                                CostToGoMap.CellFilter allowed, LongOpenHashSet penalised, double factor) {
        int sh = g.shift();
        int ssx = startX >> sh, ssy = startY >> sh, ssz = startZ >> sh;
        int gsx = goalX >> sh, gsy = goalY >> sh, gsz = goalZ >> sh;
        long sk = SectionGraph.key(ssx, ssy, ssz), gk = SectionGraph.key(gsx, gsy, gsz);
        if (sk == gk) return new Result(new int[]{ssx, ssy, ssz}, 0, 0);
        Box box = new Box(Math.min(ssx, gsx) - margin, Math.max(ssx, gsx) + margin, Math.min(ssz, gsz) - margin, Math.max(ssz, gsz) + margin,
                ctx.worldMinY >> sh, ctx.worldMaxY >> sh);
        Side f = new Side(sk, ssx, ssy, ssz, 1), b = new Side(gk, gsx, gsy, gsz, -1);
        double best = Double.POSITIVE_INFINITY;
        int mx = 0, my = 0, mz = 0;
        int expanded = 0;
        while (true) {
            f.skipStale();
            b.skipStale();
            if (f.pq.isEmpty() || b.pq.isEmpty()) break;
            if (f.pq.peek()[0] + b.pq.peek()[0] >= best) break;
            if ((++expanded & 15) == 0 && System.currentTimeMillis() > deadlineMs) break;
            boolean forward = f.pq.size() <= b.pq.size();
            Side s = forward ? f : b, o = forward ? b : f;
            double[] top = s.pq.poll();
            int cx = (int) top[1], cy = (int) top[2], cz = (int) top[3];
            long ck = SectionGraph.key(cx, cy, cz);
            s.done.add(ck);
            float[] out = forward ? g.outgoing(ctx, cx, cy, cz) : null;
            for (int d = 0; d < 26; d++) {
                int[] dir = SectionGraph.DIRS[d];
                int nx, ny, nz;
                float c;
                long nk;
                boolean pen;
                if (forward) { // cur -> next along d
                    nx = cx + dir[0];
                    ny = cy + dir[1];
                    nz = cz + dir[2];
                    if (!box.in(nx, ny, nz)) continue;
                    nk = SectionGraph.key(nx, ny, nz);
                    if (s.done.contains(nk) || (allowed != null && !allowed.test(nx, ny, nz))) continue;
                    c = out[d];
                    pen = penalised != null && penalised.contains(ck);
                } else { // prev -> cur along d
                    nx = cx - dir[0];
                    ny = cy - dir[1];
                    nz = cz - dir[2];
                    if (!box.in(nx, ny, nz)) continue;
                    nk = SectionGraph.key(nx, ny, nz);
                    if (s.done.contains(nk) || (allowed != null && !allowed.test(nx, ny, nz))) continue;
                    c = g.outgoing(ctx, nx, ny, nz)[d];
                    pen = penalised != null && penalised.contains(nk);
                }
                if (!Float.isFinite(c)) continue;
                double nd = top[0] + (pen ? c * factor : c);
                double old = s.dist.get(nk);
                if (Double.isNaN(old) || nd < old) {
                    s.dist.put(nk, nd);
                    s.parent.put(nk, d);
                    s.pq.add(new double[]{nd, nx, ny, nz});
                }
                double other = o.dist.get(nk);
                if (!Double.isNaN(other)) {
                    double total = s.dist.get(nk) + other;
                    if (total < best) {
                        best = total;
                        mx = nx;
                        my = ny;
                        mz = nz;
                    }
                }
            }
        }
        if (best == Double.POSITIVE_INFINITY) return null;
        IntArrayList left = f.trace(mx, my, mz), right = b.trace(mx, my, mz);
        int nl = left.size() / 3, nr = right.size() / 3;
        int[] cells = new int[(nl + nr - 1) * 3];
        for (int i = 0; i < nl; i++) left.getElements(3 * (nl - 1 - i), cells, 3 * i, 3); // start .. meet
        for (int i = 1; i < nr; i++) right.getElements(3 * i, cells, 3 * (nl + i - 1), 3); // after meet .. goal
        return new Result(cells, best, expanded);
    }

    private record Box(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
        boolean in(int x, int y, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
        }
    }

    private static final class Side {
        final Long2DoubleOpenHashMap dist = new Long2DoubleOpenHashMap();
        /** Direction index d of the edge that reached the cell; its parent is cell - sign * DIRS[d]. */
        final Long2IntOpenHashMap parent = new Long2IntOpenHashMap();
        final LongOpenHashSet done = new LongOpenHashSet();
        final PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        final long root;
        final int sign;

        Side(long root, int x, int y, int z, int sign) {
            this.root = root;
            this.sign = sign;
            dist.defaultReturnValue(Double.NaN);
            dist.put(root, 0);
            pq.add(new double[]{0, x, y, z});
        }

        void skipStale() {
            while (!pq.isEmpty()) {
                double[] t = pq.peek();
                long k = SectionGraph.key((int) t[1], (int) t[2], (int) t[3]);
                if (done.contains(k) || t[0] > dist.get(k)) pq.poll();
                else return;
            }
        }

        /** From cell (x,y,z) back to this side's root, (x,y,z) first, as triples. */
        IntArrayList trace(int x, int y, int z) {
            IntArrayList out = new IntArrayList();
            for (int i = 0; i < 1_000_000; i++) {
                out.add(x);
                out.add(y);
                out.add(z);
                long k = SectionGraph.key(x, y, z);
                if (k == root) return out;
                int[] dir = SectionGraph.DIRS[parent.get(k)];
                x -= sign * dir[0];
                y -= sign * dir[1];
                z -= sign * dir[2];
            }
            throw new IllegalStateException("route trace did not reach its root");
        }
    }
}
