/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Shared file, identical in every fork.
 */
package baritone.pathing.calc;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Three-way bench: a dense, palette-packed block box. Each fork's {@link XWorld} reads it through its own BlockStateInterface. */
public final class XVoxels {
    public static final int MIN_Y = 0;
    public static final int HEIGHT = 128;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public final int sizeX;
    public final int sizeZ;
    private final byte[] voxels;
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> index = new IdentityHashMap<>();

    public XVoxels(int sizeX, int sizeZ) {
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

    /** The block at a position; air outside the box. */
    public BlockState real(int x, int y, int z) {
        if (!inside(x, y, z)) return AIR;
        return palette.get(voxels[idx(x, y, z)] & 0xFF);
    }

    /** Topmost non-air y in a column, or MIN_Y - 1. */
    public int top(int x, int z) {
        for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) if (real(x, y, z) != AIR) return y;
        return MIN_Y - 1;
    }
}
