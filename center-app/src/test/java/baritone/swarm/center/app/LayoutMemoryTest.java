package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.LayoutStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: the shared layout file the window and {@code #center layout} follow. */
public class LayoutMemoryTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sharedFileRoundTripsAndGarbageIsTheWall() throws Exception {
        Path dir = temporary.getRoot().toPath();
        Path file = LayoutStore.sharedFile(dir);
        assertEquals(CenterLayout.WALL, LayoutStore.load(file));
        LayoutStore.save(file, CenterLayout.MAP);
        assertEquals(CenterLayout.MAP, LayoutStore.load(file));
        assertTrue(Files.readString(file).contains("map"));
        LayoutStore.save(file, CenterLayout.WALL);
        assertEquals(CenterLayout.WALL, LayoutStore.load(file));
        Files.writeString(file, "nope\n");
        assertEquals(CenterLayout.WALL, LayoutStore.load(file));
    }

    @Test public void theSharedFileIsPerUserUnderPrefs() {
        Path dir = temporary.getRoot().toPath();
        Path shared = LayoutStore.sharedFile(dir);
        assertEquals("layout-" + LayoutStore.userSlug() + ".txt", shared.getFileName().toString());
        assertEquals(dir.resolve("prefs"), shared.getParent());
        Path config = LayoutStore.file(dir);
        assertEquals("command-center-layout-" + LayoutStore.userSlug() + ".txt", config.getFileName().toString());
        assertFalse(shared.equals(config));
    }
}
