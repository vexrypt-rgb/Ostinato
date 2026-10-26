package baritone.training;

import baritone.api.training.TrainingResult;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: gear-choice scoring stays off the harness baseline, and the camp picks leather plus a wooden pick. */
public class GearChoiceTest {

    @Test
    public void offMatchesTheHarnessBaseline() throws Exception {
        Path out = Files.createTempDirectory("ostinato-gear-off");
        TrainingResult plains = TrainingSuite.INSTANCE.run("log-planks", false, "heuristic", null, out);
        TrainingResult stone = TrainingSuite.INSTANCE.run("stone-step", false, "heuristic", null, out);
        TrainingResult night = TrainingSuite.INSTANCE.run("night-listen", false, "heuristic", null, out);
        TrainingResult nightLegit = TrainingSuite.INSTANCE.run("night-listen", true, "heuristic", null, out);
        assertEquals(21, plains.totalTicks);
        assertEquals(65, stone.totalTicks);
        assertEquals(28, night.totalTicks);
        assertEquals(38, nightLegit.totalTicks);
        assertEquals("HAND", plains.gearTool);
        assertEquals("NONE", plains.gearArmor);
        assertEquals("WOOD_PICK", stone.gearTool);
        assertEquals(1, stone.durabilitySpent);
        assertEquals(0, plains.deaths);
        assertEquals(0, stone.deaths);
        assertFalse(plains.gearScored);
        assertEquals(0, plains.gearScore);
        String taskLine = Files.readAllLines(out.resolve("runs.jsonl"), StandardCharsets.UTF_8).get(0);
        assertFalse(taskLine.contains("gearScore"));
        assertFalse(Files.exists(out.resolve("gear-rows.jsonl")));

        TrainingResult camp = TrainingSuite.INSTANCE.run("gear-camp", false, "heuristic", null, out);
        assertFalse(camp.success);
        assertEquals(0, camp.deaths);
        assertEquals(CircuitRunner.MAX_TICKS, camp.totalTicks);
        assertFalse(Arrays.asList(TrainingWorld.suiteIds()).contains("gear-camp"));
        assertEquals("gear-camp", TrainingWorld.circuitForSeed("71005"));

        String settings = Files.readString(project("src/api/java/baritone/api/Settings.java"), StandardCharsets.UTF_8);
        assertTrue(settings.contains("trainingGearChoice = new Setting<>(false)"));
        assertTrue(settings.contains("trainingTasks = new Setting<>(\"log-planks,stone-step,night-listen,compass-hunt\")"));
        for (String rel : new String[]{
                "src/test/java/baritone/pathing/calc/PathBench.java",
                "src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java",
                "src/main/java/baritone/pathing/decide/PathDecisionMatrix.java"}) {
            String src = Files.readString(project(rel), StandardCharsets.UTF_8);
            assertFalse(rel, src.contains("GearChoice") || src.contains("trainingGearChoice"));
        }
    }

