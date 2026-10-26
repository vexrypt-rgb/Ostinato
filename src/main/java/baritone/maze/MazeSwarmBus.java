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

package baritone.maze;

import java.util.List;

/**
 * Hook for a future swarm transport. The maze map publishes claims and container marks here, and applies whatever
 * {@link #pollClaims()} and {@link #pollLoot()} return. {@link Nop} drops publishes and returns nothing, which is
 * what a single bot uses. A later bus can forward these to other bots. This interface does not open a socket or
 * send chat.
 */
public interface MazeSwarmBus {

    void publishClaim(int cellX, int cellZ, long roomSeed, String themeId, String botId);

    void publishLoot(int x, int y, int z, String containerKind, String themeId, String payload);

    List<ClaimNotice> pollClaims();

    List<LootNotice> pollLoot();

    record ClaimNotice(int cellX, int cellZ, long roomSeed, String themeId, String botId) {}

    record LootNotice(int x, int y, int z, String containerKind, String themeId, String payload) {}

    /** Ignores publishes. The default until a swarm transport is attached. */
    final class Nop implements MazeSwarmBus {
        public static final Nop INSTANCE = new Nop();

        private Nop() {}

        @Override
        public void publishClaim(int cellX, int cellZ, long roomSeed, String themeId, String botId) {}

        @Override
        public void publishLoot(int x, int y, int z, String containerKind, String themeId, String payload) {}

        @Override
        public List<ClaimNotice> pollClaims() {
            return List.of();
        }

        @Override
        public List<LootNotice> pollLoot() {
            return List.of();
        }
    }
}
