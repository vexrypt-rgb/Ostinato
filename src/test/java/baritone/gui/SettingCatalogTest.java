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


package baritone.gui;

import baritone.gui.model.SettingCategorizer;
import baritone.gui.model.SettingCategory;
import baritone.gui.model.SettingDescriptions;
import org.junit.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Every user-visible setting in Settings.java gets a real category and a generated description entry. */
public class SettingCatalogTest {

    private static final Pattern SETTING = Pattern.compile("((?:@\\w+\\s*)*)public final Setting<.+?> (\\w+) = new Setting<>");

    static Set<String> userSettingNames() throws Exception {
        String src = new String(Files.readAllBytes(project("src/api/java/baritone/api/Settings.java")), StandardCharsets.UTF_8);
        Set<String> names = new LinkedHashSet<>();
        Matcher m = SETTING.matcher(src);
        while (m.find()) {
            if (!m.group(1).contains("JavaOnly")) {
                names.add(m.group(2));
            }
        }
        return names;
    }

    static Path project(String rel) {
        Path p = Paths.get(rel);
        return Files.exists(p) ? p : Paths.get("..").resolve(rel);
    }

    @Test
    public void everySettingHasACategory() throws Exception {
        Set<String> names = userSettingNames();
        assertTrue("parsed too few settings: " + names.size(), names.size() > 200);
        List<String> other = new ArrayList<>();
        for (String n : names) {
            if (SettingCategorizer.categorize(n) == SettingCategory.OTHER) {
                other.add(n);
            }
        }
        assertTrue("uncategorized settings: " + other, other.isEmpty());
    }

    @Test
    public void newGuiSettingsAreInInterface() {
        for (String n : new String[]{"guiKeybind", "renderPathHud", "pathHudAnchor", "guiAccentColor", "renderAcsHud", "acsHudAnchor", "acsHudOpacity", "acsHudScale"}) {
            assertEquals(n, SettingCategory.INTERFACE, SettingCategorizer.categorize(n));
        }
    }

    @Test
    public void descriptionsJsonIsUpToDate() throws Exception {
        Map<String, String> docs;
        try (Reader r = Files.newBufferedReader(project("src/launch/resources/assets/baritone/ostinato/setting-descriptions.json"), StandardCharsets.UTF_8)) {
            docs = SettingDescriptions.parse(r);
        }
        List<String> missing = new ArrayList<>();
        for (String n : userSettingNames()) {
            if (!docs.containsKey(n.toLowerCase())) {
                missing.add(n);
            }
        }
        assertTrue("run scripts/gen_setting_descriptions.py; missing: " + missing, missing.isEmpty());
        assertTrue(docs.get("guikeybind").contains("RCONTROL"));
    }
}
