package baritone.swarm.center;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Ostinato: theme names, custom JSON themes, and the per-user saved choice. */
public class ThemeStoreTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path dir() {
        return temporary.getRoot().toPath();
    }

    @Test public void namesAndAliasesResolve() {
        assertEquals("dark", ThemeStore.resolve(dir(), "Default"));
        assertEquals("high-contrast", ThemeStore.resolve(dir(), "High contrast"));
        assertEquals("colorblind", ThemeStore.resolve(dir(), "colorblind_safe"));
        assertEquals("light", ThemeStore.resolve(dir(), "LIGHT"));
        assertNull(ThemeStore.resolve(dir(), "nether"));
        assertEquals(CenterTheme.DEFAULT_DARK, ThemeStore.theme(dir(), "nether"));
    }

    @Test public void customJsonFillsFromBaseAndSkipsBadFiles() throws Exception {
        Path themes = ThemeStore.themesDir(dir());
        Files.createDirectories(themes);
        Files.writeString(themes.resolve("Ocean Night.json"), "{\"base\":\"light\",\"accent\":\"#0055AA\",\"ok\":\"117733\"}");
        Files.writeString(themes.resolve("broken.json"), "{\"text\":\"blue\"}");
        Files.writeString(themes.resolve("light.json"), "{\"text\":\"#000000\"}");
        Files.writeString(themes.resolve("notes.txt"), "not a theme");
        assertEquals(java.util.List.of("dark", "light", "high-contrast", "colorblind", "ocean-night"), ThemeStore.names(dir()));
        CenterTheme ocean = ThemeStore.theme(dir(), "ocean night");
        assertEquals(0x0055AA, ocean.accent());
        assertEquals(0x117733, ocean.ok());
        assertEquals(CenterTheme.LIGHT.background(), ocean.background());
        assertEquals(CenterTheme.LIGHT, ThemeStore.theme(dir(), "light"));
        try {
            ThemeStore.parse("{\"base\":\"rust\"}");
            fail("unknown base accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("base"));
        }
    }

    @Test public void choiceIsSavedPerUserAndFallsBack() throws Exception {
        assertEquals("dark", ThemeStore.load(dir()));
        ThemeStore.save(dir(), "high-contrast");
        assertTrue(ThemeStore.file(dir()).getFileName().toString().startsWith("command-center-theme-"));
        assertEquals("high-contrast", ThemeStore.load(dir()));
        Files.writeString(ThemeStore.file(dir()), "# x\nvanished\n");
        assertEquals("dark", ThemeStore.load(dir()));
        try (var files = Files.list(dir())) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test public void runtimeThemeReachesTheHost() {
        CenterRuntime.resetForTests();
        CenterTheme[] seen = new CenterTheme[1];
        CenterRuntime.setHost(new CenterRuntime.Host() {
            @Override public void open() {}
            @Override public void close() {}
            @Override public void layout(CenterLayout layout) {}
            @Override public void theme(CenterTheme theme) {
                seen[0] = theme;
            }
        });
        CenterRuntime.theme("light", CenterTheme.LIGHT);
        assertEquals(CenterTheme.LIGHT, seen[0]);
        assertEquals("light", CenterRuntime.themeName());
        CenterRuntime.resetForTests();
        assertEquals(CenterTheme.DEFAULT_DARK, CenterRuntime.theme());
    }
}
