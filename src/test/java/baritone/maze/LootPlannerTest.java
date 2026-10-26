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
import baritone.maze.LootPlanner.LootStack;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LootPlannerTest {

    @Test
    public void lootIsSeedStableAndStaysOnTheThemeTable() {
        int rooms = 0;
        int containers = 0;
        boolean sawEmpty = false;
        boolean sawSome = false;
        for (int i = 0; i < 80; i++) {
            RoomPlan plan = MazePlanner.layout(i, i, 1, 0, 10, 0);
            List<LootPlacement> once = LootPlanner.plan(plan, LootPlanner.PlacedLedger.EMPTY);
            List<LootPlacement> twice = LootPlanner.plan(plan, LootPlanner.PlacedLedger.EMPTY);
            assertEquals(once, twice);
            rooms++;
            containers += once.size();
            if (once.isEmpty()) {
                sawEmpty = true;
            } else {
                sawSome = true;
            }
            for (LootPlacement placement : once) {
                assertEquals(BlockRole.AIR, plan.role(placement.x() - plan.originX, placement.y() - plan.originY, placement.z() - plan.originZ));
                List<String> table = LootPlanner.table(placement.theme(), placement.kind());
                assertFalse(placement.stacks().isEmpty());
                for (LootStack stack : placement.stacks()) {
                    assertTrue(placement.kind() + " " + stack.itemId(), table.contains(stack.itemId()));
                    assertTrue(stack.count() >= 1);
                }
            }
        }
        assertTrue(sawEmpty);
        assertTrue(sawSome);
        assertTrue("loot rate is too high: " + containers + " in " + rooms, containers < rooms);
    }

    @Test
    public void ledgerStopsARefill() {
        RoomPlan found = null;
        List<LootPlacement> first = List.of();
        for (int i = 0; i < 200 && first.isEmpty(); i++) {
            RoomPlan plan = MazePlanner.layout(1000L + i, 0, 0, 0, 16, 0);
            first = LootPlanner.plan(plan, LootPlanner.PlacedLedger.EMPTY);
            found = plan;
        }
        assertFalse(first.isEmpty());
        MazeMap map = new MazeMap(MazeSwarmBus.Nop.INSTANCE);
        for (LootPlacement placement : first) {
            assertTrue(map.recordLoot(placement));
            assertFalse(map.recordLoot(placement));
        }
        List<LootPlacement> again = LootPlanner.plan(found, map);
        for (LootPlacement placement : again) {
            assertFalse(map.contains(placement.x(), placement.y(), placement.z()));
        }
        for (LootPlacement placement : first) {
            assertTrue(map.contains(placement.x(), placement.y(), placement.z()));
            for (LootPlacement other : again) {
                assertFalse(placement.x() == other.x() && placement.y() == other.y() && placement.z() == other.z());
            }
        }
    }
}
