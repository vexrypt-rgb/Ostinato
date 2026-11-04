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

package baritone.pathing.legato;

import baritone.api.utils.BetterBlockPos;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ostinato Legato: lines that failed when walked, so the next path does not take the same shortcut again. Both the
 * line itself (its two ends) and the cell the player was in when it failed are refused for {@link #TTL_NANOS};
 * after that the terrain is trusted again. Written by the client thread, read by the planning thread.
 */
public final class LegatoFailures {

    public static final long TTL_NANOS = 60_000_000_000L;
    private static final int MAX_ENTRIES = 256;

    private static final Map<Long, Long> PAIRS = new ConcurrentHashMap<>();
    private static final Map<Long, Long> CELLS = new ConcurrentHashMap<>();
    /** Test seam: the clock. */
    static java.util.function.LongSupplier clock = System::nanoTime;

    private LegatoFailures() {}

    static long pairKey(int x0, int y, int z0, int x1, int z1) {
        return BetterBlockPos.longHash(x0, y, z0) * 31 + BetterBlockPos.longHash(x1, y, z1);
    }

    /** Records a line from (x0, y, z0) to (x1, y, z1) that failed with the player in cell (fx, y, fz). */
    public static void record(int x0, int y, int z0, int x1, int z1, int fx, int fz) {
        long until = clock.getAsLong() + TTL_NANOS;
        if (PAIRS.size() >= MAX_ENTRIES || CELLS.size() >= MAX_ENTRIES) {
            prune();
        }
        PAIRS.put(pairKey(x0, y, z0, x1, z1), until);
        PAIRS.put(pairKey(x1, y, z1, x0, z0), until);
        CELLS.put(BetterBlockPos.longHash(fx, y, fz), until);
        LegatoStats.straightFailed();
    }

    public static boolean pairFailed(int x0, int y, int z0, int x1, int z1) {
        return !PAIRS.isEmpty() && live(PAIRS, pairKey(x0, y, z0, x1, z1));
    }

    public static boolean anyCells() {
        return !CELLS.isEmpty();
    }

    public static boolean cellFailed(int x, int y, int z) {
        return live(CELLS, BetterBlockPos.longHash(x, y, z));
    }

    private static boolean live(Map<Long, Long> map, long key) {
        Long until = map.get(key);
        if (until == null) {
            return false;
        }
        if (until - clock.getAsLong() > 0) {
            return true;
        }
        map.remove(key, until);
        return false;
    }

    private static void prune() {
        long now = clock.getAsLong();
        for (Map<Long, Long> map : new Map[]{PAIRS, CELLS}) {
            for (Iterator<Long> it = map.values().iterator(); it.hasNext(); ) {
                if (it.next() - now <= 0) {
                    it.remove();
                }
            }
            if (map.size() >= MAX_ENTRIES) {
                map.clear();
            }
        }
    }

    public static void clear() {
        PAIRS.clear();
        CELLS.clear();
    }
}
