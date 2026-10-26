package baritone.training;

// Ostinato: task scores and path signals are separate files. A bucket file refuses a different bucket.

import baritone.api.training.TrainingResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class MetricsWriter {

    private static final String CSV_HEADER = "runId,circuit,seed,mode,model,success,totalTicks,mineTicks,craftTicks,placeTicks,"
            + "deaths,deathCauses,pathFailures,broken,placed,durability,gearTool,gearArmor,gearReason,bucket,biome,"
            + "temperature,mobPressure,inventoryPressure,cues";

    private MetricsWriter() {}

    public static void append(Path dir, TrainingResult task, PathSignals path) throws IOException {
        Files.createDirectories(dir);
        String taskLine = taskJson(task);
        Files.writeString(dir.resolve("runs.jsonl"), taskLine + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Path csv = dir.resolve("runs.csv");
        if (!Files.exists(csv)) {
            Files.writeString(csv, CSV_HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        Files.writeString(csv, csvRow(task) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Path bucketDir = dir.resolve("buckets");
        Files.createDirectories(bucketDir);
        Path bucket = bucketDir.resolve(task.bucketKey.replace('|', '_') + ".jsonl");
        appendBucket(bucket, task.bucketKey, taskLine);
        String pathLine = "{\"runId\":\"" + path.runId + "\",\"terrain\":\"" + path.terrain
                + "\",\"jumpMajority\":\"" + path.jumpMajority + "\",\"riseBlocks\":" + path.riseBlocks
                + ",\"gapBlocks\":" + path.gapBlocks + "}";
        Files.writeString(dir.resolve("path-signals.jsonl"), pathLine + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Refuses to write a record into a file that already holds a different bucket. */
    public static void appendBucket(Path file, String bucketKey, String jsonLine) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            String first = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
            if (!first.contains("\"bucket\":\"" + bucketKey + "\"")) {
                throw new IllegalStateException("refusing to overwrite bucket file " + file + " with " + bucketKey);
            }
        }
        Files.writeString(file, jsonLine + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String taskJson(TrainingResult task) {
        String json = "{\"runId\":\"" + task.runId + "\",\"circuit\":\"" + task.circuitId + "\",\"seed\":" + task.seed
                + ",\"mode\":\"" + task.mode + "\",\"model\":\"" + task.modelId + "\",\"success\":" + task.success
                + ",\"totalTicks\":" + task.totalTicks + ",\"mineTicks\":" + task.mineTicks
                + ",\"craftTicks\":" + task.craftTicks + ",\"placeTicks\":" + task.placeTicks
                + ",\"deaths\":" + task.deaths + ",\"deathCauses\":\"" + task.deathCauses
                + "\",\"pathFailures\":" + task.pathFailures + ",\"broken\":" + task.blocksBroken
                + ",\"placed\":" + task.blocksPlaced + ",\"durability\":" + task.durabilitySpent
                + ",\"gearTool\":\"" + task.gearTool + "\",\"gearArmor\":\"" + task.gearArmor
                + "\",\"gearReason\":\"" + esc(task.gearReason) + "\",\"bucket\":\"" + task.bucketKey
                + "\",\"biome\":\"" + task.biome + "\",\"temperature\":" + task.temperature
                + ",\"mobPressure\":\"" + task.mobPressure + "\",\"inventoryPressure\":\"" + task.inventoryPressure
                + "\",\"cues\":\"" + task.cues + "\"}";
        // Ostinato: gear score is additive. The baseline line has no gearScore field.
        if (!task.gearScored) {
            return json;
        }
        return json.substring(0, json.length() - 1) + ",\"gearScore\":" + task.gearScore + "}";
    }

    private static String csvRow(TrainingResult task) {
        return String.join(",", task.runId, task.circuitId, Long.toString(task.seed), task.mode, task.modelId,
                Boolean.toString(task.success), Integer.toString(task.totalTicks), Integer.toString(task.mineTicks),
                Integer.toString(task.craftTicks), Integer.toString(task.placeTicks), Integer.toString(task.deaths),
                task.deathCauses, Integer.toString(task.pathFailures), Integer.toString(task.blocksBroken),
                Integer.toString(task.blocksPlaced), Integer.toString(task.durabilitySpent), task.gearTool,
                task.gearArmor, esc(task.gearReason).replace(',', ';'), task.bucketKey, task.biome,
                Double.toString(task.temperature), task.mobPressure, task.inventoryPressure, task.cues);
    }

    private static String esc(String raw) {
        return raw == null ? "" : raw.replace("\"", "'");
    }
}
