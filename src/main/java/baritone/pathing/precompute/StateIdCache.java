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

package baritone.pathing.precompute;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ostinato 2.0 Phase 07: a small direct-mapped cache in front of {@code Block.BLOCK_STATE_REGISTRY.getId}, used by the
 * planner's per-state tables when {@code pathingStateIdCache} is on (default off).
 * <p>
 * The registry is an identity hash map over every block state (about 30k), so each lookup probes tables far larger
 * than the few dozen states a search touches. Bounded: {@link #SLOTS} entries, a colliding state replaces the slot.
 * Never stale: a state's id is fixed once the registry is built, and a hit compares the state by identity, so the
 * cache returns exactly what the registry returns. Thread-safe without locks: an entry is immutable, so a racing reader
 * sees a whole entry, or misses and asks the registry.
 */
public final class StateIdCache {

    static final int SLOTS = 1024;

    private static final Entry[] CACHE = new Entry[SLOTS];

    private record Entry(BlockState state, int id) {}

    private StateIdCache() {}

    public static int id(BlockState state) {
        int h = System.identityHashCode(state);
        int slot = (h ^ (h >>> 16)) & (SLOTS - 1);
        Entry e = CACHE[slot];
        if (e != null && e.state == state) {
            return e.id;
        }
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        CACHE[slot] = new Entry(state, id);
        return id;
    }

    /** Entries held, for tests. */
    static int size() {
        int n = 0;
        for (Entry e : CACHE) {
            if (e != null) {
                n++;
            }
        }
        return n;
    }
}
