package baritone.swarm.center.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 window, war-room control groups 1 to 9. Remembered per OS user as
 * {@code <centerDir>/prefs/<user>.properties}. A missing file is nine empty groups.
 */
public final class MapGroups {

    public static final int MIN = 1;
    public static final int MAX = 9;

    private final Map<Integer, List<String>> slots = new LinkedHashMap<>();

    public void store(int slot, List<String> names) {
        if (slot < MIN || slot > MAX) {
            return;
        }
        List<String> copy = clean(names);
        if (copy.isEmpty()) {
            slots.remove(slot);
        } else {
            slots.put(slot, copy);
        }
    }

    public List<String> recall(int slot) {
        List<String> v = slots.get(slot);
        return v == null ? List.of() : v;
    }

    /** Load {@code file} into this object. A missing or unreadable file clears every group. */
    public void read(Path file) {
        slots.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            parse(Files.readString(file));
        } catch (IOException e) {
            slots.clear();
        }
    }

    public void save(Path file) throws IOException {
        if (file == null) {
            throw new IOException("groups file");
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toText());
    }

    public String toText() {
        StringBuilder sb = new StringBuilder("# ostinato command center groups\n");
        for (int i = MIN; i <= MAX; i++) {
            List<String> names = slots.get(i);
            if (names != null && !names.isEmpty()) {
                sb.append(i).append('=').append(String.join(",", names)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Replace the groups with the ones in this text. A bad line is skipped. */
    public void parse(String text) {
        slots.clear();
        if (text == null) {
            return;
        }
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            int slot;
            try {
                slot = Integer.parseInt(line.substring(0, eq).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (slot < MIN || slot > MAX) {
                continue;
            }
            List<String> names = new ArrayList<>();
            String rest = line.substring(eq + 1);
            if (!rest.isBlank()) {
                for (String part : rest.split(",")) {
                    String name = part.trim();
                    if (!name.isEmpty() && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
            if (names.isEmpty()) {
                slots.remove(slot);
            } else {
                slots.put(slot, List.copyOf(names));
            }
        }
    }

    public static Path file(Path centerDir) {
        Path root = centerDir == null ? Path.of(System.getProperty("user.home", "."), ".ostinato") : centerDir;
        return root.resolve("prefs").resolve(slug() + ".properties");
    }

    public static String slug() {
        return slugOf(System.getProperty("user.name", "user"));
    }

    static String slugOf(String user) {
        String safe = (user == null ? "" : user).replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "user" : safe;
    }

    private static List<String> clean(List<String> names) {
        List<String> copy = new ArrayList<>();
        if (names == null) {
            return copy;
        }
        for (String name : names) {
            if (name != null && !name.isBlank() && !copy.contains(name)) {
                copy.add(name);
            }
        }
        return List.copyOf(copy);
    }
}
