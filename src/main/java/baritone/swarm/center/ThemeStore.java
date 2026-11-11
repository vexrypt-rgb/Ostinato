package baritone.swarm.center;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: command-center themes by name. Built-ins first, then {@code <config>/themes/*.json} custom files.
 * A custom file is an object of token names to {@code "#RRGGBB"}, plus an optional {@code "base"} built-in that
 * fills the tokens it leaves out. The file name (without .json) is the theme name; it cannot replace a built-in.
 * The choice is saved one file per OS user, like {@link LayoutStore}. A missing or unknown choice is Default dark.
 */
public final class ThemeStore {

    public static final String DEFAULT = "dark";

    private static final Map<String, CenterTheme> BUILT_INS = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        BUILT_INS.put("dark", CenterTheme.DEFAULT_DARK);
        BUILT_INS.put("light", CenterTheme.LIGHT);
        BUILT_INS.put("high-contrast", CenterTheme.HIGH_CONTRAST);
        BUILT_INS.put("colorblind", CenterTheme.COLORBLIND_SAFE);
        ALIASES.put("default", "dark");
        ALIASES.put("default-dark", "dark");
        ALIASES.put("contrast", "high-contrast");
        ALIASES.put("highcontrast", "high-contrast");
        ALIASES.put("hc", "high-contrast");
        ALIASES.put("colorblind-safe", "colorblind");
        ALIASES.put("cb", "colorblind");
    }

    private ThemeStore() {}

    public static Map<String, CenterTheme> builtIns() {
        return java.util.Collections.unmodifiableMap(BUILT_INS);
    }

    /** Display label for the header picker. */
    public static String label(String name) {
        return switch (name) {
            case "dark" -> "Default dark";
            case "light" -> "Light";
            case "high-contrast" -> "High contrast";
            case "colorblind" -> "Colorblind safe";
            default -> name;
        };
    }

    public static Path themesDir(Path configDir) {
        return dir(configDir).resolve("themes");
    }

    /** Built-ins, then custom themes in name order. Bad custom files are skipped. */
    public static Map<String, CenterTheme> all(Path configDir) {
        Map<String, CenterTheme> all = new LinkedHashMap<>(BUILT_INS);
        all.putAll(custom(configDir));
        return all;
    }

    public static List<String> names(Path configDir) {
        return new ArrayList<>(all(configDir).keySet());
    }

    /** The canonical name for user input, or null when no theme has that name. */
    public static String resolve(Path configDir, String input) {
        if (input == null) {
            return null;
        }
        String name = normalize(input);
        name = ALIASES.getOrDefault(name, name);
        return all(configDir).containsKey(name) ? name : null;
    }

    public static CenterTheme theme(Path configDir, String name) {
        String found = resolve(configDir, name);
        return found == null ? CenterTheme.DEFAULT_DARK : all(configDir).get(found);
    }

    static Map<String, CenterTheme> custom(Path configDir) {
        Map<String, CenterTheme> found = new java.util.TreeMap<>();
        Path folder = themesDir(configDir);
        if (!Files.isDirectory(folder)) {
            return found;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path file : files) {
                String raw = file.getFileName().toString();
                String name = normalize(raw.substring(0, raw.length() - ".json".length()));
                if (name.isEmpty() || BUILT_INS.containsKey(name) || ALIASES.containsKey(name)) {
                    continue;
                }
                try {
                    found.put(name, parse(Files.readString(file)));
                } catch (RuntimeException | IOException ignored) {
                    // A broken file is left out of the list. The other themes still load.
                }
            }
        } catch (IOException ignored) {
            return found;
        }
        return found;
    }

    /** Parses a custom theme. Throws on a non-object, a bad color, or an unknown base. */
    public static CenterTheme parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("theme file is not an object");
        }
        JsonObject object = root.getAsJsonObject();
        CenterTheme base = CenterTheme.DEFAULT_DARK;
        if (object.has("base")) {
            String wanted = normalize(object.get("base").getAsString());
            base = BUILT_INS.get(ALIASES.getOrDefault(wanted, wanted));
            if (base == null) {
                throw new IllegalArgumentException("unknown base theme");
            }
        }
        Map<String, Integer> tokens = new HashMap<>();
        for (String token : CenterTheme.TOKENS) {
            if (object.has(token)) {
                tokens.put(token, color(object.get(token).getAsString()));
            }
        }
        return base.with(tokens);
    }

    static int color(String text) {
        String hex = text == null ? "" : text.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (!hex.matches("[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("color must be #RRGGBB");
        }
        return Integer.parseInt(hex, 16);
    }

    private static String normalize(String input) {
        return input.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-').replaceAll("[^a-z0-9.-]", "");
    }

    private static Path dir(Path configDir) {
        return configDir == null ? Path.of(System.getProperty("user.home"), ".ostinato") : configDir;
    }

    public static Path file(Path configDir) {
        String user = System.getProperty("user.name", "user");
        String safe = user.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "user";
        }
        return dir(configDir).resolve("command-center-theme-" + safe + ".txt");
    }

    /** The saved choice when it still names a theme, otherwise {@link #DEFAULT}. */
    public static String load(Path configDir) {
        Path file = file(configDir);
        try {
            if (!Files.isRegularFile(file)) {
                return DEFAULT;
            }
            for (String raw : Files.readAllLines(file)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String found = resolve(configDir, line);
                return found == null ? DEFAULT : found;
            }
        } catch (RuntimeException | IOException ignored) {
            return DEFAULT;
        }
        return DEFAULT;
    }

    public static void save(Path configDir, String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IOException("theme name");
        }
        Path file = file(configDir);
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
