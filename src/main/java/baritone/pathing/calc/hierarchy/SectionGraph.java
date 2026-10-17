/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc.hierarchy;

import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.MutableMoveResult;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ostinato: the abstract (HPA*-style) layer over 16x16x16 sections. A section's outgoing edge to each of its 26
 * neighbours is found with the <em>real</em> movement set: every standable (or swimmable) cell on the section's boundary
 * shell tries every {@link Moves} entry, and the cheapest cost per block of any move that lands in the neighbour sets
 * the edge's rate. Sections with no such move get coarse terrain-modification edges instead: tunnel (mining the
 * section's sampled blocks), bridge (placing across air) or pillar (placing upward), priced from the same
 * CalculationContext, so "dig straight through" and "walk around" compete in the abstract search.
 * <p>
 * Edges are computed lazily and cached; {@link #prefetch} fills them on a worker thread as chunks load, and
 * {@link #invalidateBlock}/{@link #invalidateChunk} drop the affected sections (and their neighbours, whose
 * boundary moves read across the face) when the world changes. The cache is keyed by a signature of the context's
 * movement settings and cleared when those change. Intra-section connectivity is assumed (a known simplification:
 * a section split by a wall is treated as one node).
 */
public final class SectionGraph implements LevelGraph {

    /** Ostinato: the coarse level above (64-256 blocks), told about invalidations; null = sections only. */
    public volatile LevelGraph parent;

    @Override
    public int shift() {
        return 4;
    }

    /** Ostinato: the coarse level with cells of {@code 16 << factorShift} blocks, created on first use. */
    public synchronized CoarseGraph coarse(int factorShift) {
        if (parent instanceof CoarseGraph cg && cg.shift() == 4 + factorShift) return cg;
        CoarseGraph cg = new CoarseGraph(this, factorShift);
        parent = cg;
        return cg;
    }

    private void notifyParent(int sx, int sy, int sz) {
        LevelGraph p = parent;
        if (p != null) p.invalidateFine(sx, sy, sz);
    }

    public static final int SIZE = 16;
    /** Neighbour offsets, index = direction. */
    public static final int[][] DIRS = new int[26][];
    /** Centre-to-centre distance in blocks per direction. */
    public static final double[] DIST = new double[26];

    static {
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            DIRS[i] = new int[]{dx, dy, dz};
            DIST[i] = SIZE * Math.sqrt(dx * dx + dy * dy + dz * dz);
            i++;
        }
    }

    public static int dirIndex(int dx, int dy, int dz) {
        int i = (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
        return i > 13 ? i - 1 : i;
    }

    /** The single in-game graph (planning threads share it; access is synchronised per call). */
    public static final SectionGraph INSTANCE = new SectionGraph();

    private final Long2ObjectOpenHashMap<float[]> edges = new Long2ObjectOpenHashMap<>();
    private long signature = Long.MIN_VALUE;
    /** Bumped on every invalidation, so a prefetch computed against an older world is discarded. */
    private volatile long epoch;
    public volatile long computedSections;

    public static long key(int sx, int sy, int sz) {
        return BetterBlockPos.longHash(sx, sy, sz);
    }

    private static long signature(CalculationContext c) {
        long s = 17;
        s = s * 31 + (c.allowBreak ? 1 : 0);
        s = s * 31 + (c.hasThrowaway ? 1 : 0);
        s = s * 31 + (c.allowParkour ? 1 : 0);
        s = s * 31 + (c.canSprint ? 1 : 0);
        s = s * 31 + c.maxFallHeightNoWater;
        s = s * 31 + Double.hashCode(c.breakBlockAdditionalCost);
        s = s * 31 + Double.hashCode(c.jumpPenalty);
        s = s * 31 + System.identityHashCode(c.bsi.worldBorder) * 0L; // border changes come through invalidateAll
        return s;
    }

    /** Outgoing edge costs (ticks) to the 26 neighbours of section (sx,sy,sz); +inf where there is none. */
    @Override
    public float[] outgoing(CalculationContext ctx, int sx, int sy, int sz) {
        long k = key(sx, sy, sz);
        long sig = signature(ctx);
        long ep;
        synchronized (this) {
            if (sig != signature) {
                boolean had = signature != Long.MIN_VALUE;
                edges.clear();
                signature = sig;
                if (had && parent instanceof CoarseGraph cg) cg.invalidateAll(); // Ostinato: coarse edges were built from these
            }
            float[] e = edges.get(k);
            if (e != null) return e;
            ep = epoch;
        }
        float[] e = compute(ctx, sx, sy, sz);
        synchronized (this) {
            if (ep == epoch && sig == signature) edges.put(k, e);
        }
        return e;
    }

    public synchronized boolean isComputed(int sx, int sy, int sz) {
        return edges.containsKey(key(sx, sy, sz));
    }

    public void invalidateBlock(int x, int y, int z) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        synchronized (this) {
            epoch++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                edges.remove(key(sx + dx, sy + dy, sz + dz));
        }
        notifyParent(sx, sy, sz);
    }

    public void invalidateChunk(int cx, int cz, int minSy, int maxSy) {
        synchronized (this) {
            epoch++;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int sy = minSy - 1; sy <= maxSy + 1; sy++)
                edges.remove(key(cx + dx, sy, cz + dz));
        }
        for (int sy = minSy; sy <= maxSy; sy++) notifyParent(cx, sy, cz);
    }

    public void invalidateAll() {
        synchronized (this) {
            epoch++;
            edges.clear();
        }
        LevelGraph p = parent;
        if (p instanceof CoarseGraph cg) cg.invalidateAll();
    }

    /** Fill the edges of a chunk column's sections on the calling (worker) thread. */
    public void prefetch(CalculationContext ctx, int cx, int cz) {
        int minSy = ctx.worldMinY >> 4, maxSy = ctx.worldMaxY >> 4;
        for (int sy = minSy; sy <= maxSy; sy++) outgoing(ctx, cx, sy, cz);
    }

    private static boolean onShell(int lx, int ly, int lz) {
        return lx == 0 || lx == SIZE - 1 || ly == 0 || ly == SIZE - 1 || lz == 0 || lz == SIZE - 1;
    }

    private float[] compute(CalculationContext ctx, int sx, int sy, int sz) {
        computedSections++;
        float[] rate = new float[26];
        java.util.Arrays.fill(rate, Float.POSITIVE_INFINITY);
        MutableMoveResult res = new MutableMoveResult();
        Moves[] moves = Moves.values();
        int bx = sx << 4, by = sy << 4, bz = sz << 4;
        int minY = ctx.worldMinY, maxY = ctx.worldMaxY;
        boolean anyStandable = false;
        boolean allAir = true;
        double mineSum = 0;
        int mineN = 0;
        for (int lx = 0; lx < SIZE; lx++) for (int lz = 0; lz < SIZE; lz++) for (int ly = 0; ly < SIZE; ly++) {
            int x = bx + lx, y = by + ly, z = bz + lz;
            if (y <= minY || y >= maxY) continue;
            BlockState feet = ctx.get(x, y, z);
            if (allAir && !feet.isAir()) allAir = false;

            if (!onShell(lx, ly, lz)) continue;
            boolean wet = MovementHelper.isWater(feet);
            if (!wet && !(MovementHelper.canWalkThrough(ctx, x, y, z, feet) && MovementHelper.canWalkThrough(ctx, x, y + 1, z) && MovementHelper.canWalkOn(ctx, x, y - 1, z))) continue;
            anyStandable = true;
            for (Moves m : moves) {
                // Ostinato: a fixed-offset move that lands inside this section cannot produce an edge; skip it
                // before evaluating (same result, most of the cold-build cost).
                if (!m.dynamicXZ && !m.dynamicY) {
                    int tx = lx + m.xOffset, ty = ly + m.yOffset, tz = lz + m.zOffset;
                    if ((tx | ty | tz) >= 0 && tx < SIZE && ty < SIZE && tz < SIZE) continue;
                }
                int ny = y + m.yOffset;
                if (ny > maxY || ny < minY) continue;
                if (!m.dynamicXZ && !ctx.worldBorder.entirelyContains(x + m.xOffset, z + m.zOffset)) continue;
                res.reset();
                m.apply(ctx, x, y, z, res);
                double c = res.cost;
                if (c >= ActionCosts.COST_INF) continue;
                int dsx = (res.x >> 4) - sx, dsy = (res.y >> 4) - sy, dsz = (res.z >> 4) - sz;
                if ((dsx | dsy | dsz) == 0 || Math.abs(dsx) > 1 || Math.abs(dsy) > 1 || Math.abs(dsz) > 1) continue;
                double len = Math.max(1, Math.sqrt(sq(res.x - x) + sq(res.y - y) + sq(res.z - z)));
                int d = dirIndex(dsx, dsy, dsz);
                double r = c / len;
                if (r < rate[d]) rate[d] = (float) r;
            }
        }
        float[] out = new float[26];
        boolean needAlt = !anyStandable;
        for (int d = 0; d < 26 && !needAlt; d++) needAlt = !Float.isFinite(rate[d]);
        if (needAlt && ctx.allowBreak && !allAir) {
            // Ostinato: the tunnel price is only needed when some direction has no real move; sampling it lazily
            // (same sample cells, same order) gives identical edges without the mining-time lookups in open terrain.
            for (int lx = 0; lx < SIZE; lx++) for (int lz = 0; lz < SIZE; lz++) for (int ly = 0; ly < SIZE; ly++) {
                if (((lx | ly | lz) & 7) != 3) continue;
                int x = bx + lx, y = by + ly, z = bz + lz;
                if (y <= minY || y >= maxY) continue;
                BlockState feet = ctx.get(x, y, z);
                if (MovementHelper.canWalkThrough(ctx, x, y, z, feet)) continue;
                mineSum += MovementHelper.getMiningDurationTicks(ctx, x, y, z, feet, false);
                mineN++;
            }
        }
        double tunnel = ctx.allowBreak && mineN > 0 ? (mineSum / mineN) * 2 + ActionCosts.WALK_ONE_BLOCK_COST : Double.POSITIVE_INFINITY; // two blocks per step
        double bridge = ctx.hasThrowaway ? ctx.costOfPlacingAt(bx, by, bz, ctx.get(bx, by, bz)) + ActionCosts.WALK_ONE_BLOCK_COST * 2 : Double.POSITIVE_INFINITY;
        for (int d = 0; d < 26; d++) {
            double r = rate[d];
            if (!Double.isFinite(r) || !anyStandable) {
                // coarse terrain modification: dig, bridge or pillar, whichever this section allows
                int dy = DIRS[d][1];
                double alt = Double.POSITIVE_INFINITY;
                if (!allAir) alt = Math.min(alt, tunnel);
                if (allAir && dy <= 0) alt = Math.min(alt, bridge);
                if (dy > 0 && ctx.hasThrowaway) alt = Math.min(alt, ActionCosts.JUMP_ONE_BLOCK_COST + bridge);
                r = Math.min(r, alt);
            }
            out[d] = Double.isFinite(r) ? (float) (r * DIST[d]) : Float.POSITIVE_INFINITY;
        }
        return out;
    }

    private static int sq(int a) {
        return a * a;
    }
}
