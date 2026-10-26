package baritone.agent;

// Ostinato: delete leftover race worlds only. vexflat and named fixtures are kept.
// A name has to be a direct child of the directory the caller passes. Tests use a temp directory.

import baritone.api.agent.WorldCleanup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class RaceWorldCleanup implements WorldCleanup {

    /** Present only in a directory the race harness owns. Without it, deleteLeftovers deletes nothing. */
    public static final String MARKER = "ostinato-race-root.txt";

    public static final RaceWorldCleanup INSTANCE = new RaceWorldCleanup();

    @Override
    public boolean keep(String worldName) {
        if (!safeName(worldName)) {
            return true;
        }
        String name = worldName.toLowerCase(Locale.ROOT);
        if (name.startsWith("vexflat")) {
            return true;
        }
        if (name.startsWith("pk_") || name.startsWith("pkrun_")) {
            return true;
        }
        return name.equals("sigiltest") || name.equals("gs_world");
    }

    @Override
    public boolean isRaceWorld(String worldName) {
        if (!safeName(worldName) || keep(worldName)) {
            return false;
        }
        String name = worldName.toLowerCase(Locale.ROOT);
        return name.startsWith("race_") || name.startsWith("hyp_") || name.startsWith("agentrun_");
    }

    @Override
    public List<String> plan(Collection<String> worldNames) {
        List<String> delete = new ArrayList<>();
        if (worldNames == null) {
            return delete;
        }
        for (String name : worldNames) {
            if (isRaceWorld(name)) {
                delete.add(name);
            }
        }
        Collections.sort(delete);
        return delete;
    }

    @Override
    public List<String> deleteWorld(Path savesRoot, String worldName) throws IOException {
        if (savesRoot == null || !Files.isDirectory(savesRoot) || !isRaceWorld(worldName)) {
            return List.of();
        }
        Path root = savesRoot.toAbsolutePath().normalize();
        // Ostinato: same marker gate as deleteLeftovers, so one named delete cannot touch a personal saves folder.
        if (!Files.isRegularFile(root.resolve(MARKER))) {
            return List.of();
        }
        Path child = root.resolve(worldName).normalize();
        if (!child.startsWith(root) || child.equals(root) || !Files.isDirectory(child) || Files.isSymbolicLink(child)) {
            return List.of();
        }
        deleteTree(child);
        return List.of(worldName);
    }

    @Override
    public List<String> deleteLeftovers(Path savesRoot) throws IOException {
        if (savesRoot == null || !Files.isDirectory(savesRoot)) {
            return List.of();
        }
        Path root = savesRoot.toAbsolutePath().normalize();
        // Ostinato: a personal saves folder has no marker, so this cannot delete vexflat or anything else there.
        if (!Files.isRegularFile(root.resolve(MARKER))) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (Files.isDirectory(child) && !Files.isSymbolicLink(child)) {
                    names.add(child.getFileName().toString());
                }
            }
        }
        List<String> planned = plan(names);
        List<String> deleted = new ArrayList<>();
        for (String name : planned) {
            Path child = root.resolve(name).normalize();
            if (!child.startsWith(root) || child.equals(root) || !Files.isDirectory(child) || Files.isSymbolicLink(child)) {
                continue;
            }
            deleteTree(child);
            deleted.add(name);
        }
        Collections.sort(deleted);
        return deleted;
    }

    static boolean safeName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return worldName.indexOf('/') < 0
                && worldName.indexOf('\\') < 0
                && !worldName.contains("..")
                && !worldName.equals(".")
                && !worldName.equals("..");
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            Files.deleteIfExists(root);
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                deleteTree(child);
            }
        }
        Files.deleteIfExists(root);
    }
}
