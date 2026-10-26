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

import java.util.Locale;

/**
 * Room themes. The cell's theme comes only from the maze seed and the cell, so two bots agree without talking.
 * Weights are fixed: yellow office 70, pipes 20, hub 8, deep 2. Deep is a stub palette on the shared shell.
 */
public enum MazeTheme {
    YELLOW_OFFICE("yellow_office", "Yellow office", false),
    PIPES("pipes", "Pipes and utility", false),
    HUB("hub", "Office hub", false),
    DEEP("deep", "Deep level", true);

    public final String id;
    public final String displayName;
    /** True when the theme has a name and a loot table but no geometry of its own yet. */
    public final boolean stubbed;

    MazeTheme(String id, String displayName, boolean stubbed) {
        this.id = id;
        this.displayName = displayName;
        this.stubbed = stubbed;
    }

    public static MazeTheme forCell(long mazeSeed, int cellX, int cellZ) {
        int roll = (int) Long.remainderUnsigned(MazeSeed.mix(mazeSeed ^ 0x51F15EEDL, cellX, cellZ), 100);
        if (roll < 70) {
            return YELLOW_OFFICE;
        }
        if (roll < 90) {
            return PIPES;
        }
        if (roll < 98) {
            return HUB;
        }
        return DEEP;
    }

    public static MazeTheme byId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        for (MazeTheme theme : values()) {
            if (theme.id.equals(key)) {
                return theme;
            }
        }
        return null;
    }
}
