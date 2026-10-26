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

import baritone.maze.LootPlanner.LootPlacement;
import baritone.maze.MazeSwarmBus.ClaimNotice;
import baritone.maze.MazeSwarmBus.LootNotice;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MazeMapTest {

    @Test
    public void fileRoundTripKeepsClaimsAndLoot() throws Exception {
        Path dir = Files.createTempDirectory("ostinato-maze");
        Path file = dir.resolve("maze-map.txt");
        try {
            MazeMap map = new MazeMap(MazeSwarmBus.Nop.INSTANCE);
            assertTrue(map.claim(1, -2, MazeSeed.roomSeed(5, 1, -2), "pipes", "Bot Name"));
            RoomPlan plan = MazePlanner.layout(5, 1, -2, 0, 10, 0);
            List<LootPlacement> loot = LootPlanner.plan(plan, map);
            for (LootPlacement placement : loot) {
                assertTrue(map.recordLoot(placement));
            }
            map.save(file);
            MazeMap loaded = MazeMap.load(file, MazeSwarmBus.Nop.INSTANCE);
            assertTrue(loaded.claimed(1, -2));
            assertEquals("Bot_Name", loaded.claimAt(1, -2).botId);
            assertEquals(MazeSeed.roomSeed(5, 1, -2), loaded.claimAt(1, -2).roomSeed);
            assertFalse(loaded.claim(1, -2, 1L, "hub", "other"));
            for (LootPlacement placement : loot) {
                assertTrue(loaded.contains(placement.x(), placement.y(), placement.z()));
            }
            loaded.save(file);
            String once = Files.readString(file);
            loaded.save(file);
            assertEquals(once, Files.readString(file));
            assertTrue(once.startsWith(MazeMap.HEADER));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir.resolve("maze-map.txt.tmp"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void busCarriesAClaimTheFileHasNotSeenYet() {
        Pipe pipe = new Pipe();
        MazeMap left = new MazeMap(pipe);
        assertTrue(left.claim(4, 5, 9L, "hub", "alpha"));
        MazeMap right = new MazeMap(pipe);
        right.pull();
        assertTrue(right.claimed(4, 5));
        assertEquals("alpha", right.claimAt(4, 5).botId);
        assertFalse(right.claim(4, 5, 1L, "deep", "beta"));
        right.pull();
        assertEquals("alpha", right.claimAt(4, 5).botId);
        assertEquals(1, right.claims().size());
    }

    @Test
    public void cadenceIsStableInsideItsRange() {
        StealthCadence steady = StealthCadence.from(4, 9, 0);
        int delay = steady.delayTicks(123, 0);
        assertTrue(delay >= 4 && delay <= 9);
        assertEquals(delay, steady.delayTicks(123, 0));
        StealthCadence jittered = StealthCadence.from(4, 9, 2);
        int shaken = jittered.delayTicks(123, 1);
        assertTrue(shaken >= 2 && shaken <= 11);
        assertEquals(shaken, jittered.delayTicks(123, 1));
        StealthCadence clamped = StealthCadence.from(9, 1, -3);
        assertEquals(9, clamped.minDelay);
        assertEquals(9, clamped.maxDelay);
        assertEquals(0, clamped.jitter);
        assertEquals(9, clamped.delayTicks(1, 0));
    }

    private static final class Pipe implements MazeSwarmBus {
        private final List<ClaimNotice> claims = new ArrayList<>();
        private final List<LootNotice> loot = new ArrayList<>();

        @Override
        public void publishClaim(int cellX, int cellZ, long roomSeed, String themeId, String botId) {
            claims.add(new ClaimNotice(cellX, cellZ, roomSeed, themeId, botId));
        }

        @Override
        public void publishLoot(int x, int y, int z, String containerKind, String themeId, String payload) {
            loot.add(new LootNotice(x, y, z, containerKind, themeId, payload));
        }

        @Override
        public List<ClaimNotice> pollClaims() {
            List<ClaimNotice> copy = List.copyOf(claims);
            claims.clear();
            return copy;
        }

        @Override
        public List<LootNotice> pollLoot() {
            List<LootNotice> copy = List.copyOf(loot);
            loot.clear();
            return copy;
        }
    }
}
