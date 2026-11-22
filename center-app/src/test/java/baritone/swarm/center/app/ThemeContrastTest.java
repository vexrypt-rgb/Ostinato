package baritone.swarm.center.app;

import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: built-in themes meet WCAG AA, and the colorblind identity palette stays apart. */
public class ThemeContrastTest {

    private static final List<String> NAMES = List.of("dark", "light", "high-contrast", "colorblind");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everyBuiltInTextAndStatusMeetsAa() {
        for (String name : NAMES) {
            CenterTheme theme = CenterTheme.builtIn(name);
            assertEquals(name, List.of(), theme.aaProblems());
            for (int bg : new int[]{theme.background(), theme.surface(), theme.raised()}) {
                assertTrue(name + " text", CenterTheme.contrast(theme.text(), bg) >= 4.5);
                assertTrue(name + " ok", CenterTheme.contrast(theme.statusOk(), bg) >= 4.5);
                assertTrue(name + " idle", CenterTheme.contrast(theme.statusIdle(), bg) >= 4.5);
                assertTrue(name + " warn", CenterTheme.contrast(theme.statusWarn(), bg) >= 4.5);
                assertTrue(name + " bad", CenterTheme.contrast(theme.statusBad(), bg) >= 4.5);
            }
            assertEquals(CenterTheme.label(name), switch (name) {
                case "dark" -> "Default dark";
                case "light" -> "Light";
                case "high-contrast" -> "High contrast";
                default -> "Colorblind safe";
            });
        }
    }

    @Test public void colorblindIdentityColorsStayTenApart() {
        int[] colors = CenterTheme.COLORBLIND_SAFE.identityPalette();
        assertEquals(12, colors.length);
        assertEquals(12, CenterTheme.IDENTITY.length);
        for (int i = 0; i < colors.length; i++) {
            for (int j = i + 1; j < colors.length; j++) {
                assertTrue(CenterTheme.deltaE(colors[i], colors[j], CenterTheme.PROTAN) >= 10);
                assertTrue(CenterTheme.deltaE(colors[i], colors[j], CenterTheme.DEUTAN) >= 10);
            }
        }
        assertEquals(BotColors.of("Azalea"), CenterTheme.DEFAULT_DARK.identity("Azalea"));
        assertEquals(CenterTheme.DEFAULT_DARK.identity("Azalea"), CenterTheme.DEFAULT_DARK.identity("Azalea"));
        assertFalse(CenterTheme.COLORBLIND_SAFE.identityPalette() == CenterTheme.IDENTITY);
    }

    @Test public void themeFileRoundTripsPerUserAndGarbageIsDark() throws Exception {
        Path dir = temporary.getRoot().toPath();
        Path file = ThemeChoice.file(dir);
        assertEquals("theme-" + baritone.swarm.center.LayoutStore.userSlug() + ".txt", file.getFileName().toString());
        assertEquals(dir.resolve("prefs"), file.getParent());
        assertEquals("dark", ThemeChoice.load(file));
        ThemeChoice.save(file, "light");
        assertEquals("light", ThemeChoice.load(file));
        assertTrue(Files.readString(file).contains("light"));
        assertEquals("colorblind", ThemeChoice.canonical("colorblind_safe"));
        Files.writeString(file, "nope\n");
        assertEquals("dark", ThemeChoice.load(file));
        assertEquals("high-contrast", ThemeChoice.canonical("hc"));
        assertEquals(null, ThemeChoice.canonical("nether"));
    }
}
