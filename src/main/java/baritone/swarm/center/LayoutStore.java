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

    public static Path file(Path configDir) {
        String user = System.getProperty("user.name", "user");
        String safe = user.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "user";
        }
        Path dir = configDir == null ? Path.of(System.getProperty("user.home"), ".ostinato") : configDir;
        return dir.resolve("command-center-layout-" + safe + ".txt");
    }

    public static CenterLayout load(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return CenterLayout.WALL;
            }
            for (String raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                return CenterLayout.parse(line);
            }
        } catch (RuntimeException | IOException ignored) {
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
