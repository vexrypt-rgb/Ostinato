/*
 * Ostinato: regression suite. Headless world for the sim drills. New file, replaces no Baritone code.
 */
package baritone.regress;

import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * Ostinato: a sparse overworld-height (-64..319) block world the real pathfinder can plan in with no client. Blocks come
 * from course-script commands ({@code fill}/{@code setblock}, as PkTest runs them in game) and, for map courses, from the
 * save's region files, read a chunk at a time on first touch. A chunk counts as loaded once the save has it or a
 * command wrote into it, so the search stops at the same chunk edges a forceloaded drill area has in game.
 */
final class CourseWorld extends BlockStateInterface {

    static final int MIN_Y = -64, HEIGHT = 384;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Map<String, BlockState> PARSED = new HashMap<>();
    /** Blocks renamed since the map saves were written; the game's DataFixer would rename them on load. */
    private static final Map<String, String> LEGACY = Map.of(
            "minecraft:grass", "minecraft:short_grass",
            "minecraft:chain", "minecraft:iron_chain");

    private final Nbt.Regions regions; // null: command-built course
    private final Map<Long, BlockState[]> sections = new HashMap<>();
    private final Set<Long> loaded = new HashSet<>();
    private final Set<Long> tried = new HashSet<>();
    private long lastKey = Long.MIN_VALUE;
    private BlockState[] last;
    final Set<String> unknownBlocks = new TreeSet<>();

    CourseWorld(Nbt.Regions regions) {
        super(MIN_Y, HEIGHT, new BetterWorldBorder(-29_999_984, 29_999_984, -29_999_984, 29_999_984));
        this.regions = regions;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) (x >> 4) & 0x3FFFFF) << 42 | ((long) (z >> 4) & 0x3FFFFF) << 20 | ((y - MIN_Y) >> 4);
    }

    /** Block state from "name[k=v,...]" (namespace optional); unknown names read as stone and are listed. */
    BlockState parse(String spec) {
        String raw = spec.trim();
        BlockState cached = PARSED.get(raw);
        if (cached != null) return cached;
        int br = raw.indexOf('[');
        String body = br >= 0 ? raw.substring(0, br) : raw;
        String props = br >= 0 && raw.endsWith("]") ? raw.substring(br + 1, raw.length() - 1) : "";
        if (body.indexOf('{') >= 0) body = body.substring(0, body.indexOf('{')); // block entity data: ignored
        if (!body.contains(":")) body = "minecraft:" + body;
        body = LEGACY.getOrDefault(body, body);
        BlockState state;
        Optional<? extends net.minecraft.core.Holder.Reference<Block>> found = BuiltInRegistries.BLOCK.get(Identifier.parse(body));
        if (found.isEmpty()) {
            unknownBlocks.add(body);
            state = Blocks.STONE.defaultBlockState();
        } else {
            Block block = found.get().value();
            state = block.defaultBlockState();
            if (!props.isEmpty()) {
                for (String part : props.split(",")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length != 2) continue;
                    Property<?> p = block.getStateDefinition().getProperty(kv[0].trim());
                    if (p != null) state = with(state, p, kv[1].trim());
                }
            }
        }
        PARSED.put(raw, state);
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(val -> s.setValue(p, val)).orElse(s);
    }

    private void ensure(int cx, int cz) {
        long ck = chunkKey(cx, cz);
        if (regions == null || !tried.add(ck)) return;
        Map<Integer, Nbt.Section> secs;
        try {
            secs = regions.chunk(cx, cz);
        } catch (IOException e) {
            throw new UncheckedIOException("chunk " + cx + "," + cz, e);
        }
        if (secs == null) return;
        loaded.add(ck);
        for (Map.Entry<Integer, Nbt.Section> e : secs.entrySet()) {
            int sy = e.getKey();
            if (sy * 16 < MIN_Y || sy * 16 >= MIN_Y + HEIGHT) continue;
            Nbt.Section s = e.getValue();
            if (s.palette().length == 1 && s.palette()[0].equals("minecraft:air")) continue;
            BlockState[] pal = new BlockState[s.palette().length];
            for (int i = 0; i < pal.length; i++) pal[i] = parse(s.palette()[i]);
            BlockState[] arr = new BlockState[4096];
            for (int ly = 0; ly < 16; ly++)
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++) {
                        int k = s.index(lx, ly, lz);
                        arr[(ly << 8) | (lz << 4) | lx] = k < pal.length ? pal[k] : AIR;
                    }
            sections.put(sectionKey(cx << 4, sy * 16, cz << 4), arr);
        }
    }

    void set(int x, int y, int z, BlockState s) {
        if (y < MIN_Y || y >= MIN_Y + HEIGHT) return;
        ensure(x >> 4, z >> 4);
        loaded.add(chunkKey(x >> 4, z >> 4));
        long k = sectionKey(x, y, z);
        BlockState[] arr = sections.computeIfAbsent(k, kk -> new BlockState[4096]);
        arr[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = s;
        lastKey = Long.MIN_VALUE;
    }

    /** Marks a chunk loaded with nothing in it (a forceloaded area that is still air). */
    void touch(int x, int z) {
        ensure(x >> 4, z >> 4);
        loaded.add(chunkKey(x >> 4, z >> 4));
    }

    @Override
    public BlockState get0(int x, int y, int z) {
        if (y < MIN_Y || y >= MIN_Y + HEIGHT) return AIR;
        long k = sectionKey(x, y, z);
        BlockState[] arr;
        if (k == lastKey) {
            arr = last;
        } else {
            ensure(x >> 4, z >> 4);
            arr = sections.get(k);
            lastKey = k;
            last = arr;
        }
        if (arr == null) return AIR;
        BlockState s = arr[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        return s == null ? AIR : s;
    }

    @Override
    public boolean isLoaded(int x, int z) {
        ensure(x >> 4, z >> 4);
        return loaded.contains(chunkKey(x >> 4, z >> 4));
    }

    @Override
    public boolean isKnown(int x, int z) {
        return isLoaded(x, z);
    }

    @Override
    public boolean worldContainsLoadedChunk(int x, int z) {
        return isLoaded(x, z);
    }

    /** Feet can stand at (x, y, z): two passable-looking air-ish cells over a block with a collision box. */
    boolean standable(int x, int y, int z) {
        BlockState below = get0(x, y - 1, z);
        return !below.getCollisionShape(access, new net.minecraft.core.BlockPos(x, y - 1, z)).isEmpty()
                && get0(x, y, z).getCollisionShape(access, new net.minecraft.core.BlockPos(x, y, z)).isEmpty()
                && get0(x, y + 1, z).getCollisionShape(access, new net.minecraft.core.BlockPos(x, y + 1, z)).isEmpty();
    }
}
