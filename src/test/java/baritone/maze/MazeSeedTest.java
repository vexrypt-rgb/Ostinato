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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MazeSeedTest {

    @Test
    public void roomSeedIsStableAndDependsOnTheCell() {
        long once = MazeSeed.roomSeed(0xC0FFEE, 4, -2);
        assertEquals(once, MazeSeed.roomSeed(0xC0FFEE, 4, -2));
        assertNotEquals(once, MazeSeed.roomSeed(0xC0FFEE, 5, -2));
        assertNotEquals(once, MazeSeed.roomSeed(0xC0FFEE, 4, -1));
        assertNotEquals(once, MazeSeed.roomSeed(0xC0FFEE + 1, 4, -2));
        assertEquals(once, MazeSeed.mix(0xC0FFEE, 4, -2));
    }

    @Test
    public void negativeCellsStayInTheirOwnCell() {
        assertEquals(-1, MazeSeed.cellOf(-1));
        assertEquals(-1, MazeSeed.cellOf(-32));
        assertEquals(0, MazeSeed.cellOf(0));
        assertEquals(0, MazeSeed.cellOf(31));
        assertEquals(-24, MazeSeed.originX(-1));
        assertEquals(-1, MazeSeed.cellOf(MazeSeed.originX(-1)));
        assertEquals(-1, MazeSeed.cellOf(MazeSeed.originX(-1) + MazePlanner.SIZE_X - 1));
    }

    @Test
    public void layoutFingerprintRepeats() {
        RoomPlan a = MazePlanner.layout(99, 3, -4, 40, 12, -80);
        RoomPlan b = MazePlanner.layout(99, 3, -4, 40, 12, -80);
        assertEquals(a.fingerprint(), b.fingerprint());
        assertEquals(a.theme, b.theme);
        assertEquals(a.roomSeed, b.roomSeed);
        assertNotEquals(a.fingerprint(), MazePlanner.layout(100, 3, -4, 40, 12, -80).fingerprint());
    }
}
