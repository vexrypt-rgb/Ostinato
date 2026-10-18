/*
 * Ostinato: regression suite. One stage's metrics file. New file, replaces no Baritone code.
 */
package baritone.regress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: what every stage writes to {@code build/regress/metrics/<stage>.json} and what the baseline stores. Any
 * stage, including a live-client one run by an outside script, plugs in by writing this shape (docs/REGRESSION.md).
 * <ul>
 * <li>{@code metrics}: named numbers with a direction ({@code higher}, {@code lower} or {@code info}), a tolerance
 * ({@code abs} plus {@code rel} times the baseline) and a severity ({@code fail} or {@code warn}) for when they get worse.</li>
 * <li>{@code cases}: per drill goal, query or test, an {@code outcome} ({@code ok} is the passing one) and optional
 * {@code cost}/{@code nodes}/{@code detail}. Baseline ok, now anything else, is a failure; the reverse an improvement.</li>
 * </ul>
 */
final class StageResult {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    static final class Metric {
        String name;
        double value;
        String better = "info"; // higher | lower | info
        String severity = "fail"; // fail | warn
        double abs, rel;
        String unit = "";
    }

    static final class Case {
        String outcome;
        Double cost;
        Long nodes;
        Double ms;
        String detail;
    }

    String stage;
    String kind;
    String status = "ok"; // ok | skipped | error
    String note = "";
    String commit = "";
    List<Metric> metrics = new ArrayList<>();
    Map<String, Case> cases = new LinkedHashMap<>();

    StageResult(String stage, String kind) {
        this.stage = stage;
        this.kind = kind;
    }

    StageResult metric(String name, double value, String better, String severity, double abs, double rel, String unit) {
        Metric m = new Metric();
        m.name = name;
        m.value = value;
        m.better = better;
        m.severity = severity;
        m.abs = abs;
        m.rel = rel;
        m.unit = unit;
        metrics.add(m);
        return this;
    }

    Case add(String id, String outcome) {
        String key = id;
        for (int i = 2; cases.containsKey(key); i++) key = id + "#" + i;
        Case c = new Case();
        c.outcome = outcome;
        cases.put(key, c);
        return c;
    }

    Metric metric(String name) {
        for (Metric m : metrics) if (m.name.equals(name)) return m;
        return null;
    }

    static String fileName(String stage) {
        return stage.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName(stage)), GSON.toJson(this), StandardCharsets.UTF_8);
    }

    static StageResult read(Path file) throws IOException {
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StageResult.class);
    }
}
