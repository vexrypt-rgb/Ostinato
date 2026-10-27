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

import baritone.maze.BlockProbe.BlockKind;
import baritone.maze.MazePlanner.Attempt;
import baritone.maze.ShellSeal.SealVerdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShellSealTest {

    private static final BlockProbe SOLID = (x, y, z) -> BlockKind.SOLID;

    @Test
    public void solidStoneHidesTheRoomAndTheStubIsCapped() {
        RoomPlan plan = MazePlanner.layout(1, 0, 0, 8, 20, 8);
        assertEquals(MazePlanner.SIZE_X, plan.sizeX);
        assertEquals(MazePlanner.SIZE_Y, plan.sizeY);
        assertEquals(MazePlanner.SIZE_Z, plan.sizeZ);
        assertEquals(104, count(plan, BlockRole.AIR)); // Ostinato: 4-high room
        assertEquals(2, count(plan, BlockRole.STUB_CAP));
        assertEquals(1, count(plan, BlockRole.LIGHT));
        assertEquals(1, count(plan, BlockRole.FEATURE));
        SealVerdict verdict = ShellSeal.check(plan, SOLID);
        assertTrue(verdict.airEnclosed);
        assertTrue(verdict.shellClear);
        assertTrue(verdict.stubResealed);
        assertTrue(verdict.underground);
        assertTrue(verdict.sealed());
        assertTrue(verdict.ok());
        assertTrue(ShellSeal.maintenanceTargets(plan, SOLID).isEmpty());
    }

    @Test
    public void airAboveTheCeilingIsSurfaceVisible() {
        RoomPlan plan = MazePlanner.layout(1, 0, 0, 8, 20, 8);
        // Ceiling is originY + 6. Cover is the next four blocks. The second of those is open.
        BlockProbe thinCover = (x, y, z) -> y == 28 ? BlockKind.AIR : BlockKind.SOLID;
        SealVerdict verdict = ShellSeal.check(plan, thinCover);
        assertTrue(verdict.shellClear);
        assertFalse(verdict.underground);
        assertFalse(verdict.ok());
        assertFalse(ShellSeal.maintenanceTargets(plan, thinCover).isEmpty());
    }

    @Test
    public void airBesideTheShellIsABreach() {
        RoomPlan plan = MazePlanner.layout(1, 0, 0, 8, 20, 8);
        BlockProbe cave = (x, y, z) -> x == 7 ? BlockKind.AIR : BlockKind.SOLID;
        SealVerdict verdict = ShellSeal.check(plan, cave);
        assertFalse(verdict.shellClear);
        assertTrue(verdict.underground);
        assertFalse(verdict.sealed());
        assertFalse(ShellSeal.maintenanceTargets(plan, cave).isEmpty());
    }

    @Test
    public void fluidBesideTheShellIsABreach() {
        RoomPlan plan = MazePlanner.layout(1, 0, 0, 8, 20, 8);
        BlockProbe water = (x, y, z) -> x == 7 ? BlockKind.FLUID : BlockKind.SOLID;
        assertFalse(ShellSeal.check(plan, water).shellClear);
    }

    @Test
    public void anOpenedStubIsNotSealed() {
        RoomPlan plan = MazePlanner.layout(1, 0, 0, 8, 20, 8);
        RoomPlan opened = plan.withRole(9, 2, 3, BlockRole.AIR).withRole(9, 3, 3, BlockRole.AIR);
        SealVerdict verdict = ShellSeal.check(opened, SOLID);
        assertFalse(verdict.airEnclosed);
        assertFalse(verdict.stubResealed);
        assertFalse(verdict.sealed());
    }

    @Test
    public void shiftsAwayFromASideCave() {
        BlockProbe cave = (x, y, z) -> x <= 7 ? BlockKind.AIR : BlockKind.SOLID;
        Attempt attempt = MazePlanner.place(1L, 0, 0, 20, -64, 320, cave);
        assertTrue(attempt.refusal, attempt.ok());
        assertTrue(attempt.shifted);
        assertEquals(10, attempt.plan.originX);
        assertEquals(20, attempt.plan.originY);
    }

    @Test
    public void shiftsDownWhenTheSurfaceIsTooClose() {
        BlockProbe sky = (x, y, z) -> y >= 45 ? BlockKind.AIR : BlockKind.SOLID;
        Attempt attempt = MazePlanner.place(1L, 0, 0, 40, -64, 320, sky);
        assertTrue(attempt.refusal, attempt.ok());
        assertTrue(attempt.shifted);
        assertEquals(34, attempt.plan.originY);
        assertTrue(ShellSeal.check(attempt.plan, sky).underground);
    }

    @Test
    public void skipsAClaimedCell() {
        MazeMap map = new MazeMap(MazeSwarmBus.Nop.INSTANCE);
        assertTrue(map.claim(0, 0, 1L, "yellow_office", "a"));
        Attempt attempt = MazePlanner.placeOpen(1L, 0, 0, 20, -64, 320, SOLID, map, 1);
        assertTrue(attempt.ok());
        assertFalse(attempt.cellX == 0 && attempt.cellZ == 0);
        Attempt blocked = MazePlanner.placeOpen(1L, 0, 0, 20, -64, 320, SOLID, (x, z) -> true, 1);
        assertFalse(blocked.ok());
        assertEquals("every nearby cell is already claimed", blocked.refusal);
    }

    private static int count(RoomPlan plan, BlockRole role) {
        int n = 0;
        for (int x = 0; x < plan.sizeX; x++) {
            for (int y = 0; y < plan.sizeY; y++) {
                for (int z = 0; z < plan.sizeZ; z++) {
                    if (plan.role(x, y, z) == role) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
