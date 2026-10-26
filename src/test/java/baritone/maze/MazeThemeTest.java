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

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MazeThemeTest {

    @Test
    public void catalogHasTheThreeRoomsAndAStubbedDeepLevel() {
        assertEquals(4, MazeTheme.values().length);
        Set<String> ids = new HashSet<>();
        for (MazeTheme theme : MazeTheme.values()) {
            assertTrue(ids.add(theme.id));
            assertSame(theme, MazeTheme.byId(theme.id));
            assertSame(theme, MazeTheme.byId(theme.id.toUpperCase()));
        }
        assertFalse(MazeTheme.YELLOW_OFFICE.stubbed);
        assertFalse(MazeTheme.PIPES.stubbed);
        assertFalse(MazeTheme.HUB.stubbed);
        assertTrue(MazeTheme.DEEP.stubbed);
        assertNull(MazeTheme.byId("poolrooms"));
    }

    @Test
    public void themeForACellIsStableAndEveryThemeOccurs() {
        assertEquals(MazeTheme.forCell(42, 3, 9), MazeTheme.forCell(42, 3, 9));
        Map<MazeTheme, Integer> counts = new EnumMap<>(MazeTheme.class);
        for (MazeTheme theme : MazeTheme.values()) {
            counts.put(theme, 0);
        }
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                MazeTheme theme = MazeTheme.forCell(42, x, z);
                counts.put(theme, counts.get(theme) + 1);
            }
        }
        for (MazeTheme theme : MazeTheme.values()) {
            assertTrue(theme.id + " never appeared", counts.get(theme) > 0);
        }
        assertTrue(counts.get(MazeTheme.YELLOW_OFFICE) > counts.get(MazeTheme.PIPES));
        assertTrue(counts.get(MazeTheme.PIPES) > counts.get(MazeTheme.HUB));
        assertTrue(counts.get(MazeTheme.HUB) > counts.get(MazeTheme.DEEP));
    }
}
