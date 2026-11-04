/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics.mc;

import baritone.physics.BlockInfo;
import baritone.physics.PhysicsStats;
import baritone.physics.Terrain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: copies the world into immutable {@link Terrain} snapshots on the game thread. Section copies are kept and
 * shared by every snapshot until the world changes in them: a block update or chunk (re)load supersedes the copy, and
 * a copy older than {@link #MAX_AGE} ticks is checked again (cells changed with no packet, e.g. the client's own
 * predicted placements, are caught within that age). A re-check that finds the same cells keeps the old copy, so
 * results cached against it stay valid. Each capture copies at most {@link #COPY_BUDGET} sections, nearest the
 * subject first; the rest of the region is left out and reads as OUTSIDE_SNAPSHOT. Copying also stops once the
 * request's deadline has passed (Presto v4: the capture runs on the game thread before the predictor and was outside
 * the deadline's reach), so a cold region costs at most the deadline plus one section's copy. Game thread only.
 */
final class TerrainCapture {
    /** Retained section copies (each at most ~8.2 KB: about 8.4 MB in all). */
    static final int MAX_SECTIONS = 1024;
    static final int MAX_AGE = 20;
    static final int COPY_BUDGET = 64;
    /** Most sections one snapshot may span (the reach caps below keep regions well under it). */
    static final int MAX_REGION = 512;
    private static final double[] NO_BOXES = new double[0];

    private final PhysicsStats stats;
    private final BlockInfos blocks = new BlockInfos();
    private final LinkedHashMap<Long, Terrain.Section> cache = new LinkedHashMap<>(MAX_SECTIONS * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Terrain.Section> e) {
            boolean evict = size() > MAX_SECTIONS;
            if (evict) e.getValue().supersede(); // outstanding tickets cannot track this copy after eviction
            return evict;
        }
    };
    // reused copy scratch
    private final short[] cells = new short[4096];
    private final List<BlockInfo> palette = new ArrayList<>();
    private final IdentityHashMap<BlockState, Integer> stateIndex = new IdentityHashMap<>();
    private final long[] order = new long[MAX_REGION];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    TerrainCapture(PhysicsStats stats) {
        this.stats = stats;
    }

    /** The world was replaced or left: nothing copied from it is current. */
    void clear() {
        for (Terrain.Section s : cache.values()) s.supersede();
        cache.clear();
        blocks.clear();
    }

    void blockChanged(int x, int y, int z) {
        drop(Terrain.key(x >> 4, y >> 4, z >> 4));
    }

    void chunkChanged(ClientLevel level, int cx, int cz) {
        for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) drop(Terrain.key(cx, sy, cz));
    }

    private void drop(long key) {
        Terrain.Section s = cache.remove(key);
        if (s != null) {
            s.supersede();
            stats.sectionInvalidations.increment();
        }
    }

    int retained() {
        return cache.size();
    }

    /**
     * A snapshot of the block box {@code [x0, x1] x [y0, y1] x [z0, z1]} (clipped to the build limits) and the
     * collidable entities in it other than {@code excludeId}, copying nearest {@code (ox, oy, oz)} first, no new copy
     * after {@code deadline} ({@link System#nanoTime}, 0 none).
     */
    Terrain capture(ClientLevel level, long tick, int x0, int y0, int z0, int x1, int y1, int z1, double ox, double oy, double oz, int excludeId, long deadline) {
        int minSy = level.getMinSectionY(), maxSy = level.getMaxSectionY();
        int sx0 = x0 >> 4, sx1 = x1 >> 4, sz0 = z0 >> 4, sz1 = z1 >> 4;
        int sy0 = Math.max(minSy, y0 >> 4), sy1 = Math.min(maxSy, y1 >> 4);
        int osx = (int) Math.floor(ox) >> 4, osy = (int) Math.floor(oy) >> 4, osz = (int) Math.floor(oz) >> 4;
        int n = 0;
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sy = sy0; sy <= sy1 && n < MAX_REGION; sy++) {
                    long d = (long) (sx - osx) * (sx - osx) + (long) (sy - osy) * (sy - osy) + (long) (sz - osz) * (sz - osz);
                    order[n++] = d << 30 | (long) (sx - sx0) << 20 | (long) (sz - sz0) << 10 | (sy - sy0);
                }
            }
        }
        Arrays.sort(order, 0, n);
        Terrain.Builder t = Terrain.builder(level.getMinY(), level.getMinY() + level.getHeight(), tick, n);
        int budget = COPY_BUDGET;
        for (int i = 0; i < n; i++) {
            long o = order[i];
            int sx = sx0 + (int) (o >>> 20 & 0x3FF), sz = sz0 + (int) (o >>> 10 & 0x3FF), sy = sy0 + (int) (o & 0x3FF);
            long key = Terrain.key(sx, sy, sz);
            Terrain.Section s = cache.get(key);
            if (s != null && tick - s.tick <= MAX_AGE) {
                stats.sectionReuses.increment();
            } else {
                if (budget == 0) continue; // left out: OUTSIDE_SNAPSHOT if reached
                if (deadline != 0 && System.nanoTime() - deadline > 0) {
                    stats.captureCuts.increment();
                    budget = 0; // out of time: the rest is left out too
                    continue;
                }
                budget--;
                Terrain.Section fresh = copy(level, sx, sy, sz, tick, s);
                if (fresh == s) {
                    stats.sectionReuses.increment();
                } else {
                    stats.sectionCaptures.increment();
                    if (s != null) s.supersede();
                }
                s = fresh;
                cache.put(key, s);
            }
            t.put(sx, sy, sz, s);
        }
        return t.entityBoxes(entityBoxes(level, x0, y0, z0, x1, y1, z1, excludeId)).build();
    }

    private static double[] entityBoxes(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1, int excludeId) {
        List<Entity> es = level.getEntities((Entity) null, new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1),
                e -> e.getId() != excludeId && e.canBeCollidedWith(null));
        if (es.isEmpty()) return NO_BOXES;
        double[] out = new double[es.size() * 6];
        for (int k = 0; k < es.size(); k++) {
            AABB a = es.get(k).getBoundingBox();
            out[k * 6] = a.minX;
            out[k * 6 + 1] = a.minY;
            out[k * 6 + 2] = a.minZ;
            out[k * 6 + 3] = a.maxX;
            out[k * 6 + 4] = a.maxY;
            out[k * 6 + 5] = a.maxZ;
        }
        return out;
    }

    /** A copy of one section, or {@code old} itself (re-dated) when its cells are unchanged. */
    private Terrain.Section copy(ClientLevel level, int sx, int sy, int sz, long tick, Terrain.Section old) {
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) return Terrain.Section.uniform(BlockInfo.AIR_INFO, tick, Terrain.Section.UNLOADED);
        LevelChunkSection sec = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        if (sec == null || sec.hasOnlyAir()) {
            if (old != null && old.uniform() && old.flags == 0 && old.get(0, 0, 0) == BlockInfo.AIR_INFO) return old.recheck(tick);
            return Terrain.Section.uniform(BlockInfo.AIR_INFO, tick, 0);
        }
        palette.clear();
        stateIndex.clear();
        BlockState last = null;
        int lastIdx = 0;
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, z = i >> 4 & 15, y = i >> 8;
            BlockState st = sec.getBlockState(x, y, z);
            if (st != last || BlockInfos.perPosition(st)) {
                last = st;
                if (BlockInfos.perPosition(st)) {
                    lastIdx = index(blocks.get(st, level, pos.set(sx << 4 | x, sy << 4 | y, sz << 4 | z)));
                } else {
                    Integer known = stateIndex.get(st);
                    if (known == null) {
                        known = index(blocks.get(st, level, pos));
                        stateIndex.put(st, known);
                    }
                    lastIdx = known;
                }
            }
            cells[i] = (short) lastIdx;
        }
        boolean uniform = palette.size() == 1;
        if (old != null && old.flags == 0 && old.uniform() == uniform && old.paletteSize() == palette.size()) {
            boolean same = true;
            for (int i = 0; same && i < palette.size(); i++) same = palette.get(i).sameAs(old.paletteEntry(i));
            if (same && (uniform || old.sameCells(cells))) return old.recheck(tick);
        }
        BlockInfo[] p = palette.toArray(new BlockInfo[0]);
        return uniform ? Terrain.Section.uniform(p[0], tick, 0) : new Terrain.Section(p, cells.clone(), tick, 0);
    }

    private int index(BlockInfo info) {
        for (int i = 0; i < palette.size(); i++) if (palette.get(i).sameAs(info)) return i;
        palette.add(info);
        return palette.size() - 1;
    }
}
