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

import baritone.gui.anim.Anim;
import baritone.gui.model.*;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GuiModelTest {

    @Test
    public void mergerReplacesRemovesAndAppendsOnlyTouchedSettings() {
        List<String> file = Arrays.asList("allowSprint false", "# comment", "", "renderGoal false", "allowparkour true",
                "renderGoal true");
        Map<String, String> upd = new LinkedHashMap<>();
        upd.put("renderGoal", "renderGoal false");   // replace (and drop the later duplicate)
        upd.put("allowparkour", null);                // back to default -> removed
        upd.put("guikeybind", "guiKeybind RSHIFT");   // new -> appended
        List<String> out = SettingsFileMerger.merge(file, upd);
        assertEquals(Arrays.asList("allowSprint false", "# comment", "", "renderGoal false", "guiKeybind RSHIFT"), out);
    }

    @Test
    public void mergerKeyOf() {
        assertEquals("allowsprint", SettingsFileMerger.keyOf("allowSprint true"));
        assertNull(SettingsFileMerger.keyOf("# allowSprint true"));
        assertNull(SettingsFileMerger.keyOf(""));
        assertNull(SettingsFileMerger.keyOf("lonely"));
    }

    @Test
    public void keyNames() {
        assertEquals(345, KeyNames.parse("RCONTROL"));
        assertEquals(345, KeyNames.parse(" rctrl "));
        assertEquals(344, KeyNames.parse("KEY_RSHIFT"));
        assertEquals(297, KeyNames.parse("F8"));
        assertEquals(75, KeyNames.parse("k"));
        assertEquals(KeyNames.NONE, KeyNames.parse("NONE"));
        assertEquals(KeyNames.NONE, KeyNames.parse("definitelyNotAKey"));
        assertEquals("RCONTROL", KeyNames.name(345));
        assertEquals("NONE", KeyNames.name(-1));
        for (int code : new int[]{32, 65, 90, 290, 320, 341, 346, 999}) {
            assertEquals(code, KeyNames.parse(KeyNames.name(code)));
        }
    }

    @Test
    public void filterScores() {
        assertEquals("allow parkour place", SettingFilter.humanize("allowParkourPlace"));
        assertEquals(3, SettingFilter.score("allowPark", "allowParkour", ""));
        assertEquals(3, SettingFilter.score("allow park", "allowParkour", ""));
        assertEquals(2, SettingFilter.score("parkour", "allowParkour", ""));
        assertEquals(1, SettingFilter.score("jump", "allowParkour", "Jump over gaps"));
        assertEquals(0, SettingFilter.score("elytra", "allowParkour", "Jump over gaps"));
        assertTrue(SettingFilter.score("", "x", null) > 0);
    }

    @Test
    public void hudFormat() {
        assertEquals("21.4s", HudFormat.seconds(428));
        assertEquals("2m 05s", HudFormat.seconds(2500));
        assertEquals("--", HudFormat.seconds(Double.NaN));
        assertEquals("428 t", HudFormat.ticks(428));
        assertEquals("", HudFormat.ticks(-1));
        assertEquals("12.5m", HudFormat.distance(12.5));
        assertEquals("1.5km", HudFormat.distance(1500));
        assertEquals("Block 57 67 70", HudFormat.goal("GoalBlock", new int[]{57, 67, 70}, "whatever"));
        assertEquals("XZ 100 -20", HudFormat.goal("GoalXZ", null, "GoalXZ{x=100,z=-20}"));
        assertEquals("+4.2%", HudFormat.signedPct(4.24));
        assertEquals("-12.5%", HudFormat.signedPct(-12.5));
        assertEquals("+0.0%", HudFormat.signedPct(0));
        assertEquals("+130%", HudFormat.signedPct(130.4));
        assertEquals("+100%", HudFormat.signedPct(99.96));
        assertEquals("-999%", HudFormat.signedPct(-5000));
        assertEquals("--", HudFormat.signedPct(Double.NaN));
        assertEquals("2.84", HudFormat.perSecond(0.142));
        assertEquals("--", HudFormat.perSecond(Double.POSITIVE_INFINITY));
        assertEquals(2, HudFormat.cardScale(1920, 132, 4));
        assertEquals(3, HudFormat.cardScale(2560, 132, 4));
        assertEquals(4, HudFormat.cardScale(3840, 132, 6));
        assertEquals(2, HudFormat.cardScale(1280, 132, 3));
        assertEquals("never below two pixels a unit", 2, HudFormat.cardScale(1920, 132, 1));
        assertEquals("never above the GUI scale", 2, HudFormat.cardScale(3840, 132, 2));
        assertEquals(0.5f, HudFormat.finePrint(2), 0);
        assertEquals(2 / 3f, HudFormat.finePrint(3), 0);
        assertEquals(0.5f, HudFormat.finePrint(4), 0);
        assertEquals(0.6f, HudFormat.finePrint(5), 0);
        assertEquals(1f, HudFormat.finePrint(1), 0);
    }

    @Test
    public void rangesSnapAndClamp() {
        SettingRanges.Range r = SettingRanges.get("maxFallHeightBucket");
        assertNotNull(r);
        assertEquals(r.max, r.snap(1e9), 0);
        assertEquals(r.min, r.snap(-5), 0);
        assertEquals(10, r.snap(10.4), 1e-9);
        assertEquals(0.5, r.fraction((r.min + r.max) / 2), 1e-9);
        assertEquals(0, r.fraction(r.min - 100), 1e-9);
        assertNull(SettingRanges.get("noSuchSetting"));
    }

    @Test
    public void textInput() {
        TextInput t = new TextInput(8);
        t.insert("abc");
        t.left();
        t.insert("X");
        assertEquals("abXc", t.get());
        t.backspace();
        t.delete();
        assertEquals("ab", t.get());
        t.home();
        t.insert("\u00a7>");
        assertEquals(">ab", t.get());
        t.selectAll();
        t.insert("new");
        assertEquals("new", t.get());
        t.insert("123456789");
        assertEquals(8, t.get().length());
        t.end();
        assertEquals(8, t.caret());
    }

    @Test
    public void animEasesWithInjectedClock() {
        long[] now = {0};
        Anim a = new Anim(0, 12, () -> now[0]);
        a.get();
        a.target(1f);
        now[0] += 50_000_000L; // 50 ms
        float v1 = a.get();
        assertTrue(v1 > 0.3f && v1 < 0.6f);
        now[0] += 2_000_000_000L; // dt is capped at 250 ms per step
        for (int i = 0; i < 20; i++) {
            now[0] += 250_000_000L;
            a.get();
        }
        assertEquals(1f, a.get(), 0f);
        a.snap(0.25f);
        assertEquals(0.25f, a.get(), 0f);
    }
}
