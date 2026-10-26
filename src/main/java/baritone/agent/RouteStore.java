package baritone.agent;

// Ostinato: saved task routes. Each save bumps the version. Steps are whitelist commands, never a key.

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RouteStore {

    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,32}$");

    private RouteStore() {}

    public static final class TaskRoute {
        public final String name;
        public final int version;
        public final List<String> steps;

        public TaskRoute(String name, int version, List<String> steps) {
            this.name = name;
            this.version = version;
            this.steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public static TaskRoute save(Path dir, String name, List<String> steps, String tools) throws IOException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("route name");
        }
        List<String> clean = new ArrayList<>();
        if (steps != null) {
            for (String step : steps) {
                String line = step == null ? "" : step.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!stepAllowed(line, tools)) {
                    throw new IllegalArgumentException("route step");
                }
                clean.add(line);
            }
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("route steps");
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".json");
        int version = 1;
        if (Files.isRegularFile(file)) {
            TaskRoute existing = load(dir, name);
            version = existing == null ? 1 : existing.version + 1;
        }
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("version", version);
        JsonArray array = new JsonArray();
        for (String step : clean) {
            array.add(step);
        }
        object.add("steps", array);
        Files.writeString(file, object.toString(), StandardCharsets.UTF_8);
        return new TaskRoute(name, version, clean);
    }

    public static TaskRoute load(Path dir, String name) throws IOException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("route name");
        }
        Path file = dir.resolve(name + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> steps = new ArrayList<>();
        if (object.has("steps") && object.get("steps").isJsonArray()) {
            object.getAsJsonArray("steps").forEach(element -> steps.add(element.getAsString()));
        }
        int version = object.has("version") ? object.get("version").getAsInt() : 1;
        return new TaskRoute(object.get("name").getAsString(), version, steps);
    }

    public static List<String> list(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return names;
        }
        try (var stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                names.add(filename.substring(0, filename.length() - 5));
            }
        }
        Collections.sort(names);
        return names;
    }

    public static boolean stepAllowed(String step, String tools) {
        if (step == null) {
            return false;
        }
        String line = step.trim();
        if (line.isEmpty() || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.length() > 200) {
            return false;
        }
        if (line.indexOf('#') >= 0) {
            return false;
        }
        String command = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!AgentPolicy.toolAllowed(tools, command)) {
            return false;
        }
        if ("set".equals(command)) {
            String[] parts = line.split("\\s+");
            if (parts.length < 3) {
                return false;
            }
            return AgentActions.toCommand("settings", java.util.Map.of("name", parts[1], "value", parts[2])) != null;
        }
        return true;
    }
}
