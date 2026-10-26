package baritone.agent;

// Ostinato: the keep-list. This test only creates a temp directory and never opens a real saves folder.

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorldCleanupTest {

    @Test
    public void keepListSurvivesAndOnlyRaceWorldsGo() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path root = Files.createTempDirectory("ostinato-race-worlds").toAbsolutePath().normalize();
        assertTrue(root.startsWith(tmp));

        for (String name : List.of(
                "vexflat", "VexFlat-26.3", "pk_hh", "pkrun_1", "SigilTest", "gs_world",
                "New World", "race_old", "hyp_a", "agentrun_b", "pk_race_trap")) {
            Files.createDirectories(root.resolve(name));
        }
        Files.writeString(root.resolve("race_note.txt"), "not a world");

        RaceWorldCleanup cleanup = RaceWorldCleanup.INSTANCE;
        assertTrue(cleanup.keep("vexflat"));
        assertTrue(cleanup.keep("VexFlat-26.3"));
        assertTrue(cleanup.keep("pk_hh"));
        assertTrue(cleanup.keep("pkrun_1"));
        assertTrue(cleanup.keep("SigilTest"));
        assertTrue(cleanup.keep("gs_world"));
        assertTrue(cleanup.keep("pk_race_trap"));
        assertFalse(cleanup.keep("New World"));
        assertFalse(cleanup.isRaceWorld("New World"));
        assertFalse(cleanup.isRaceWorld("vexflat"));
        assertFalse(cleanup.isRaceWorld("../race_escape"));
        assertEquals(List.of("agentrun_b", "hyp_a", "race_old"), cleanup.plan(List.of(
                "vexflat", "pk_hh", "pkrun_1", "SigilTest", "gs_world", "New World",
                "race_old", "hyp_a", "agentrun_b", "pk_race_trap", "../race_escape")));

        assertEquals(List.of(), cleanup.deleteLeftovers(root));
        assertTrue(Files.isDirectory(root.resolve("race_old")));

        Files.writeString(root.resolve(RaceWorldCleanup.MARKER), "race root");
        assertEquals(List.of("agentrun_b", "hyp_a", "race_old"), cleanup.deleteLeftovers(root));

        for (String kept : List.of("vexflat", "VexFlat-26.3", "pk_hh", "pkrun_1", "SigilTest", "gs_world",
                "New World", "pk_race_trap")) {
            assertTrue(kept, Files.isDirectory(root.resolve(kept)));
        }
        assertTrue(Files.isRegularFile(root.resolve("race_note.txt")));
        assertTrue(Files.isRegularFile(root.resolve(RaceWorldCleanup.MARKER)));
        for (String gone : List.of("race_old", "hyp_a", "agentrun_b")) {
            assertFalse(gone, Files.exists(root.resolve(gone)));
        }
    }

    @Test
    public void deleteWorldRemovesOnlyThatRaceFolder() throws Exception {
        Path root = Files.createTempDirectory("ostinato-race-one").toAbsolutePath().normalize();
        for (String name : List.of("vexflat", "race_old", "hyp_a", "New World")) {
            Files.createDirectories(root.resolve(name));
        }
        RaceWorldCleanup cleanup = RaceWorldCleanup.INSTANCE;
        assertEquals(List.of(), cleanup.deleteWorld(root, "hyp_a"));
        assertTrue(Files.isDirectory(root.resolve("hyp_a")));

        Files.writeString(root.resolve(RaceWorldCleanup.MARKER), "race root");
        assertEquals(List.of(), cleanup.deleteWorld(root, "vexflat"));
        assertEquals(List.of(), cleanup.deleteWorld(root, "New World"));
        assertEquals(List.of("hyp_a"), cleanup.deleteWorld(root, "hyp_a"));
        assertFalse(Files.exists(root.resolve("hyp_a")));
        assertTrue(Files.isDirectory(root.resolve("race_old")));
        assertTrue(Files.isDirectory(root.resolve("vexflat")));
        assertTrue(Files.isDirectory(root.resolve("New World")));
    }
}
