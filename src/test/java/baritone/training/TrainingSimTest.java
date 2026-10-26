package baritone.training;

import baritone.api.training.ITaskView;
import baritone.api.training.ITrainingModel;
import baritone.api.training.TaskDecision;
import baritone.api.training.TrainingResult;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: headless circuits. No Minecraft client and no pathbench. */
public class TrainingSimTest {

    @Test
    public void logPlanksThenStoneThenLegitNight() throws Exception {
        Path out = Files.createTempDirectory("ostinato-train");
        TrainingResult plains = TrainingSuite.INSTANCE.run("log-planks", false, "heuristic", null, out);
        assertTrue(plains.actions, plains.success);
        assertTrue(plains.mineTicks > 0 && plains.craftTicks > plains.mineTicks && plains.placeTicks > plains.craftTicks);
        assertEquals("HAND", plains.gearTool);
        assertEquals("NONE", plains.gearArmor);
        assertEquals(0, plains.deaths);
        assertEquals(71001L, plains.seed);
        assertEquals("stone-step", TrainingWorld.circuitForSeed("71002"));
        assertTrue(plains.bucketKey.contains("PLAINS") && plains.bucketKey.endsWith("EMPTY"));
        assertEquals(0.8, plains.temperature, 0.001);

        TrainingResult stone = TrainingSuite.INSTANCE.run("stone-step", false, "heuristic", null, out);
        assertTrue(stone.actions, stone.success);
        assertEquals("WOOD_PICK", stone.gearTool);
        assertTrue(stone.gearReason.contains("pick"));
        assertEquals(1, stone.durabilitySpent);
        assertEquals(0, stone.pathFailures);
        assertTrue(stone.bucketKey.contains("HILLS") && stone.bucketKey.contains("STEP") && stone.bucketKey.contains("MESSY"));
        assertTrue(stone.craftTicks > stone.mineTicks && stone.placeTicks > stone.craftTicks);

        Probe probe = new Probe(new HeuristicModel("heuristic"));
        TrainingResult night = CircuitRunner.run("night-listen", true, probe, out);
        assertTrue(night.actions, night.success);
        assertEquals("LEGIT", night.mode);
        assertNotNull(probe.first);
        assertEquals("FOREST", probe.first.biome());
        assertEquals(0.7, probe.first.temperature(), 0.001);
        assertTrue(probe.first.fullBlocks().isEmpty());
        assertTrue(probe.first.fullEntities().isEmpty());
        assertFalse(joined(probe.first.visible()).contains("LOG@"));
        assertTrue(joined(probe.first.sounds()).contains("MOB_AMBIENT"));
        assertTrue(joined(probe.first.sounds()).contains("WATER@"));
        assertTrue(joined(probe.first.sounds()).contains("LAVA@"));
        assertTrue(night.cues.contains("BLOCK_BREAK"));
        assertTrue(night.cues.contains("PIE:chunkBuilder"));
        assertTrue(night.cues.contains("FOOTSTEP"));
        assertEquals("NIGHT", night.mobPressure);

        Probe full = new Probe(new HeuristicModel("heuristic"));
        TrainingResult nightFull = CircuitRunner.run("night-listen", false, full, out);
        assertTrue(nightFull.success);
        assertTrue(joined(full.first.fullBlocks()).contains("LOG@"));
        assertTrue(joined(full.first.fullEntities()).contains("ZOMBIE@"));
        assertTrue(nightFull.totalTicks < night.totalTicks);

        String taskLine = Files.readAllLines(out.resolve("runs.jsonl"), StandardCharsets.UTF_8).get(0);
        assertFalse(taskLine.contains("riseBlocks"));
        String pathLine = Files.readString(out.resolve("path-signals.jsonl"), StandardCharsets.UTF_8);
        assertTrue(pathLine.contains("riseBlocks"));
        assertTrue(Files.exists(out.resolve("runs.csv")));
        Path bucket = out.resolve("buckets").resolve(plains.bucketKey.replace('|', '_') + ".jsonl");
        try {
            MetricsWriter.appendBucket(bucket, "OTHER|WALK|FLAT|DAY|EMPTY", "{\"bucket\":\"OTHER|WALK|FLAT|DAY|EMPTY\"}");
            fail("foreign bucket was written");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("refusing"));
        }
    }

    @Test
    public void compassHuntLegitFollowsTheNeedle() throws Exception {
        Path out = Files.createTempDirectory("ostinato-compass");
        Probe legit = new Probe(new HeuristicModel("heuristic"));
        TrainingResult fair = CircuitRunner.run("compass-hunt", true, legit, out);
        assertTrue(fair.actions, fair.success);
        assertEquals("LEGIT", fair.mode);
        assertEquals(71004L, fair.seed);
        assertTrue(legit.first.fullBlocks().isEmpty());
        assertTrue(legit.first.fullEntities().isEmpty());
        assertTrue(joined(legit.first.sounds()).contains("COMPASS@S"));
        assertFalse(joined(legit.first.visible()).contains("LODESTONE@"));
        assertFalse(fair.cues.contains("4,1,16"));
        assertTrue(fair.cues.contains("COMPASS"));
        assertTrue(fair.actions.startsWith("TURN_RIGHT"));
        assertEquals(12, fair.totalTicks);
        assertEquals(0, fair.pathFailures);
        assertEquals(0, fair.deaths);

        Probe full = new Probe(new HeuristicModel("heuristic"));
        TrainingResult ground = CircuitRunner.run("compass-hunt", false, full, out);
        assertTrue(ground.actions, ground.success);
        assertEquals("FULL", ground.mode);
        assertTrue(joined(full.first.fullBlocks()).contains("LODESTONE@4,1,16"));
        assertTrue(fair.totalTicks >= ground.totalTicks);

        assertEquals("compass-hunt", TrainingWorld.circuitForSeed("71004"));
    }

    @Test
    public void modelFileSuppliesTheId() throws Exception {
        Path out = Files.createTempDirectory("ostinato-train-model");
        Path file = out.resolve("model.txt");
        Files.writeString(file, "id=custom-stub\ndelegate=heuristic\n", StandardCharsets.UTF_8);
        TrainingResult result = TrainingSuite.INSTANCE.run("log-planks", false, "not-a-class", file, out);
        assertEquals("custom-stub", result.modelId);
        assertTrue(result.success);
    }

    @Test
    public void deathsAreFallVoidAndKnockoff() {
        TrainingWorld fall = TrainingWorld.create("night-listen");
        fall.debugStand(5, 1, 8, 0);
        fall.act("WALK");
        assertEquals("FALL", fall.deathCause());

        TrainingWorld knock = TrainingWorld.create("night-listen");
        knock.debugStand(5, 1, 8, 0);
        knock.actKnock(0);
        assertEquals("KNOCKOFF", knock.deathCause());

        TrainingWorld hole = TrainingWorld.create("night-listen");
        hole.debugStand(5, 1, 5, 1);
        hole.act("WALK");
        assertEquals("VOID", hole.deathCause());
    }

    @Test
    public void harnessStaysOffThePathfinderAndDefaultsOff() throws Exception {
        String settings = Files.readString(project("src/api/java/baritone/api/Settings.java"), StandardCharsets.UTF_8);
        assertTrue(settings.contains("trainingTask = new Setting<>(false)"));
        assertTrue(settings.contains("trainingLegit = new Setting<>(false)"));
        assertTrue(settings.contains("compassFollow = new Setting<>(false)"));
        for (String rel : new String[]{
                "src/test/java/baritone/pathing/calc/PathBench.java",
                "src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java",
                "src/main/java/baritone/pathing/decide/PathDecisionMatrix.java"}) {
            String src = Files.readString(project(rel), StandardCharsets.UTF_8);
            assertFalse(rel, src.contains("baritone.training"));
        }
    }

    private static String joined(List<String> tokens) {
        return String.join(" ", tokens);
    }

    private static Path project(String rel) {
        Path path = Path.of(rel);
        return Files.exists(path) ? path : Path.of("..").resolve(rel);
    }

    /** Records the first view the policy actually received. */
    private static final class Probe implements ITrainingModel {
        private final ITrainingModel inner;
        private ITaskView first;

        private Probe(ITrainingModel inner) {
            this.inner = inner;
        }

        @Override
        public String id() {
            return inner.id();
        }

        @Override
        public TaskDecision decide(ITaskView view) {
            if (first == null) {
                first = view;
            }
            return inner.decide(view);
        }
    }
}
