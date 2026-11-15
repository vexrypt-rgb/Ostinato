/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * A Level that reads its blocks from the bot's decoded chunk store instead of a ClientLevel's
 * ClientChunkCache. Vanilla Player construction, collision queries, ray traces and the movement
 * helpers all take a Level; this is that Level, with every world-mutating, lighting, sound and
 * entity-tracking method reduced to a no-op. Nothing here ticks, renders or talks to a server.
 */
package baritone.headless;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.client.multiplayer.ClientRecipeContainer;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class HeadlessLevel extends Level {

    private final HeadlessChunkStore store;
    private final Holder<DimensionType> dimensionType;
    private final RegistryAccess registryAccess;
    private final TickRateManager tickRateManager = new TickRateManager();
    private final Scoreboard scoreboard = new Scoreboard();
    // Ostinato: furnace menus read property sets from this on construction. Empty until UpdateRecipes.
    private RecipeAccess recipes = new ClientRecipeContainer(java.util.Map.of(), SelectableRecipe.SingleInputSet.empty());
    private final LevelEntityGetter<Entity> entities = new LevelEntityGetter<>() {
        @Override
        public Entity get(int id) {
            return null;
        }

        @Override
        public Entity get(UUID uuid) {
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return List.of();
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> type, AbortableIterationConsumer<U> consumer) {
        }

        @Override
        public void get(AABB area, java.util.function.Consumer<Entity> consumer) {
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> type, AABB area,
                                           AbortableIterationConsumer<U> consumer) {
        }
    };
    private LevelData.RespawnData respawnData = LevelData.RespawnData.DEFAULT;
    private final net.minecraft.world.level.border.WorldBorder worldBorder = new net.minecraft.world.level.border.WorldBorder();
    /** Nothing is ever scheduled: the bot never ticks the world, it only reads blocks. */
    private final EmptyTicks<net.minecraft.world.level.block.Block> blockTicks = new EmptyTicks<>();
    private final EmptyTicks<net.minecraft.world.level.material.Fluid> fluidTicks = new EmptyTicks<>();

    private static final class EmptyTicks<T> implements net.minecraft.world.ticks.LevelTickAccess<T> {
        @Override
        public void schedule(net.minecraft.world.ticks.ScheduledTick<T> tick) {
        }

        @Override
        public boolean hasScheduledTick(BlockPos pos, T type) {
            return false;
        }

        @Override
        public int count() {
            return 0;
        }

        @Override
        public boolean willTickThisTick(BlockPos pos, T type) {
            return false;
        }
    }

    public HeadlessLevel(HeadlessChunkStore store, ResourceKey<Level> dimension,
                         RegistryAccess registryAccess, Holder<DimensionType> dimensionType) {
        super(new HeadlessLevelData(), dimension, registryAccess, dimensionType, false, false, 0L, 1);
        this.store = store;
        this.dimensionType = dimensionType;
        this.registryAccess = registryAccess;
    }

    /**
     * Ostinato: a level whose height matches the chunk store. Built without a server dimension registry so a
     * plain JVM can construct the pathing body after spawn.
     */
    public static HeadlessLevel create(HeadlessChunkStore store, RegistryAccess registryAccess) {
        int minY = store.minSection() << 4;
        int height = store.expectedSections() << 4;
        DimensionType type = new DimensionType(false, true, false, false, 1.0, minY, height, height,
                HolderSet.direct(), 0.0f, new DimensionType.MonsterSettings(ConstantInt.of(0), 0),
                DimensionType.Skybox.OVERWORLD, CardinalLighting.Type.DEFAULT, EnvironmentAttributeMap.EMPTY,
                HolderSet.direct(), Optional.empty());
        return new HeadlessLevel(store, Level.OVERWORLD, HeadlessRegistries.forLevel(registryAccess), Holder.direct(type));
    }

    // ------------------------------------------------------------------ world reads

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Ostinato: the same store PlayerSim reads and placement prediction writes. */
    public HeadlessChunkStore chunks() {
        return store;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int y = pos.getY();
        if (y < getMinY() || y >= getMinY() + getHeight()) {
            return AIR;
        }
        BlockState state = store.getBlockState(pos.getX(), y, pos.getZ());
        // VOID_AIR marks a cell whose chunk was never sent. For collision, fluid and ray traces that is air.
        return state.is(Blocks.VOID_AIR) ? AIR : state;
    }

    @Override
    public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
        return worldBorder;
    }

    @Override
    public List<net.minecraft.world.phys.shapes.VoxelShape> getEntityCollisions(Entity entity, AABB area) {
        // No entities exist headlessly, so nothing can obstruct a move.
        return List.of();
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return store.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return store.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Override
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean load) {
        return null;
    }

    @Override
    public LevelChunk getChunk(int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        return this;
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        // Nothing headless samples heightmaps; the top of the build range is the neutral answer.
        return getMinY() + getHeight() - 1;
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public BiomeManager getBiomeManager() {
        return null;
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return null;
    }

    @Override
    public int getSeaLevel() {
        return 63;
    }

    @Override
    public DimensionType dimensionType() {
        return dimensionType.value();
    }

    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.VANILLA_SET;
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes() {
        return null;
    }

    // ------------------------------------------------------------------ level data / services

    @Override
    public ChunkSource getChunkSource() {
        return null;
    }

    @Override
    public MinecraftServer getServer() {
        return null;
    }

    @Override
    public RandomSource getRandom() {
        return this.random;
    }

    @Override
    public long nextSubTickCount() {
        return 0L;
    }

    @Override
    public LevelData getLevelData() {
        return this.levelData;
    }

    @Override
    public TickRateManager tickRateManager() {
        return tickRateManager;
    }

    @Override
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    @Override
    public RecipeAccess recipeAccess() {
        return recipes;
    }

    void setRecipeAccess(RecipeAccess recipes) {
        if (recipes != null) {
            this.recipes = recipes;
        }
    }

    @Override
    public ClockManager clockManager() {
        return null;
    }

    @Override
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(Entity source, double x, double y, double z, Holder<SoundEvent> sound,
                                SoundSource category, float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(Entity source, Entity entity, Holder<SoundEvent> sound,
                                SoundSource category, float volume, float pitch, long seed) {
    }

    @Override
    public void playSound(Entity entity, BlockPos pos, SoundEvent sound, SoundSource category,
                          float volume, float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z,
                            double xSpeed, double ySpeed, double zSpeed) {
    }

    @Override
    public void levelEvent(Entity source, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public void explode(Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator,
                        double x, double y, double z, float radius, boolean fire,
                        ExplosionInteraction interaction, ParticleOptions smallExplosionParticles,
                        ParticleOptions largeExplosionParticles,
                        WeightedList<ExplosionParticleInfo> blockParticles, Holder<SoundEvent> explosionSound) {
    }

    @Override
    public String gatherChunkSourceStats() {
        return "headless";
    }

    @Override
    public void setRespawnData(LevelData.RespawnData data) {
        this.respawnData = data;
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        return respawnData;
    }

    @Override
    public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return blockTicks;
    }

    @Override
    public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return fluidTicks;
    }

    @Override
    public List<? extends net.minecraft.world.entity.player.Player> players() {
        // No client player entity and no tracked players headlessly: the bot's own body is a plain Player.
        return List.of();
    }

    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return entities;
    }

    @Override
    public Collection<EnderDragonPart> dragonParts() {
        return List.of();
    }

    /** LevelData with the neutral values of a fresh survival world. */
    private static final class HeadlessLevelData implements WritableLevelData {
        private LevelData.RespawnData spawn = LevelData.RespawnData.DEFAULT;
        private long gameTime;

        @Override
        public LevelData.RespawnData getRespawnData() {
            return spawn;
        }

        @Override
        public long getGameTime() {
            return gameTime++;
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public Difficulty getDifficulty() {
            return Difficulty.NORMAL;
        }

        @Override
        public boolean isDifficultyLocked() {
            return false;
        }

        @Override
        public void setSpawn(LevelData.RespawnData data) {
            this.spawn = data;
        }
    }
}
