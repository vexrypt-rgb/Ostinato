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

import baritone.gui.model.SettingCategorizer;
import baritone.gui.model.SettingCategory;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Reads the setting source. Does not construct Settings, which pulls in Minecraft. */
public class MazeSettingsTest {

    @Test
    public void mazeModeDefaultsOffAndTheGuiCanFileTheKnobs() throws Exception {
        Path path = Path.of("src/api/java/baritone/api/Settings.java");
        if (!Files.exists(path)) {
            path = Path.of("..").resolve(path);
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(source.contains("mazeMode = new Setting<>(false)"));
        assertTrue(source.contains("mazeSeed = new Setting<>(0L)"));
        for (String name : new String[]{"mazeMode", "mazeSeed", "mazeMinActionDelay", "mazeMaxActionDelay", "mazeActionJitter", "mazeMapFile"}) {
            assertEquals(name, SettingCategory.BUILDING, SettingCategorizer.categorize(name));
        }
    }
}
