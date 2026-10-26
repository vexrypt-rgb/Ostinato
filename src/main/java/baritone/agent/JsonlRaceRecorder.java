package baritone.agent;

// Ostinato: append-only race results. The line has no API-key field.

import baritone.api.agent.RaceRecorder;
import baritone.api.agent.RaceRun;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class JsonlRaceRecorder implements RaceRecorder {

    private final Path file;

    public JsonlRaceRecorder(Path file) {
        this.file = file;
    }

    @Override
    public void record(RaceRun run) throws IOException {
        Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
        Files.writeString(file, toJson(run) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public boolean containsRun(String runId) throws IOException {
        if (runId == null || runId.isBlank() || file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String needle = "\"runId\":\"" + runId + "\"";
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static String toJson(RaceRun run) {
        JsonObject object = new JsonObject();
        object.addProperty("runId", run.runId);
        object.addProperty("hypothesis", run.hypothesis);
        object.addProperty("agent", run.agent);
        object.addProperty("provider", run.provider);
        object.addProperty("model", run.model);
        JsonObject metrics = new JsonObject();
        for (Map.Entry<String, String> entry : run.metrics.entrySet()) {
            metrics.addProperty(entry.getKey(), entry.getValue());
        }
        object.add("metrics", metrics);
        object.addProperty("outcome", run.outcome);
        return object.toString();
    }
}
