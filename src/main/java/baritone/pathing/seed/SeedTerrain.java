/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.seed;

import baritone.pathing.SeedPlan;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Ostinato: a {@link SeedPlan.Predictor} that runs vanilla's noise terrain for one column at a time
 * ({@link ChunkGenerator#getBaseColumn}). It gives the base shape only: stone, water and lava from aquifers, noise
 * caves, and air. There are no surface blocks (grass and sand read as stone), carver caves, ores, trees or
 * structures, so a planned route through predicted terrain is replanned once its chunks load (PathExecutor
 * already does that for segments not calculated while loaded). Columns are cached, least recently used first out.
 */
public final class SeedTerrain implements SeedPlan.Predictor {

    private static volatile HolderLookup.Provider vanilla;

    private final ChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessor height;
    private final int capacity;
    private final Long2ObjectLinkedOpenHashMap<NoiseColumn> columns = new Long2ObjectLinkedOpenHashMap<>();
    /** Columns generated and nanoseconds spent generating them, for the benchmark and the status line. */
    public volatile long generated, generatedNanos;

    public SeedTerrain(ChunkGenerator generator, RandomState random, int minY, int height, int capacity) {
        this.generator = generator;
        this.random = random;
        this.height = LevelHeightAccessor.create(minY, height);
        this.capacity = capacity;
    }

    /**
     * Vanilla generation for {@code dimension} from {@code seed} alone (for servers: the seed is all we know). The
     * overworld uses the normal preset; amplified or large-biome worlds predict wrongly.
     */
    public static SeedTerrain vanilla(long seed, ResourceKey<Level> dimension, int minY, int height, int capacity) {
        HolderLookup.Provider p = vanilla;
        if (p == null) {
            vanilla = p = VanillaRegistries.createWorldLookup();
        }
        ResourceKey<NoiseGeneratorSettings> key = dimension == Level.NETHER ? NoiseGeneratorSettings.NETHER
                : dimension == Level.END ? NoiseGeneratorSettings.END : NoiseGeneratorSettings.OVERWORLD;
        var settings = p.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key);
        var biome = p.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS); // base terrain does not read biomes
        ChunkGenerator gen = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings);
        RandomState rs = RandomState.create(p.lookupOrThrow(Registries.NOISE), seed, settings.value());
        return new SeedTerrain(gen, rs, minY, height, capacity);
    }

    @Override
    public boolean covers(int blockX, int blockZ) {
        return true;
    }

    @Override
    public BlockState block(int x, int y, int z) {
        return column(x, z).getBlock(y);
    }

    /** Surface height: one above the highest non-air predicted block, or the world's bottom. */
    public int surface(int x, int z) {
        NoiseColumn c = column(x, z);
        for (int y = height.getMaxY(); y >= height.getMinY(); y--) {
            if (!c.getBlock(y).isAir()) return y + 1;
        }
        return height.getMinY();
    }

    private NoiseColumn column(int x, int z) {
        long k = ((long) x << 32) | (z & 0xFFFFFFFFL);
        synchronized (columns) {
            NoiseColumn c = columns.getAndMoveToLast(k);
            if (c != null) return c;
        }
        long t = System.nanoTime();
        NoiseColumn c = generator.getBaseColumn(x, z, height, random); // outside the lock: the noise is the slow part
        generatedNanos += System.nanoTime() - t;
        generated++;
        synchronized (columns) {
            columns.putAndMoveToLast(k, c);
            while (columns.size() > capacity) columns.removeFirst();
        }
        return c;
    }
}
