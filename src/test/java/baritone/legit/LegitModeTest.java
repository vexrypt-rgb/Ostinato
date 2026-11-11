/*
 * Ostinato: legit mode effective-mode tests. Headless. No game client.
 */
package baritone.legit;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.legit.LegitMode;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.PathBench;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LegitModeTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void autoIsOffInSingleplayerLanAndWhenNoRemoteIsShown() {
        assertFalse(LegitMode.isActive(LegitMode.Value.AUTO, LegitMode.SessionKind.SINGLEPLAYER));
        assertFalse(LegitMode.isActive(LegitMode.Value.AUTO, LegitMode.SessionKind.LAN));
        assertFalse(LegitMode.isActive(LegitMode.Value.AUTO, LegitMode.SessionKind.NONE));
        assertTrue(LegitMode.isActive(LegitMode.Value.AUTO, LegitMode.SessionKind.REMOTE));
    }

    @Test
    public void onAndOffIgnoreTheSession() {
        for (LegitMode.SessionKind session : LegitMode.SessionKind.values()) {
            assertTrue(session.name(), LegitMode.isActive(LegitMode.Value.ON, session));
            assertFalse(session.name(), LegitMode.isActive(LegitMode.Value.OFF, session));
        }
        assertFalse(LegitMode.isActive(null, LegitMode.SessionKind.REMOTE));
    }

    @Test
    public void defaultSettingIsAuto() {
        assertEquals(LegitMode.Value.AUTO, BaritoneAPI.getSettings().legitMode.defaultValue);
    }

    @Test
    public void setAcceptsOnOffAutoAndRejectsOtherTokens() {
        Settings settings = BaritoneAPI.getSettings();
        LegitMode.Value previous = settings.legitMode.value;
        try {
            SettingsUtil.parseAndApply(settings, "legitmode", "on");
            assertEquals(LegitMode.Value.ON, settings.legitMode.value);
            SettingsUtil.parseAndApply(settings, "legitmode", " OFF ");
            assertEquals(LegitMode.Value.OFF, settings.legitMode.value);
            SettingsUtil.parseAndApply(settings, "legitmode", "Auto");
            assertEquals(LegitMode.Value.AUTO, settings.legitMode.value);
            assertEquals("auto", SettingsUtil.settingValueToString(settings.legitMode));

            for (String bad : new String[]{"", "yes", "true", "false", "legit", "enable", "0", "1"}) {
                settings.legitMode.value = LegitMode.Value.AUTO;
                try {
                    SettingsUtil.parseAndApply(settings, "legitmode", bad);
                    fail("accepted " + bad);
                } catch (IllegalArgumentException e) {
                    assertTrue(e.getMessage(), e.getMessage().contains("on, off, or auto"));
                    assertTrue(bad, e.getMessage().contains("not \"" + bad.trim() + "\""));
                }
                assertEquals(LegitMode.Value.AUTO, settings.legitMode.value);
            }
        } finally {
            settings.legitMode.value = previous;
        }
    }
}
