package baritone.swarm.center.app;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: control groups 1 to 9 and the per-user prefs file. */
public class MapGroupsTest {

    @Test public void storeAndRecallStayInsideOneToNine() {
        MapGroups g = new MapGroups();
        g.store(1, List.of("Azalea", "Spelunker"));
        g.store(9, List.of("Copper"));
        g.store(0, List.of("Oak"));
        g.store(10, List.of("Oak"));
        g.store(2, java.util.Arrays.asList(" ", null));
        assertEquals(List.of("Azalea", "Spelunker"), g.recall(1));
        assertEquals(List.of("Copper"), g.recall(9));
        assertTrue(g.recall(2).isEmpty());
        assertTrue(g.recall(0).isEmpty());
        assertTrue(g.recall(5).isEmpty());
        g.store(1, List.of());
        assertTrue(g.recall(1).isEmpty());
    }

    @Test public void parseSkipsGarbageAndARoundTripKeepsTheGroups() throws Exception {
        MapGroups g = new MapGroups();
        g.parse("# comment\nnot a pair\n0=Oak\n10=Oak\n2=Azalea, Spelunker,,Azalea\n3=\n9=Copper\n");
        assertEquals(List.of("Azalea", "Spelunker"), g.recall(2));
        assertTrue(g.recall(3).isEmpty());
        assertEquals(List.of("Copper"), g.recall(9));
        assertTrue(g.recall(0).isEmpty());
        g.parse(null);
        assertTrue(g.recall(9).isEmpty());
        g.store(1, List.of("Azalea", "Spelunker"));
        g.store(4, List.of("Copper"));
        Path dir = Files.createTempDirectory("p3-groups");
        try {
            Path file = MapGroups.file(dir);
            g.save(file);
            assertEquals("prefs", file.getParent().getFileName().toString());
            assertEquals(MapGroups.slug() + ".properties", file.getFileName().toString());
            assertTrue(Files.readString(file).contains("1=Azalea,Spelunker"));
            MapGroups loaded = new MapGroups();
            loaded.read(file);
            assertEquals(List.of("Azalea", "Spelunker"), loaded.recall(1));
            assertEquals(List.of("Copper"), loaded.recall(4));
            assertTrue(loaded.recall(2).isEmpty());
            loaded.read(dir.resolve("missing.properties"));
            assertTrue(loaded.recall(1).isEmpty());
        } finally {
            delete(dir);
        }
    }

    @Test public void theUserSlugDropsCharactersAFileNameCannotKeep() {
        assertEquals("user", MapGroups.slugOf(null));
        assertEquals("user", MapGroups.slugOf(""));
        assertEquals("___", MapGroups.slugOf("   "));
        assertEquals("a_b_c", MapGroups.slugOf("a b/c"));
        assertEquals("Ada", MapGroups.slugOf("Ada"));
        assertFalse(MapGroups.slug().isBlank());
    }

    private static void delete(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        }
    }
}
