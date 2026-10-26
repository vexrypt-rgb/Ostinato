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

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.cache.CachedRegion;
import baritone.cache.WorldData;
import baritone.utils.accessor.IClientChunkProvider;
import baritone.pathing.SeedPlan;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Wraps get for chuck caching capability
 *
 * @author leijurv
 */
public class BlockStateInterface {

    private final ClientChunkCache provider;
    private final WorldData worldData;
    protected final Level world;
    public final BlockPos.MutableBlockPos isPassableBlockPos;
    public final BlockGetter access;
    public final BetterWorldBorder worldBorder;

    private LevelChunk prev = null;
    private CachedRegion prevCached = null;

    private final boolean useTheRealWorld;

    // Ostinato: trainingLegit off leaves these false and get0 stays on the cache and seed path below.
    private final boolean legitClip;
    private final int legitChunkX;
    private final int legitChunkZ;
    private final int legitChunks;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public BlockStateInterface(IPlayerContext ctx) {
        this(ctx, false);
    }

    public BlockStateInterface(IPlayerContext ctx, boolean copyLoadedChunks) {
        this.world = ctx.world();
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
        this.worldData = (WorldData) ctx.worldData();
        if (copyLoadedChunks) {
            this.provider = ((IClientChunkProvider) world.getChunkSource()).createThreadSafeCopy();
        } else {
            this.provider = (ClientChunkCache) world.getChunkSource();
        }
        this.useTheRealWorld = !Baritone.settings().pathThroughCachedOnly.value;
        boolean clip = false;
        int cx = 0;
        int cz = 0;
        int chunks = 0;
        // Ostinato: legit track sees loaded chunks inside render distance. Off, legitClip stays false.
        if (Baritone.settings().trainingLegit.value && ctx.player() != null && ctx.minecraft() != null) {
            clip = true;
            cx = LegitHorizon.chunkOf(ctx.player().getX());
            cz = LegitHorizon.chunkOf(ctx.player().getZ());
            chunks = ctx.minecraft().options.renderDistance().get();
        }
        this.legitClip = clip;
        this.legitChunkX = cx;
        this.legitChunkZ = cz;
        this.legitChunks = chunks;
        if (!ctx.minecraft().isSameThread()) {
            throw new IllegalStateException("BlockStateInterface must be constructed on the main thread");
        }
        this.isPassableBlockPos = new BlockPos.MutableBlockPos();
        this.access = new BlockStateInterfaceAccessWrapper(this);
    }

    // Ostinato: headless constructor for snapshot/synthetic worlds (pathfinding benchmark and tests). A subclass overrides
    // get0, isLoaded and worldContainsLoadedChunk; there is no client world or chunk provider. New, replaces nothing.
    protected BlockStateInterface(int minY, int height, BetterWorldBorder worldBorder) {
        this.world = null;
        this.provider = null;
        this.worldData = null;
        this.worldBorder = worldBorder;
        this.useTheRealWorld = false;
        this.legitClip = false;
        this.legitChunkX = 0;
        this.legitChunkZ = 0;
        this.legitChunks = 0;
        this.headlessMinY = minY;
        this.headlessHeight = height;
        this.isPassableBlockPos = new BlockPos.MutableBlockPos();
        this.access = new BlockStateInterfaceAccessWrapper(this);
    }

    // Ostinato: world height bounds that also work headless; replaces direct bsi.world.getMinY()/getHeight() reads in BlockStateInterfaceAccessWrapper.
    private int headlessMinY;
    private int headlessHeight;

    public int minY() {
        return world != null ? world.getMinY() : headlessMinY;
    }

    public int height() {
        return world != null ? world.getHeight() : headlessHeight;
    }

    public boolean worldContainsLoadedChunk(int blockX, int blockZ) {
        // Ostinato: legit track does not treat a cached or far chunk as loaded.
        if (legitClip) {
            return legitLoaded(blockX, blockZ);
        }
        return provider.hasChunk(blockX >> 4, blockZ >> 4);
    }

    public static Block getBlock(IPlayerContext ctx, BlockPos pos) { // won't be called from the pathing thread because the pathing thread doesn't make a single blockpos pog
        return get(ctx, pos).getBlock();
    }

    public static BlockState get(IPlayerContext ctx, BlockPos pos) {
        return new BlockStateInterface(ctx).get0(pos.getX(), pos.getY(), pos.getZ()); // immense iq
        // can't just do world().get because that doesn't work for out of bounds
        // and toBreak and stuff fails when the movement is instantiated out of load range but it's not able to BlockStateInterface.get what it's going to walk on
    }

