/*
 * Ostinato: run mode setting and property tests. Headless JUnit. No game client.
 */
package baritone.run;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.run.RunMode;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.PathBench;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RunModeTest {

    private String previousProperty;
    private RunMode.Value previousMode;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Before
    public void save() {
        previousProperty = System.getProperty(RunMode.PROPERTY);
        previousMode = BaritoneAPI.getSettings().runMode.value;
        clearProperty();
        RunMode.resetForTests();
    }

    @After
    public void restore() {
        if (previousProperty == null) {
            clearProperty();
        } else {
            System.setProperty(RunMode.PROPERTY, previousProperty);
        }
        Settings settings = BaritoneAPI.getSettings();
        settings.runMode.value = previousMode;
        RunMode.resetForTests();
        RunMode.installStartup(settings);
        if (previousProperty == null) {
            // installStartup must not have kept a token we cleared.
            settings.runMode.value = previousMode;
        }
    }

    @Test
    public void defaultIsNormalAndDoesNotReadAMissingPropertyAsHidden() {
        assertEquals(RunMode.Value.NORMAL, BaritoneAPI.getSettings().runMode.defaultValue);
        assertEquals(RunMode.Value.NORMAL, RunMode.windowCreateMode());
        assertNull(RunMode.propertyError());
        assertEquals(RunMode.Value.NORMAL, RunMode.current());
        assertTrue(RunMode.isNormal());
        assertFalse(RunMode.isHidden());
        assertFalse(RunMode.isHeadless());
    }

    @Test
    public void propertyIsTheModeBeforeSettingsAndTheSettingIsTheModeAfter() {
        System.setProperty(RunMode.PROPERTY, "hidden");
        assertEquals(RunMode.Value.HIDDEN, RunMode.windowCreateMode());
        assertTrue(RunMode.isHidden());
        assertFalse(RunMode.isHeadless());

        Settings settings = BaritoneAPI.getSettings();
        settings.runMode.value = RunMode.Value.NORMAL;
        RunMode.installStartup(settings);
        assertTrue(RunMode.settingsAuthoritative());
        assertEquals(RunMode.Value.HIDDEN, settings.runMode.value);
        assertTrue(RunMode.isHidden());

        SettingsUtil.parseAndApply(settings, "runmode", "normal");
        assertEquals(RunMode.Value.NORMAL, RunMode.current());
        assertTrue(RunMode.isNormal());
        assertFalse(RunMode.isHidden());
    }

    @Test
    public void headlessIsStoredAndIsNotHidden() {
        System.setProperty(RunMode.PROPERTY, "HEADLESS");
        assertEquals(RunMode.Value.HEADLESS, RunMode.windowCreateMode());
        assertTrue(RunMode.isHeadless());
        assertFalse(RunMode.isHidden());
        assertFalse(RunMode.isNormal());

        Settings settings = BaritoneAPI.getSettings();
        RunMode.installStartup(settings);
        assertEquals(RunMode.Value.HEADLESS, settings.runMode.value);
        assertEquals("headless", SettingsUtil.settingValueToString(settings.runMode));
        assertTrue(RunMode.isHeadless());
        assertFalse(RunMode.isHidden());
    }

    @Test
    public void unknownPropertyStaysNormalAndDoesNotOverwriteTheSetting() {
        Settings settings = BaritoneAPI.getSettings();
        settings.runMode.value = RunMode.Value.NORMAL;
        System.setProperty(RunMode.PROPERTY, "minimized");
        assertEquals(RunMode.Value.NORMAL, RunMode.windowCreateMode());
        assertTrue(RunMode.propertyError().contains("not \"minimized\""));
        assertFalse(RunMode.isHidden());

        RunMode.installStartup(settings);
        assertEquals(RunMode.Value.NORMAL, settings.runMode.value);
        assertTrue(RunMode.propertyError().contains("not \"minimized\""));
    }

    @Test
    public void setAcceptsNormalHiddenAndHeadlessAndRejectsOtherTokens() {
        Settings settings = BaritoneAPI.getSettings();
        RunMode.installStartup(settings);
        try {
            SettingsUtil.parseAndApply(settings, "runmode", "hidden");
            assertEquals(RunMode.Value.HIDDEN, settings.runMode.value);
            assertTrue(RunMode.isHidden());
            SettingsUtil.parseAndApply(settings, "runmode", " HEADLESS ");
            assertEquals(RunMode.Value.HEADLESS, settings.runMode.value);
            assertTrue(RunMode.isHeadless());
            SettingsUtil.parseAndApply(settings, "runmode", "Normal");
            assertEquals(RunMode.Value.NORMAL, settings.runMode.value);
            assertEquals("normal", SettingsUtil.settingValueToString(settings.runMode));
            assertTrue(RunMode.isNormal());

            for (String bad : new String[]{"", "yes", "true", "false", "glfw", "window", "0", "1"}) {
                settings.runMode.value = RunMode.Value.NORMAL;
                try {
                    SettingsUtil.parseAndApply(settings, "runmode", bad);
                    fail("accepted " + bad);
                } catch (IllegalArgumentException e) {
                    assertTrue(e.getMessage(), e.getMessage().contains("normal, hidden, or headless"));
                    assertTrue(bad, e.getMessage().contains("not \"" + bad.trim() + "\""));
                }
                assertEquals(RunMode.Value.NORMAL, settings.runMode.value);
            }
        } finally {
            settings.runMode.value = RunMode.Value.NORMAL;
        }
    }

    @Test
    public void nullTokenIsRejectedWithoutAQuotedEmptyString() {
        try {
            RunMode.Value.parse(null);
            fail("accepted null");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("normal, hidden, or headless"));
            assertFalse(e.getMessage(), e.getMessage().contains("not \""));
        }
    }

    private static void clearProperty() {
        System.clearProperty(RunMode.PROPERTY);
    }
}
