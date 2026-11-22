package baritone.swarm.center;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Ostinato: the theme the standalone window and {@code #theme} share when the feed is on,
 * {@code <centerDir>/prefs/theme-<os user>.txt}. {@link ThemeStore#file} stays the in-game config path.
 * A missing or unknown choice is Default dark. This class has no JSON parser; the four built-ins are the window's set.
 */
public final class ThemeChoice {

    public static final String DEFAULT = "dark";

    private ThemeChoice() {}

    public static Path file(Path centerDir) {
        Path root = centerDir == null ? Path.of(System.getProperty("user.home"), ".ostinato") : centerDir;
        return root.resolve("prefs").resolve("theme-" + LayoutStore.userSlug() + ".txt");
    }

    /** Canonical built-in name, or null when nothing has that name. */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        return switch (name) {
            case "dark", "default", "default-dark" -> "dark";
            case "light" -> "light";
            case "high-contrast", "contrast", "highcontrast", "hc" -> "high-contrast";
            case "colorblind", "colorblind-safe", "cb" -> "colorblind";
            default -> null;
        };
    }

    /** The saved choice, or {@link #DEFAULT} when the file is missing, empty, or not a built-in. */
    public static String load(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return DEFAULT;
            }
            return parse(Files.readString(file));
        } catch (RuntimeException | IOException ignored) {
            return DEFAULT;
        }
    }

    /** The choice in a file's text, or {@link #DEFAULT} when it is empty or not a built-in. */
    public static String parse(String text) {
        if (text == null) {
            return DEFAULT;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String found = canonical(line);
            return found == null ? DEFAULT : found;
        }
        return DEFAULT;
    }

    public static void save(Path file, String name) throws IOException {
        if (file == null || name == null || name.isBlank()) {
            throw new IOException("theme file");
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path pending = Files.createTempFile(parent == null ? Path.of(".") : parent, ".theme-", ".tmp");
        try {
            Files.writeString(pending, "# ostinato command center theme\n" + name + "\n");
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
