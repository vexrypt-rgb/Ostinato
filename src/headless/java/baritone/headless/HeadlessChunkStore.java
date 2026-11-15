/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 *
 * Per-bot, per-dimension chunk store. Sections are decoded with vanilla's own
 * PalettedContainer#read(FriendlyByteBuf) - the same codec LevelChunkSection#read uses - so
 * palette formats and block-state ids come from the 26.3 classes rather than a hand-rolled decoder.
 *
 * A headless bot paths on block states only; biomes are irrelevant. The biome palette that follows
 * each block-state palette is still consumed with a vanilla PalettedContainer, but backed by a
 * synthetic IdMap rather than the datapack BIOME registry. That keeps chunk decoding independent of
 * configuration-time registry reconstruction and works with any server-side biome registry size
 * (Strategy$2 derives the wire storage size from the bits on the wire, not from the id map).
 */
package baritone.headless;

import net.minecraft.core.BlockPos;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class HeadlessChunkStore {

    private static final Strategy<BlockState> BLOCK_STATES = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    /** Stands in for the BIOME registry: ids are kept verbatim, values are never inspected. */
    private static final IdMap<Integer> BIOME_IDS = new IdMap<Integer>() {
        @Override
        public int getId(Integer value) {
            return value == null ? 0 : value;
        }

        @Override
        public Integer byId(int id) {
            return id;
        }

        @Override
        public int size() {
            return 1 << 16;
        }

        @Override
        public Iterator<Integer> iterator() {
            return Collections.singletonList(0).iterator();
        }
    };

    private static final Strategy<Integer> BIOMES = Strategy.createForBiomes(BIOME_IDS);

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();

    private volatile int minSection = -4; // overworld minY -64 >> 4
    private volatile int expectedSections = 24; // 384 / 16
    private volatile long loadedChunks;
    private volatile long decodedSections;
    private volatile long blockUpdates;
    private volatile long forgottenChunks;

    public HeadlessChunkStore() {
    }

    /** Applied from the dimension type carried by ClientboundLoginPacket. */
    public void setDimension(int minY, int height) {
        this.minSection = minY >> 4;
        this.expectedSections = (height + 15) >> 4;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    /**
     * Read one section from the wire with vanilla palette codecs.
     * Layout (LevelChunkSection#write): short nonEmptyBlockCount, short fluidCount,
     * block-state palette, biome palette.
     */
    public static PalettedContainer<BlockState> readSection(FriendlyByteBuf buf) {
        buf.readShort();
        buf.readShort();
        PalettedContainer<BlockState> states = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), BLOCK_STATES);
        states.read(buf);
        // Consume the biome palette so the next section starts at the right byte.
        PalettedContainer<Integer> biomes = new PalettedContainer<>(0, BIOMES);
        biomes.read(buf);
        return states;
    }

    /** Test/synthetic counterpart of {@link #readSection(FriendlyByteBuf)}. */
    public static void writeSection(FriendlyByteBuf buf, PalettedContainer<BlockState> states) {
        buf.writeShort(0);
        buf.writeShort(0);
        states.write(buf);
        new PalettedContainer<>(0, BIOMES).write(buf);
    }

    /**
     * Decode one full chunk. ClientboundLevelChunkPacketData#getReadBuffer is exactly the
     * concatenation of the chunk's sections as written by LevelChunkSection#write (verified from
     * calculateChunkSize / extractChunkData bytecode), so reading sections until the buffer is
     * exhausted needs no section-count field and works for every dimension height.
     */
    public void loadChunk(ClientboundLevelChunkWithLightPacket packet) {
        ClientboundLevelChunkPacketData data = packet.chunkData();
        FriendlyByteBuf buf = data.getReadBuffer();
        List<PalettedContainer<BlockState>> sections = new ArrayList<>(expectedSections);
        try {
            while (buf.isReadable()) {
                sections.add(readSection(buf));
                if (buf.readableBytes() > 0 && buf.readableBytes() < 2) {
                    throw new IllegalStateException("truncated section header");
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("chunk " + packet.x() + "," + packet.z()
                    + " section decode failed after " + sections.size() + " sections, "
                    + buf.readableBytes() + " bytes left", e);
        }
        if (!sections.isEmpty() && sections.size() != expectedSections) {
            // The server decides the section count; trust what it actually sent.
            this.expectedSections = sections.size();
        }
        chunks.put(key(packet.x(), packet.z()), new Chunk(sections));
        loadedChunks++;
        decodedSections += sections.size();
    }

    public void applySectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        packet.runUpdates((pos, state) -> setBlockState(pos, state));
        blockUpdates++;
    }

    /** @return false if the chunk or section is not loaded (the update will be applied on load). */
    public synchronized boolean setBlockState(BlockPos pos, BlockState state) {
        Chunk chunk = chunks.get(key(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunk == null) {
            return false;
        }
        int sy = (pos.getY() >> 4) - minSection;
        if (sy < 0 || sy >= chunk.sections.size()) {
            return false;
        }
        chunk.sections.get(sy).getAndSet(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        return true;
    }

    /** AIR is used for known-empty space; VOID_AIR marks an unknown/unloaded cell. */
    public synchronized BlockState getBlockState(int x, int y, int z) {
        Chunk chunk = chunks.get(key(x >> 4, z >> 4));
        if (chunk == null) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        int sy = (y >> 4) - minSection;
        if (sy < 0 || sy >= chunk.sections.size()) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return chunk.sections.get(sy).get(x & 15, y & 15, z & 15);
    }

    public void forgetChunk(int cx, int cz) {
        if (chunks.remove(key(cx, cz)) != null) {
            forgottenChunks++;
        }
    }

    /** Drop columns outside the current cache window. Vanilla ClientChunkCache does this on a center/radius update. */
    public int retainAround(int centerX, int centerZ, int radius) {
        if (radius < 0) {
            return 0;
        }
        int dropped = 0;
        for (Long k : chunks.keySet()) {
            int cx = (int) (k >> 32);
            int cz = k.intValue();
            if (Math.abs(cx - centerX) > radius || Math.abs(cz - centerZ) > radius) {
                if (chunks.remove(k) != null) {
                    forgottenChunks++;
                    dropped++;
                }
            }
        }
        return dropped;
    }

    public void clear() {
        chunks.clear();
    }

    /** Install an all-air chunk column (synthetic worlds, unit tests, offline simulation). */
    public void createEmptyChunk(int cx, int cz) {
        List<PalettedContainer<BlockState>> sections = new ArrayList<>(expectedSections);
        for (int i = 0; i < expectedSections; i++) {
            sections.add(new PalettedContainer<>(Blocks.AIR.defaultBlockState(), BLOCK_STATES));
        }
        chunks.put(key(cx, cz), new Chunk(sections));
    }

    /** True when the store holds decoded sections for this chunk column. */
    public boolean isChunkLoaded(int cx, int cz) {
        return chunks.containsKey(key(cx, cz));
    }

    public int chunkCount() {
        return chunks.size();
    }

    public long loadedChunks() {
        return loadedChunks;
    }

    public long decodedSections() {
        return decodedSections;
    }

    public long blockUpdates() {
        return blockUpdates;
    }

    public long forgottenChunks() {
        return forgottenChunks;
    }

    public int expectedSections() {
        return expectedSections;
    }

    public int minSection() {
        return minSection;
    }

    private static final class Chunk {
        final List<PalettedContainer<BlockState>> sections;

        Chunk(List<PalettedContainer<BlockState>> sections) {
            this.sections = sections;
        }
    }
}