    @Test
    public void onKeepsStockCircuitsAndScoresTheCamp() throws Exception {
        assertEquals(1000, GearChoice.DEATH_WEIGHT);
        assertEquals(2, GearChoice.DURABILITY_WEIGHT);
        Path offDir = Files.createTempDirectory("ostinato-gear-base");
        Path onDir = Files.createTempDirectory("ostinato-gear-on");
        for (String id : new String[]{"log-planks", "stone-step"}) {
            TrainingResult off = TrainingSuite.INSTANCE.run(id, false, "heuristic", null, offDir);
            TrainingResult on = TrainingSuite.INSTANCE.run(id, false, "heuristic", null, onDir, true);
            assertSameRun(off, on);
        }
        TrainingResult nightOff = TrainingSuite.INSTANCE.run("night-listen", true, "heuristic", null, offDir);
        TrainingResult nightOn = TrainingSuite.INSTANCE.run("night-listen", true, "heuristic", null, onDir, true);
        assertSameRun(nightOff, nightOn);

        List<String> offJson = Files.readAllLines(offDir.resolve("runs.jsonl"), StandardCharsets.UTF_8);
        List<String> onJson = Files.readAllLines(onDir.resolve("runs.jsonl"), StandardCharsets.UTF_8);
        assertEquals(offJson.size(), onJson.size());
        for (int i = 0; i < offJson.size(); i++) {
            assertEquals(offJson.get(i), onJson.get(i).replaceAll(",\"gearScore\":\\d+}", "}"));
            assertTrue(onJson.get(i).contains("gearScore"));
        }
        assertEquals(Files.readString(offDir.resolve("runs.csv"), StandardCharsets.UTF_8),
                Files.readString(onDir.resolve("runs.csv"), StandardCharsets.UTF_8));
        assertEquals(Files.readString(offDir.resolve("path-signals.jsonl"), StandardCharsets.UTF_8),
                Files.readString(onDir.resolve("path-signals.jsonl"), StandardCharsets.UTF_8));

        List<String> stockRows = Files.readAllLines(onDir.resolve("gear-rows.jsonl"), StandardCharsets.UTF_8);
        assertEquals(4, stockRows.size()); // plains 1, stone 2, legit night 1
        assertTrue(chosen(stockRows, "log-planks").contains("\"tool\":\"HAND\""));
        assertTrue(chosen(stockRows, "stone-step").contains("\"tool\":\"WOOD_PICK\""));
        assertTrue(chosen(stockRows, "stone-step").contains("\"armor\":\"NONE\""));

        Path campDir = Files.createTempDirectory("ostinato-gear-camp");
        TrainingResult camp = TrainingSuite.INSTANCE.run("gear-camp", false, "heuristic", null, campDir, true);
        assertTrue(camp.actions, camp.success);
        assertEquals(0, camp.deaths);
        assertEquals(1, camp.durabilitySpent);
        assertEquals("WOOD_PICK", camp.gearTool);
        assertEquals("LEATHER", camp.gearArmor);
        assertTrue(camp.gearScored);
        assertEquals(GearChoice.score(camp.totalTicks, camp.deaths, camp.durabilitySpent), camp.gearScore);
        assertTrue(camp.gearReason.contains("locker"));

        List<String> rows = Files.readAllLines(campDir.resolve("gear-rows.jsonl"), StandardCharsets.UTF_8);
        assertEquals(16, rows.size());
        String leather = row(rows, "WOOD_PICK", "LEATHER");
        String naked = row(rows, "WOOD_PICK", "NONE");
        assertTrue(leather.contains("\"chosen\":true"));
        assertEquals(1, countChosen(rows));
        int leatherTime = field(leather, "time");
        int nakedTime = field(naked, "time");
        int nakedDeaths = field(naked, "deaths");
        assertTrue(nakedDeaths >= 1);
        assertTrue(nakedTime < leatherTime);
        assertTrue(field(leather, "score") < field(naked, "score"));
        assertEquals(1, field(leather, "durability"));
        assertEquals(GearChoice.score(leatherTime, 0, 1), field(leather, "score"));

        TrainingResult nakedPlay = CircuitRunner.play("gear-camp", false, new HeuristicModel("heuristic"), null,
                GearChoice.Equip.of("WOOD_PICK", "NONE"));
        assertEquals("KNOCKOFF", nakedPlay.deathCauses);
        assertFalse(nakedPlay.success);

        TrainingResult legit = TrainingSuite.INSTANCE.run("gear-camp", true, "heuristic", null, campDir, true);
        assertTrue(legit.actions, legit.success);
        assertEquals("WOOD_PICK", legit.gearTool);
        assertEquals("LEATHER", legit.gearArmor);
        assertEquals(0, legit.deaths);
    }

    private static void assertSameRun(TrainingResult off, TrainingResult on) {
        assertEquals(off.actions, on.actions);
        assertEquals(off.totalTicks, on.totalTicks);
        assertEquals(off.mineTicks, on.mineTicks);
        assertEquals(off.craftTicks, on.craftTicks);
        assertEquals(off.placeTicks, on.placeTicks);
        assertEquals(off.deaths, on.deaths);
        assertEquals(off.deathCauses, on.deathCauses);
        assertEquals(off.durabilitySpent, on.durabilitySpent);
        assertEquals(off.gearTool, on.gearTool);
        assertEquals(off.gearArmor, on.gearArmor);
        assertEquals(off.gearReason, on.gearReason);
        assertEquals(off.success, on.success);
        assertEquals(off.pathFailures, on.pathFailures);
        assertEquals(off.blocksBroken, on.blocksBroken);
        assertEquals(off.blocksPlaced, on.blocksPlaced);
        assertTrue(on.gearScored);
        assertEquals(GearChoice.score(on.totalTicks, on.deaths, on.durabilitySpent), on.gearScore);
    }

    private static String chosen(List<String> rows, String circuit) {
        String found = null;
        for (String row : rows) {
            if (row.contains("\"circuit\":\"" + circuit + "\"") && row.contains("\"chosen\":true")) {
                assertNull(found);
                found = row;
            }
        }
        assertNotNull(circuit, found);
        return found;
    }

    private static String row(List<String> rows, String tool, String armor) {
        for (String row : rows) {
            if (row.contains("\"tool\":\"" + tool + "\"") && row.contains("\"armor\":\"" + armor + "\"")) {
                return row;
            }
        }
        fail("missing " + tool + " " + armor);
        return "";
    }

    private static int countChosen(List<String> rows) {
        int n = 0;
        for (String row : rows) {
            if (row.contains("\"chosen\":true")) {
                n++;
            }
        }
        return n;
    }

    private static int field(String json, String name) {
        String key = "\"" + name + "\":";
        int i = json.indexOf(key);
        assertTrue(json, i >= 0);
        int j = i + key.length();
        int k = j;
        while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-')) {
            k++;
        }
        return Integer.parseInt(json.substring(j, k));
    }

    private static Path project(String rel) {
        Path path = Path.of(rel);
        return Files.exists(path) ? path : Path.of("..").resolve(rel);
    }
}
