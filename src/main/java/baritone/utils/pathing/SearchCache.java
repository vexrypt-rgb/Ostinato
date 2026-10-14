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

package baritone.utils.pathing;

import java.util.Arrays;

/**
 * Per-thread reusable buffers for the per-search mining-cost memo (see
 * {@code MovementHelper.getMiningDurationTicks}). Ported from Soprano
 * (https://github.com/AverWasTaken/soprano, LGPL-3.0, by AverWasTaken),
 * src/main/java/baritone/utils/pathing/SearchCache.java (mining buffers only).
 */
public final class SearchCache {

    /** Slots in the memo: 2^16, plenty for one search, about 2 MB per pathing thread. */
    public static final int MINING_BITS = 16;

    private static final ThreadLocal<MiningBuffers> CURRENT = new ThreadLocal<>();

    private SearchCache() {}

    public static MiningBuffers acquireMining() {
        MiningBuffers cached = CURRENT.get();
        MiningBuffers buffers = cached;
        if (buffers == null || buffers.owner != null) {
            buffers = new MiningBuffers(MINING_BITS);
            // a nested search gets its own arrays; don't evict the outer search's reusable ones
            if (cached == null) {
                CURRENT.set(buffers);
            }
        }
        // values can stay, keys can't: stone might be air in the next search
        buffers.clearKeys();
        buffers.owner = Thread.currentThread();
        return buffers;
    }

    public static void release(MiningBuffers buffers) {
        if (buffers.owner == Thread.currentThread()) {
            buffers.owner = null;
        }
    }

    public static final class MiningBuffers {
        public final int bits;
        public final long[] keys;
        public final long[] fallingKeys;
        public final double[] values;
        public final double[] fallingValues;
        volatile Thread owner;

        private MiningBuffers(int bits) {
            this.bits = bits;
            keys = new long[1 << bits];
            fallingKeys = new long[1 << bits];
            values = new double[1 << bits];
            fallingValues = new double[1 << bits];
        }

        void clearKeys() {
            Arrays.fill(keys, -1L);
            Arrays.fill(fallingKeys, -1L);
        }
    }
}
