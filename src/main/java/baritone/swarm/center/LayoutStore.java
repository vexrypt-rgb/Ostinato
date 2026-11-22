package baritone.swarm.center;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ostinato: last command-center layout, one file per OS user under the Ostinato config directory.
 * A missing or unreadable file is the mission-control wall.
 */
public final class LayoutStore {

    private LayoutStore() {}

    /** OS user, safe for a file name. Blank becomes {@code user}. */
    public static String userSlug() {
        String user = System.getProperty("user.name", "user");
        String safe = user == null ? "" : user.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "user" : safe;
    }

    public static Path file(Path configDir) {
        Path dir = configDir == null ? Path.of(System.getProperty("user.home"), ".ostinato") : configDir;
        return dir.resolve("command-center-layout-" + userSlug() + ".txt");
    }

    /**
     * The file the standalone window and {@code #center layout} share when the feed is on:
     * {@code <centerDir>/prefs/layout-<os user>.txt}. {@link #file} stays the in-game config path.
     */
    public static Path sharedFile(Path centerDir) {
        Path root = centerDir == null ? Path.of(System.getProperty("user.home"), ".ostinato") : centerDir;
        return root.resolve("prefs").resolve("layout-" + userSlug() + ".txt");
    }

    public static CenterLayout load(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return CenterLayout.WALL;
            }
            return parse(Files.readString(file));
        } catch (RuntimeException | IOException ignored) {
            return CenterLayout.WALL;
        }
    }

    /** The layout in a file's text: the first line that is not blank or a comment. Anything else is the wall. */
    public static CenterLayout parse(String text) {
        if (text == null) {
            return CenterLayout.WALL;
        }
        try {
            for (String raw : text.split("\\R")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                return CenterLayout.parse(line);
            }
        } catch (RuntimeException ignored) {
            return CenterLayout.WALL;
        }
        return CenterLayout.WALL;
    }

    public static void save(Path file, CenterLayout layout) throws IOException {
        if (file == null || layout == null) {
            throw new IOException("layout file");
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path pending = Files.createTempFile(parent == null ? Path.of(".") : parent, ".layout-", ".tmp");
        try {
            Files.writeString(pending, "# ostinato command center layout\n" + layout.wire() + "\n");
            try {
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }
}
