/*
 * Ostinato: Pi-cheap pathfinding helpers. New file, replaces no Baritone code.
 *
 * A direct-mapped block-read cache, reused across searches on the same thread.
 * It is installed only while piCheapPathing is on. A miss reads the world as usual,
 * so a hit cannot disagree with the uncached block.
 */
package baritone.pathing.calc;

import baritone.utils.BlockStateInterface;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/** Ostinato: reused block-read cache for {@code piCheapPathing}. */
public final class PiCheap {

    /** Slots in the block cache. 4096 is enough for the blocks one node and its neighbours read again. */
    static final int BLOCK_BITS = 12;

    private static final ThreadLocal<BlockCache> LOCAL = new ThreadLocal<>();

    private PiCheap() {}

    /** How many block reads the cache answered without asking the world, for tests. */
    public static long blockHits() {
        BlockCache c = LOCAL.get();
        return c == null ? 0 : c.hits;
    }

    public static final class BlockCache {
        final long[] keys = new long[1 << BLOCK_BITS];
        final BlockState[] vals = new BlockState[1 << BLOCK_BITS];
        final short[] stamps = new short[1 << BLOCK_BITS];
        short gen;
        long hits;
        public Thread owner;

        public BlockState read(int x, int y, int z, BlockStateInterface bsi) {
            if (!fits(x, y, z)) {
                return bsi.get0(x, y, z);
            }
            long key = pack(x, y, z);
            int slot = slot(key);
            if (stamps[slot] == gen && keys[slot] == key) {
                hits++;
                return vals[slot];
            }
            BlockState state = bsi.get0(x, y, z);
            stamps[slot] = gen;
            keys[slot] = key;
            vals[slot] = state;
            return state;
        }

        private void bump() {
            gen++;
            if (gen == 0) {
                Arrays.fill(stamps, (short) 0);
                gen = 1;
            }
        }
    }

    /** Claim the calling thread's cache and drop the previous search's entries. */
    public static BlockCache claim() {
        BlockCache c = LOCAL.get();
        if (c == null) {
            c = new BlockCache();
            LOCAL.set(c);
        }
        c.owner = Thread.currentThread();
        c.bump();
        return c;
    }

    /** 26-bit signed x/z and 12-bit signed y cover a vanilla world. Outside that, skip the cache. */
    static boolean fits(int x, int y, int z) {
        return (x << 6 >> 6) == x && (z << 6 >> 6) == z && (y << 20 >> 20) == y;
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static int slot(long key) {
        long h = key * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 30;
        return (int) (h >>> (64 - BLOCK_BITS));
    }
}
