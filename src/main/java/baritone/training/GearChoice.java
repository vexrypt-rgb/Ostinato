package baritone.training;

// Ostinato: gear-choice scoring. The harness tries kits the circuit can supply and keeps the best
// score of time, deaths and durability. Weights belong to this harness.

import baritone.api.training.ITrainingModel;
import baritone.api.training.TrainingResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class GearChoice {

    /** A death costs more than finishing the circuit. */
    public static final int DEATH_WEIGHT = 1000;
    /** One point of tool wear. Small next to a death, visible next to a few ticks. */
    public static final int DURABILITY_WEIGHT = 2;

    private static final String[] TOOLS = {"HAND", "WOOD_PICK", "STONE_PICK", "IRON_PICK"};
    private static final String[] ARMORS = {"NONE", "LEATHER", "IRON", "DIAMOND"};

    private GearChoice() {}

    @FunctionalInterface
    interface FreshModel {
        ITrainingModel create() throws IOException;
    }

    /** One locker loadout. {@code ticks} are waits before the first decision. */
    static final class Equip {
        final String tool;
        final String armor;
        final int ticks;
        final String reason;

        private Equip(String tool, String armor, int ticks, String reason) {
            this.tool = tool;
            this.armor = armor;
            this.ticks = ticks;
            this.reason = reason;
        }

        static Equip of(String tool, String armor) {
            return new Equip(tool, armor, equipTicks(tool, armor),
                    "locker " + tool + " / " + armor + " scored on time deaths and durability");
        }
    }

    static final class Row {
        final String runId;
        final String circuitId;
        final String mode;
        final String tool;
        final String armor;
        final int time;
        final int deaths;
        final int durability;
        final int score;
        final boolean success;
        boolean chosen;

        Row(TrainingResult result, String tool, String armor, int score) {
            this.runId = result.runId;
            this.circuitId = result.circuitId;
            this.mode = result.mode;
            this.tool = tool;
            this.armor = armor;
            this.time = result.totalTicks;
            this.deaths = result.deaths;
            this.durability = result.durabilitySpent;
            this.score = score;
            this.success = result.success;
        }
    }

    public static int score(int ticks, int deaths, int durability) {
        return ticks + deaths * DEATH_WEIGHT + durability * DURABILITY_WEIGHT;
    }

    /** Break ticks for one block. Negative means this tool cannot break it. */
    static int breakTicks(String tool, byte block) {
        boolean log = block == TrainingWorld.LOG;
        return switch (tool) {
            case "WOOD_PICK" -> log ? 8 : 6;
            case "STONE_PICK" -> log ? 5 : 4;
            case "IRON_PICK" -> log ? 3 : 2;
            default -> log ? 8 : -1;
        };
    }

    static int pickDurability(String tool) {
        return switch (tool) {
            case "WOOD_PICK" -> 59;
            case "STONE_PICK" -> 131;
            case "IRON_PICK" -> 250;
            default -> -1;
        };
    }

    static int armorRank(String armor) {
        return switch (armor) {
            case "LEATHER" -> 1;
            case "IRON" -> 2;
            case "DIAMOND" -> 3;
            default -> 0;
        };
    }

    static int equipTicks(String tool, String armor) {
        int toolCost = switch (tool) {
            case "WOOD_PICK" -> 4;
            case "STONE_PICK" -> 14;
            case "IRON_PICK" -> 22;
            default -> 0;
        };
        int armorCost = switch (armor) {
            case "LEATHER" -> 6;
            case "IRON" -> 18;
            case "DIAMOND" -> 30;
            default -> 0;
        };
        return toolCost + armorCost;
    }

    static boolean legal(boolean locker, String stock, String tool, String armor) {
        if (locker) {
            return true;
        }
        if (!"NONE".equals(armor)) {
            return false;
        }
        return "HAND".equals(tool) || tool.equals(stock);
    }

    /**
     * Play every legal kit. The published result is the lowest score. A stock kit is played with no locker
     * grant, so a circuit that already crafts that tool stays on the baseline.
     */
    public static TrainingResult score(String circuitId, boolean legit, FreshModel fresh, Path outDir) throws IOException {
        TrainingWorld probe = TrainingWorld.create(circuitId);
        String stock = probe.stockTool();
        boolean locker = probe.hasLocker();
        List<Row> rows = new ArrayList<>();
        TrainingResult bestResult = null;
        int bestScore = Integer.MAX_VALUE;
        int bestIndex = -1;
        for (String tool : TOOLS) {
            for (String armor : ARMORS) {
                if (!legal(locker, stock, tool, armor)) {
                    continue;
                }
                boolean identity = tool.equals(stock) && "NONE".equals(armor);
                Equip equip = identity ? null : Equip.of(tool, armor);
                TrainingResult result = CircuitRunner.play(circuitId, legit, fresh.create(), null, equip);
                int value = score(result.totalTicks, result.deaths, result.durabilitySpent);
                rows.add(new Row(result, tool, armor, value));
                if (value < bestScore) {
                    bestScore = value;
                    bestResult = result;
                    bestIndex = rows.size() - 1;
                }
            }
        }
        if (bestResult == null) {
            throw new IllegalStateException(circuitId);
        }
        rows.get(bestIndex).chosen = true;
        TrainingResult published = bestResult.withGearScore(bestScore);
        if (outDir != null) {
            MetricsWriter.append(outDir, published, new PathSignals(published.runId, probe.terrain, probe.jumpMajority,
                    probe.riseBlocks, probe.gapBlocks));
            writeRows(outDir, rows);
        }
        return published;
    }

    private static void writeRows(Path dir, List<Row> rows) throws IOException {
        Files.createDirectories(dir);
        StringBuilder buf = new StringBuilder();
        for (Row row : rows) {
            buf.append("{\"runId\":\"").append(row.runId)
                    .append("\",\"circuit\":\"").append(row.circuitId)
                    .append("\",\"mode\":\"").append(row.mode)
                    .append("\",\"tool\":\"").append(row.tool)
                    .append("\",\"armor\":\"").append(row.armor)
                    .append("\",\"time\":").append(row.time)
                    .append(",\"deaths\":").append(row.deaths)
                    .append(",\"durability\":").append(row.durability)
                    .append(",\"score\":").append(row.score)
                    .append(",\"success\":").append(row.success)
                    .append(",\"chosen\":").append(row.chosen)
                    .append("}").append(System.lineSeparator());
        }
        Files.writeString(dir.resolve("gear-rows.jsonl"), buf.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
