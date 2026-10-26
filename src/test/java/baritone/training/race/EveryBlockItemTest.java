package baritone.training.race;

// Ostinato: enumeration, score math, stage list, opt-in default off, and the cleanup keep-list.

import baritone.agent.JsonlRaceRecorder;
import baritone.agent.RaceWorldCleanup;
import baritone.api.agent.RaceRun;
import baritone.api.training.TrainingResult;
import baritone.training.TrainingMain;
import baritone.training.TrainingSuite;
import baritone.training.TrainingWorld;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EveryBlockItemTest {

    @Test
    public void stagesAndScoreMath() throws Exception {
        assertEquals(List.of("items", "blocks", "enchant", "netherite", "interact"), RaceStages.ORDER);
        assertEquals(List.of("enchanting_table", "bookshelves", "lapis", "levels", "enchant"), RaceStages.ENCHANT_STEPS);
        assertEquals(List.of("ancient_debris", "netherite_scrap", "netherite_ingot",
                "netherite_upgrade_smithing_template", "upgrade"), RaceStages.NETHERITE_STEPS);
        assertEquals(21, RaceStages.steps(RaceStages.INTERACT).size());
        assertTrue(RaceStages.steps(RaceStages.INTERACT).contains(RaceStages.WIRED_CRAFTING_USE));
        assertTrue(RaceStages.steps(RaceStages.INTERACT).contains("door.place"));
        assertTrue(RaceStages.steps(RaceStages.INTERACT).contains("bed.use"));
        assertEquals(List.of(), RaceStages.steps(RaceStages.ITEMS));
        assertEquals(List.of(RaceStages.WIRED_CRAFTING_USE), RaceStages.fromTraining(row("stone-step", 41)));
        assertEquals(List.of(), RaceStages.fromTraining(row("log-planks", 15)));
        assertEquals(List.of(), RaceStages.fromTraining(row("stone-step", -1)));
        assertEquals(List.of(), RaceStages.fromTraining(null));

        CollectableCatalog catalog = CollectableCatalog.of(
                List.of("diamond", "minecraft:oak_planks", "bedrock", "air", ""),
                List.of("stone", "oak_door", "barrier", "command_block"));
        assertEquals(List.of("diamond", "oak_planks"), catalog.items());
        assertEquals(List.of("oak_door", "stone"), catalog.blocks());
        assertFalse(catalog.hasItem("bedrock"));
        assertFalse(catalog.hasBlock("barrier"));

        RaceProgress progress = RaceProgress.start(catalog);
        progress.collectItem("diamond");
        progress.collectItem("DIAMOND");
        progress.collectItem("minecraft:oak_planks");
        progress.collectItem("bedrock");
        progress.collectBlock("stone");
        progress.collectBlock("not_a_block");
        progress.complete(RaceStages.INTERACT, "door.place");
        progress.complete(RaceStages.INTERACT, "Door.Place");
        progress.complete(RaceStages.INTERACT, "nope");
        progress.complete(RaceStages.ITEMS, "diamond");
        progress.complete(RaceStages.NETHERITE, "ancient_debris");
        progress.complete(RaceStages.NETHERITE, "netherite_scrap");
        progress.addFailures(2);
        progress.setTimeTicks(40);
        RaceScore score = progress.score();
        assertEquals(2, score.stage("items").collected);
        assertEquals(2, score.stage("items").total);
        assertEquals(1, score.stage("blocks").collected);
        assertEquals(0, score.stage("enchant").collected);
        assertEquals(5, score.stage("enchant").total);
        assertEquals(2, score.stage("netherite").collected);
        assertEquals(1, score.stage("interact").collected);
        assertEquals(21, score.stage("interact").total);
        assertEquals(6, score.collected);
        assertEquals(35, score.total);
        assertEquals(2, score.failures);
        assertEquals(40L, score.timeTicks);
        assertEquals(6.0 / 35.0, score.fraction(), 1e-9);
        assertFalse(score.passed());
        assertEquals("incomplete", score.outcome());
        assertEquals("6/35 items=2/2 blocks=1/2 enchant=0/5 netherite=2/5 interact=1/21 failures=2 time=40",
                score.summary());

        RaceRun run = score.toRun("every_block_item-71006-FULL-heuristic", "harness", "local", "heuristic");
        assertEquals("collect every block and every item", run.hypothesis);
        assertEquals("6", run.metrics.get("collected"));
        assertEquals("35", run.metrics.get("total"));
        assertEquals("6/35", run.metrics.get("progress"));
        assertEquals("40", run.metrics.get("timeTicks"));
        assertEquals("2", run.metrics.get("failures"));
        assertEquals("2/2", run.metrics.get("stage.items"));
        assertEquals("1/21", run.metrics.get("stage.interact"));
        assertEquals("ancient_debris,netherite_scrap,door.place", run.metrics.get("credited"));
        assertEquals("incomplete", run.outcome);
        Path dir = Files.createTempDirectory("ostinato-race-score");
        new JsonlRaceRecorder(dir.resolve("races.jsonl")).record(run);
        String line = Files.readString(dir.resolve("races.jsonl"), StandardCharsets.UTF_8);
        assertTrue(line.contains("\"circuit\":\"every_block_item\""));
        assertTrue(line.contains("\"stage.netherite\":\"2/5\""));
        assertTrue(line.contains("\"outcome\":\"incomplete\""));
        assertFalse(line.contains("apiKey"));

        try {
            progress.addFailures(-1);
            fail("negative failures");
        } catch (IllegalArgumentException expected) {
            assertEquals("failures", expected.getMessage());
        }

        CollectableCatalog both = CollectableCatalog.of(
                List.of("ancient_debris", "enchanting_table"),
                List.of("ancient_debris", "enchanting_table"));
        RaceProgress noted = RaceProgress.start(both);
        noted.noteObtained("minecraft:ancient_debris");
        noted.noteObtained("ancient_debris");
        noted.noteObtained("enchanting_table");
        RaceScore notedScore = noted.score();
        assertEquals(2, notedScore.stage("items").collected);
        assertEquals(2, notedScore.stage("blocks").collected);
        assertEquals(1, notedScore.stage("netherite").collected);
        assertEquals(0, notedScore.stage("enchant").collected);
        assertEquals(List.of("ancient_debris"), notedScore.credited);

        RaceProgress done = RaceProgress.start(catalog);
        done.collectItem("diamond");
        done.collectItem("oak_planks");
        done.collectBlock("stone");
        done.collectBlock("oak_door");
        for (String step : RaceStages.ENCHANT_STEPS) {
            done.complete(RaceStages.ENCHANT, step);
        }
        for (String step : RaceStages.NETHERITE_STEPS) {
            done.complete(RaceStages.NETHERITE, step);
        }
        for (String step : RaceStages.steps(RaceStages.INTERACT)) {
            done.complete(RaceStages.INTERACT, step);
        }
        done.setTimeTicks(41);
        RaceScore pass = done.score();
        assertEquals(35, pass.collected);
        assertEquals(35, pass.total);
        assertEquals(0, pass.failures);
        assertTrue(pass.passed());
        assertEquals("pass", pass.outcome());
    }

    @Test
    public void optInIsOffAndStockSuiteStays() throws Exception {
        System.clearProperty(RaceCircuit.PROPERTY);
        assertFalse(RaceCircuit.optedIn(false));
        String settings = Files.readString(project("src/api/java/baritone/api/Settings.java"), StandardCharsets.UTF_8);
        assertTrue(settings.contains("raceEveryBlockItem = new Setting<>(false)"));
        assertTrue(settings.contains("trainingTasks = new Setting<>(\"log-planks,stone-step,night-listen,compass-hunt\")"));
        assertFalse(Arrays.asList(TrainingWorld.suiteIds()).contains(RaceCircuit.ID));
        assertFalse(TrainingSuite.INSTANCE.circuits().contains(RaceCircuit.ID));
        try {
            TrainingWorld.circuitForSeed("71006");
            fail("seed");
        } catch (IllegalArgumentException expected) {
            assertEquals("71006", expected.getMessage());
        }

        Path out = Files.createTempDirectory("ostinato-race-off");
        try {
            TrainingSuite.INSTANCE.run(RaceCircuit.ID, false, "heuristic", null, out);
            fail("off");
        } catch (IllegalStateException expected) {
            assertEquals("raceEveryBlockItem is off", expected.getMessage());
        }
        assertFalse(Files.exists(out.resolve("races.jsonl")));

        List<TrainingResult> kept = TrainingSuite.INSTANCE.runConfigured(false, "heuristic", null, out,
                "log-planks,every_block_item", false, false);
        assertEquals(1, kept.size());
        assertEquals("log-planks", kept.get(0).circuitId);
        assertEquals(21, kept.get(0).totalTicks);
        assertTrue(Files.exists(out.resolve("runs.jsonl")));
        assertFalse(Files.exists(out.resolve("races.jsonl")));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            TrainingMain.main(new String[]{out.toString(), RaceCircuit.ID});
        } finally {
            System.setOut(previous);
        }
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("raceEveryBlockItem is off"));
        assertFalse(Files.exists(out.resolve("races.jsonl")));

        for (String rel : new String[]{
                "src/test/java/baritone/pathing/calc/PathBench.java",
                "src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java",
                "src/main/java/baritone/pathing/decide/PathDecisionMatrix.java"}) {
            String src = Files.readString(project(rel), StandardCharsets.UTF_8);
            assertFalse(rel, src.contains("every_block_item") || src.contains("raceEveryBlockItem")
                    || src.contains("CollectableCatalog"));
        }
        String command = Files.readString(project("src/main/java/baritone/command/defaults/TrainCommand.java"),
                StandardCharsets.UTF_8);
        assertTrue(command.contains("raceEveryBlockItem is off"));
        assertTrue(command.contains("every_block_item"));

        RaceWorldCleanup cleanup = RaceWorldCleanup.INSTANCE;
        assertTrue(cleanup.keep("vexflat"));
        assertTrue(cleanup.keep("VexFlat-26.3"));
        assertTrue(cleanup.keep("pk_hh"));
        assertTrue(cleanup.keep("pkrun_1"));
        assertTrue(cleanup.keep("SigilTest"));
        assertTrue(cleanup.keep("gs_world"));
        assertFalse(cleanup.isRaceWorld("vexflat"));
        assertTrue(cleanup.isRaceWorld("race_old"));
        assertTrue(cleanup.isRaceWorld("hyp_a"));
        assertTrue(cleanup.isRaceWorld("agentrun_b"));
        assertEquals(List.of("agentrun_b", "hyp_a", "race_old"), cleanup.plan(List.of(
                "vexflat", "pk_hh", "SigilTest", "gs_world", "race_old", "hyp_a", "agentrun_b")));

        String readme = Files.readString(project("docs/race/README.md"), StandardCharsets.UTF_8);
        for (String id : CollectableCatalog.EXCLUDED) {
            assertTrue(id, readme.contains("`" + id + "`"));
        }
        assertTrue(readme.contains("crafting.use"));
        assertTrue(readme.contains("ostinato.raceEveryBlockItem"));
        assertTrue(readme.contains("races.jsonl"));
    }

    @Test
    public void registryDropsExclusionsAndOneRaceCreditsTheTable() throws Exception {
        System.clearProperty(RaceCircuit.PROPERTY);
        CollectableCatalog catalog = CollectableCatalog.fromRegistry();
        assertTrue(catalog.itemCount() > 400);
        assertTrue(catalog.blockCount() > 200);
        for (String id : CollectableCatalog.EXCLUDED) {
            assertFalse(id, catalog.hasItem(id));
            assertFalse(id, catalog.hasBlock(id));
        }
        assertTrue(catalog.hasItem("diamond"));
        assertTrue(catalog.hasItem("lapis_lazuli"));
        assertTrue(catalog.hasItem("netherite_scrap"));
        assertTrue(catalog.hasItem("netherite_ingot"));
        assertTrue(catalog.hasItem("netherite_upgrade_smithing_template"));
        assertTrue(catalog.hasItem("water_bucket"));
        assertTrue(catalog.hasItem("pig_spawn_egg"));
        assertTrue(catalog.hasItem("light_blue_wool"));
        assertTrue(catalog.hasItem("barrel"));
        assertTrue(catalog.hasBlock("stone"));
        assertTrue(catalog.hasBlock("ancient_debris"));
        assertTrue(catalog.hasBlock("enchanting_table"));
        assertTrue(catalog.hasBlock("crafting_table"));
        assertTrue(catalog.hasBlock("oak_door"));
        assertTrue(catalog.hasBlock("oak_button"));
        assertTrue(catalog.hasBlock("lever"));
        assertTrue(catalog.hasBlock("chest"));
        assertTrue(catalog.hasBlock("furnace"));
        assertTrue(catalog.hasBlock("white_bed"));
        assertTrue(catalog.hasBlock("light_blue_wool"));
        assertTrue(catalog.hasBlock("barrel"));
        assertFalse(catalog.hasBlock("water"));
        assertFalse(catalog.hasBlock("nether_portal"));
        assertFalse(catalog.hasItem("light"));
        assertFalse(catalog.hasBlock("light"));

        Path out = Files.createTempDirectory("ostinato-race-on");
        System.setProperty(RaceCircuit.PROPERTY, "true");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            assertTrue(RaceCircuit.optedIn(false));
            TrainingResult raced = TrainingSuite.INSTANCE.run(RaceCircuit.ID, false, "heuristic", null, out, true, true);
            assertEquals(RaceCircuit.ID, raced.circuitId);
            assertEquals(RaceCircuit.SEED, raced.seed);
            assertFalse(raced.gearScored);
            assertFalse(raced.success);
            assertEquals(65, raced.totalTicks);
            assertEquals(10, raced.mineTicks);
            assertEquals(41, raced.craftTicks);
            assertEquals(65, raced.placeTicks);
            assertEquals(0, raced.deaths);
            assertEquals(0, raced.pathFailures);
            assertTrue(raced.actions.contains("items=0/"));
            assertTrue(raced.actions.contains("blocks=0/"));
            assertTrue(raced.actions.contains("enchant=0/5"));
            assertTrue(raced.actions.contains("netherite=0/5"));
            assertTrue(raced.actions.contains("interact=1/21"));
            assertTrue(raced.actions.contains("failures=0"));
            assertTrue(raced.actions.contains("time=65"));
            assertFalse(Files.exists(out.resolve("runs.jsonl")));

            TrainingMain.main(new String[]{out.toString(), RaceCircuit.ID});
        } finally {
            System.setOut(previous);
            System.clearProperty(RaceCircuit.PROPERTY);
        }
        String printed = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("progress="));
        assertTrue(printed.contains("interact=1/21"));
        String races = Files.readString(out.resolve("races.jsonl"), StandardCharsets.UTF_8);
        assertTrue(races.contains("\"runId\":\"every_block_item-71006-FULL-heuristic\""));
        assertTrue(races.contains("\"hypothesis\":\"collect every block and every item\""));
        assertTrue(races.contains("\"stage.interact\":\"1/21\""));
        assertTrue(races.contains("\"credited\":\"crafting.use\""));
        assertTrue(races.contains("\"outcome\":\"incomplete\""));
        assertFalse(races.contains("apiKey"));
        assertEquals(2, races.strip().split("\\R").length);
    }

    private static TrainingResult row(String circuit, int craftTicks) {
        return new TrainingResult("id", circuit, 1L, "FULL", "heuristic", craftTicks >= 0, 10, 1, craftTicks, 2,
                0, "", 0, 0, 0, 0, "NONE", "NONE", "", "NONE|WALK|FLAT|NONE|NONE", "NONE", 0.0, "NONE", "NONE", "", "");
    }

    private static Path project(String rel) {
        Path path = Path.of(rel);
        return Files.exists(path) ? path : Path.of("..").resolve(rel);
    }
}