    public BlockState get0(BlockPos pos) {
        return get0(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState get0(int x, int y, int z) { // Mickey resigned
        // Ostinato: one branch. Off, the body below is the full-information lookup.
        if (legitClip) {
            return legitBlock(x, y, z);
        }
        int worldY = y;
        y -= world.dimensionType().minY();
        // Invalid vertical position
        if (y < 0 || y >= world.dimensionType().height()) {
            return AIR;
        }

        if (useTheRealWorld) {
            LevelChunk cached = prev;
            // there's great cache locality in block state lookups
            // generally it's within each movement
            // if it's the same chunk as last time
            // we can just skip the mc.world.getChunk lookup
            // which is a Long2ObjectOpenHashMap.get
            // see issue #113
            if (cached != null && cached.getPos().x() == x >> 4 && cached.getPos().z() == z >> 4) {
                return getFromChunk(cached, x, y, z);
            }
            LevelChunk chunk = provider.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
            if (chunk != null && !chunk.isEmpty()) {
                prev = chunk;
                return getFromChunk(chunk, x, y, z);
            }
        }
        // same idea here, skip the Long2ObjectOpenHashMap.get if at all possible
        // except here, it's 512x512 tiles instead of 16x16, so even better repetition
        CachedRegion cached = prevCached;
        if (cached == null || cached.getX() != x >> 9 || cached.getZ() != z >> 9) {
            if (worldData == null) {
                BlockState hinted = SeedPlan.predict(x, worldY, z);
                return hinted != null ? hinted : AIR;
            }
            CachedRegion region = worldData.cache.getRegion(x >> 9, z >> 9);
            if (region == null) {
                BlockState hinted = SeedPlan.predict(x, worldY, z);
                return hinted != null ? hinted : AIR;
            }
            prevCached = region;
            cached = region;
        }
        BlockState type = cached.getBlock(x & 511, y + world.dimensionType().minY(), z & 511);
        if (type != null) {
            return type;
        }
        BlockState hinted = SeedPlan.predict(x, worldY, z);
        return hinted != null ? hinted : AIR;
    }

    public boolean isLoaded(int x, int z) {
        // Ostinato: legit track ignores the seed predictor outside what the client has loaded.
        if (legitClip) {
            return legitLoaded(x, z);
        }
        return isKnown(x, z) || SeedPlan.covers(x, z);
    }

    /**
     * Ostinato: true where the client has real terrain for this column (a loaded chunk or the world cache), as opposed
     * to {@link #isLoaded}, which also counts columns only the seed predictor ({@link SeedPlan}) covers.
     */
    public boolean isKnown(int x, int z) {
        // Ostinato: legit track does not answer from the world cache.
        if (legitClip) {
            return legitLoaded(x, z);
        }
        LevelChunk prevChunk = prev;
        if (prevChunk != null && prevChunk.getPos().x() == x >> 4 && prevChunk.getPos().z() == z >> 4) {
            return true;
        }
        prevChunk = provider.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (prevChunk != null && !prevChunk.isEmpty()) {
            prev = prevChunk;
            return true;
        }
        CachedRegion prevRegion = prevCached;
        if (prevRegion != null && prevRegion.getX() == x >> 9 && prevRegion.getZ() == z >> 9) {
            return prevRegion.isCached(x & 511, z & 511);
        }
        if (worldData == null) {
            return false;
        }
        prevRegion = worldData.cache.getRegion(x >> 9, z >> 9);
        if (prevRegion == null) {
            return false;
        }
        prevCached = prevRegion;
        return prevRegion.isCached(x & 511, z & 511);
    }

    // Ostinato: loaded client chunk inside the render square. No cache and no seed.
    private boolean legitLoaded(int x, int z) {
        if (provider == null || !LegitHorizon.inside(x, z, legitChunkX, legitChunkZ, legitChunks)) {
            return false;
        }
        LevelChunk chunk = provider.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        return chunk != null && !chunk.isEmpty();
    }

    private BlockState legitBlock(int x, int y, int z) {
        if (world == null || !legitLoaded(x, z)) {
            return AIR;
        }
        y -= world.dimensionType().minY();
        if (y < 0 || y >= world.dimensionType().height()) {
            return AIR;
        }
        LevelChunk chunk = provider.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk != null && !chunk.isEmpty()) {
            prev = chunk;
            return getFromChunk(chunk, x, y, z);
        }
        return AIR;
    }

    // get the block at x,y,z from this chunk WITHOUT creating a single blockpos object
    public static BlockState getFromChunk(LevelChunk chunk, int x, int y, int z) {
        LevelChunkSection section = chunk.getSections()[y >> 4];
        if (section.hasOnlyAir()) {
            return AIR;
        }
        return section.getBlockState(x & 15, y & 15, z & 15);
    }
}
