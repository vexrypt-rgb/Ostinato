/*
 * Ostinato: headless pathfinding benchmark world. New file, replaces no Baritone code.
 */
package baritone.pathing.calc.bench;

import baritone.pathing.SeedPlan;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: a dense, palette-packed block volume behind the real {@link BlockStateInterface} API, so the unmodified
 * pathfinder and movement costs run against it with no client. Coordinates outside the box read as air and as unloaded.
 */
public final class SyntheticWorld extends BlockStateInterface {

    public static final int MIN_Y = 0;
    public static final int HEIGHT = 128;

    public final int sizeX;
    public final int sizeZ;
    private final byte[] voxels;
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> index = new IdentityHashMap<>();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public SyntheticWorld(int sizeX, int sizeZ) {
        super(MIN_Y, HEIGHT, new BetterWorldBorder(-1, sizeX + 1, -1, sizeZ + 1));
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.voxels = new byte[sizeX * sizeZ * HEIGHT];
        id(AIR);
    }

    private int id(BlockState s) {
        Integer i = index.get(s);
        if (i == null) {
            i = palette.size();
            if (i > 255) throw new IllegalStateException("palette full");
            palette.add(s);
            index.put(s, i);
        }
        return i;
    }

    private int idx(int x, int y, int z) {
        return (x * sizeZ + z) * HEIGHT + (y - MIN_Y);
    }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && x < sizeX && z >= 0 && z < sizeZ && y >= MIN_Y && y < MIN_Y + HEIGHT;
    }

    public void set(int x, int y, int z, BlockState s) {
        if (inside(x, y, z)) voxels[idx(x, y, z)] = (byte) id(s);
    }

    /** Ostinato: columns with x at or past this read as never loaded (air, not loaded), to test far-terrain planning. */
    public int knownMaxX = Integer.MAX_VALUE;

    @Override
    public BlockState get0(int x, int y, int z) {
        if (x >= knownMaxX) { // as BlockStateInterface: unknown terrain reads as the seed prediction, else air
            BlockState p = SeedPlan.predict(x, y, z);
            return p != null ? p : AIR;
        }
        return real(x, y, z);
    }

    /** The world's own block, ignoring {@link #knownMaxX}. */
    public BlockState real(int x, int y, int z) {
        if (!inside(x, y, z)) return AIR;
        return palette.get(voxels[idx(x, y, z)] & 0xFF);
    }

    @Override
    public boolean isLoaded(int x, int z) {
        // everything counts as loaded; the world border (the box) is what stops the search at the edges, so a narrow
        // test world does not trip pathingMaxChunkBorderFetch
        return isKnown(x, z) || SeedPlan.covers(x, z);
    }

    @Override
    public boolean isKnown(int x, int z) {
        return x < knownMaxX;
    }

    @Override
    public boolean worldContainsLoadedChunk(int x, int z) {
        return isLoaded(x, z);
    }

    /** Topmost non-air y in a column, or MIN_Y - 1. */
    public int top(int x, int z) {
        for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) if (get0(x, y, z) != AIR) return y;
        return MIN_Y - 1;
    }
}
