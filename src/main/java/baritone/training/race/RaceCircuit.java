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
     * Does not write {@code races.jsonl}. The overnight recorder writes its own line when it opts in.
     * TODO: enchant (15 bookshelves, level 30, one enchant), netherite upgrade, and the other interactions
     * stay at 0 until {@link RaceProgress} hooks fill them. The overnight runner does not collect those.
     */
    public static RaceScore scoreCircuit(boolean legit, String modelId, Path modelFile) throws IOException {
        return play(legit, modelId, modelFile).score;
    }

    /**
     * Enumerates the registry, plays stone-step, and credits {@code crafting.use} when that craft happened.
     * TODO: enchant (15 bookshelves, level 30, one enchant), netherite upgrade, and the other interactions
     * stay at 0 until {@link RaceProgress} hooks fill them.
     */
    public static TrainingResult execute(boolean legit, String modelId, Path modelFile, Path outDir) throws IOException {
        Played played = play(legit, modelId, modelFile);
        String mode = legit ? "LEGIT" : "FULL";
        String runId = ID + "-" + SEED + "-" + mode + "-" + played.model.id();
        if (outDir != null) {
            new JsonlRaceRecorder(outDir.resolve("races.jsonl")).record(
                    played.score.toRun(runId, "harness", "local", played.model.id()));
        }
        int ticks = played.score.timeTicks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) played.score.timeTicks;
        TrainingResult stone = played.stone;
        return new TrainingResult(runId, ID, SEED, mode, played.model.id(), played.score.passed(), ticks, stone.mineTicks,
                stone.craftTicks, stone.placeTicks, stone.deaths, stone.deathCauses, played.score.failures, 0, 0, 0,
                "NONE", "NONE", "", "NONE|WALK|FLAT|NONE|NONE", "NONE", 0.0, "NONE", "NONE", "", played.score.summary());
    }

    private static Played play(boolean legit, String modelId, Path modelFile) throws IOException {
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
        return new Played(progress.score(), stone, model);
    }

    private static final class Played {
        final RaceScore score;
        final TrainingResult stone;
        final ITrainingModel model;

        Played(RaceScore score, TrainingResult stone, ITrainingModel model) {
            this.score = score;
            this.stone = stone;
            this.model = model;
        }
    }
}
