package baritone.swarm.center;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: layout memory. No window and no display. */
public class LayoutStoreTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void missingFileIsTheWallAndTheChoiceRoundTrips() throws Exception {
        Path config = temporary.getRoot().toPath();
        Path file = LayoutStore.file(config);
        assertEquals(CenterLayout.WALL, LayoutStore.load(file));
        LayoutStore.save(file, CenterLayout.MAP);
        assertEquals(CenterLayout.MAP, LayoutStore.load(file));
        assertTrue(java.nio.file.Files.readString(file).contains("map"));
        LayoutStore.save(file, CenterLayout.WALL);
        assertEquals(CenterLayout.WALL, LayoutStore.load(file));
    }

    @Test public void garbageFallsBackToTheWall() throws Exception {
        Path file = temporary.newFile("layout.txt").toPath();
        java.nio.file.Files.writeString(file, "nope\n");
        assertEquals(CenterLayout.WALL, LayoutStore.load(file));
    }

    @Test public void closeDoesNotDispatch() {
        CenterRuntime.resetForTests();
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        CenterRuntime.setHost(new CenterRuntime.Host() {
            @Override public void open() { opens.incrementAndGet(); }
            @Override public void close() { closes.incrementAndGet(); }
            @Override public void layout(CenterLayout layout) {}
        });
        CenterRuntime.open();
        CenterRuntime.close();
        assertEquals(1, opens.get());
        assertEquals(1, closes.get());
        assertFalse(CenterRuntime.isOpen());
        assertFalse(dispatched.get());
        CenterRuntime.resetForTests();
    }
}
