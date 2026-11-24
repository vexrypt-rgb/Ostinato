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

package baritone.swarm.world;

import baritone.swarm.lease.ReservationResolver;

/**
 * Ostinato: Phase 2c. The bot's own world as the resolver, verifier and reporter see it: a block counts as loaded only
 * when the client holds its chunk.
 *
 * <p>26.3's {@code ClientLevel.hasChunk} always returns true, so {@code ClientLevel.hasChunkAt} cannot tell a held chunk
 * from one the client never received, and the client reads the latter as air. Through that check a bot far from a vein
 * saw "no ore left", called the record stale and its gone correction replaced a valid record (p2ch run 1, 17 Nov). The
 * client's chunk source ({@code ClientChunkCache.hasChunk}) is the real test.
 */
public final class LoadedWorld implements ReservationResolver.World {

    /** What the client offers. */
    public interface Client {
        /** True only when the client holds the chunk: {@code ClientChunkCache.hasChunk}, never {@code ClientLevel}'s. */
        boolean chunkHeld(int chunkX, int chunkZ);

        /** Block id at the position. A chunk the client does not hold reads as air, as on the real client. */
        String blockId(int x, int y, int z);

        /** For a double chest at the position, the other half; else null. */
        int[] otherChestHalf(int x, int y, int z);
    }

    private final Client client;

    /**
     * Ostinato: Phase 2c part 2 slice 3. True when every chunk the box x1..x2, z1..z2 touches is held ({@code held}
     * answers per chunk, e.g. ClientChunkCache.hasChunk).
     */
    public static boolean boxHeld(int x1, int z1, int x2, int z2, java.util.function.BiPredicate<Integer, Integer> held) {
        for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++) {
            for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) {
                if (!held.test(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    public LoadedWorld(Client client) {
        this.client = client;
    }

    @Override
    public String block(int x, int y, int z) {
        if (!client.chunkHeld(x >> 4, z >> 4)) {
            return null;
        }
        return client.blockId(x, y, z);
    }

    @Override
    public int[] otherChestHalf(int x, int y, int z) {
        if (!client.chunkHeld(x >> 4, z >> 4)) {
            return null;
        }
        return client.otherChestHalf(x, y, z);
    }
}
