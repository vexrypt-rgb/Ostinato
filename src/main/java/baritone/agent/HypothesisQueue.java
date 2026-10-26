package baritone.agent;

// Ostinato: queue file for overnight A/B races. Order is the file order. Blank lines are skipped.

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class HypothesisQueue {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern VARIANT = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private HypothesisQueue() {}

    public static List<HypothesisRun> load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("no overnight queue");
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static List<HypothesisRun> parse(String jsonl) {
        List<HypothesisRun> runs = new ArrayList<>();
        if (jsonl == null || jsonl.isBlank()) {
            return runs;
        }
        int lineNo = 0;
        for (String raw : jsonl.split("\\R")) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            runs.add(one(runs.size(), lineNo, line));
        }
        return List.copyOf(runs);
    }

    private static HypothesisRun one(int index, int lineNo, String line) {
        JsonElement element;
        try {
            element = JsonParser.parseString(line);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("line " + lineNo + ": not json");
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("line " + lineNo + ": not json");
        }
        JsonObject object = element.getAsJsonObject();
        String hypothesis = text(object, "hypothesis");
        String variant = text(object, "variant");
        String world = text(object, "world");
        if (!ID.matcher(hypothesis).matches()) {
            throw new IllegalArgumentException("line " + lineNo + ": hypothesis");
        }
        if (!VARIANT.matcher(variant).matches()) {
            throw new IllegalArgumentException("line " + lineNo + ": variant");
        }
        if (!RaceWorldCleanup.INSTANCE.isRaceWorld(world)) {
            throw new IllegalArgumentException("line " + lineNo + ": world");
        }
        long timeout = 0;
        if (object.has("timeoutMs") && !object.get("timeoutMs").isJsonNull()) {
            try {
                timeout = object.get("timeoutMs").getAsLong();
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("line " + lineNo + ": timeoutMs");
            }
            if (timeout < 0) {
                throw new IllegalArgumentException("line " + lineNo + ": timeoutMs");
            }
        }
        boolean flat = true;
        if (object.has("flat") && !object.get("flat").isJsonNull()) {
            try {
                flat = object.get("flat").getAsBoolean();
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("line " + lineNo + ": flat");
            }
        }
        String seed = "";
        String prompt = "";
        try {
            if (object.has("seed") && !object.get("seed").isJsonNull()) {
                seed = object.get("seed").getAsString();
            }
            if (object.has("prompt") && !object.get("prompt").isJsonNull()) {
                prompt = object.get("prompt").getAsString();
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("line " + lineNo + ": field");
        }
        return new HypothesisRun(index, hypothesis, variant, world, seed, flat, prompt, timeout);
    }

    private static String text(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        return object.get(name).getAsString().trim();
    }
}
