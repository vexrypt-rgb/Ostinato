package baritone.training.race;

// Ostinato: opt-in every_block_item circuit. Off, nothing here runs and pathing is unchanged.
// Plays the existing stone-step circuit for the one wired interaction, then records a RaceRun.

import baritone.agent.JsonlRaceRecorder;
import baritone.api.training.ITrainingModel;
import baritone.api.training.TrainingResult;
import baritone.training.CircuitRunner;
import baritone.training.TrainingModels;

import java.io.IOException;
import java.nio.file.Path;

public final class RaceCircuit {

    public static final String ID = "every_block_item";

    /** Recorded on the race row. Not a TrainingWorld layout and not part of the default suite. */
    public static final long SEED = 71006L;

    /** Headless opt-in. In game the setting {@code raceEveryBlockItem} is the switch. */
    public static final String PROPERTY = "ostinato.raceEveryBlockItem";

    private RaceCircuit() {}

    public static boolean optedIn(boolean setting) {
        return setting || Boolean.getBoolean(PROPERTY);
    }

    /**
     * Enumerates the registry, plays stone-step, and credits {@code crafting.use} when that craft happened.
     * TODO: enchant (15 bookshelves, level 30, one enchant), netherite upgrade, and the other interactions
     * stay at 0 until {@link RaceProgress} hooks fill them.
     */
    public static TrainingResult execute(boolean legit, String modelId, Path modelFile, Path outDir) throws IOException {
        CollectableCatalog catalog = CollectableCatalog.fromRegistry();
        ITrainingModel model = TrainingModels.load(modelId, modelFile);
        TrainingResult stone = CircuitRunner.run("stone-step", legit, model, null);
        RaceProgress progress = RaceProgress.start(catalog);
        for (String step : RaceStages.fromTraining(stone)) {
            progress.complete(RaceStages.INTERACT, step);
        }
        progress.addFailures(stone.pathFailures);
        progress.addFailures(stone.deaths);
        progress.setTimeTicks(stone.totalTicks);
        RaceScore score = progress.score();
        String mode = legit ? "LEGIT" : "FULL";
        String runId = ID + "-" + SEED + "-" + mode + "-" + model.id();
        if (outDir != null) {
            new JsonlRaceRecorder(outDir.resolve("races.jsonl")).record(
                    score.toRun(runId, "harness", "local", model.id()));
        }
        int ticks = score.timeTicks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) score.timeTicks;
        return new TrainingResult(runId, ID, SEED, mode, model.id(), score.passed(), ticks, stone.mineTicks,
                stone.craftTicks, stone.placeTicks, stone.deaths, stone.deathCauses, score.failures, 0, 0, 0,
                "NONE", "NONE", "", "NONE|WALK|FLAT|NONE|NONE", "NONE", 0.0, "NONE", "NONE", "", score.summary());
    }
}
