package baritone.training;

// Ostinato: runs the fixed circuits. Each circuit gets a fresh model so gear state does not leak.

import baritone.api.training.ITrainingHarness;
import baritone.api.training.ITrainingModel;
import baritone.api.training.TrainingResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TrainingSuite implements ITrainingHarness {

    public static final TrainingSuite INSTANCE = new TrainingSuite();

    private TrainingSuite() {}

    @Override
    public List<String> circuits() {
        return Arrays.asList(TrainingWorld.suiteIds());
    }

    @Override
    public TrainingResult run(String circuitId, boolean legit, String modelId, Path modelFile, Path outDir) {
        return run(circuitId, legit, modelId, modelFile, outDir, false);
    }

    /**
     * Ostinato: {@code gearChoice} false is the interface method, one stock run.
     * True scores every kit the circuit can supply.
     */
    public TrainingResult run(String circuitId, boolean legit, String modelId, Path modelFile, Path outDir, boolean gearChoice) {
        try {
            if (!gearChoice) {
                ITrainingModel model = TrainingModels.load(modelId, modelFile);
                return CircuitRunner.run(circuitId, legit, model, outDir);
            }
            return GearChoice.score(circuitId, legit, () -> TrainingModels.load(modelId, modelFile), outDir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<TrainingResult> runAll(boolean legit, String modelId, Path modelFile, Path outDir) {
        return runConfigured(legit, modelId, modelFile, outDir, String.join(",", circuits()));
    }

    public List<TrainingResult> runAll(boolean legit, String modelId, Path modelFile, Path outDir, boolean gearChoice) {
        return runConfigured(legit, modelId, modelFile, outDir, String.join(",", circuits()), gearChoice);
    }

    /** {@code taskList} is the trainingTasks setting: comma-separated circuit ids. */
    public List<TrainingResult> runConfigured(boolean legit, String modelId, Path modelFile, Path outDir, String taskList) {
        return runConfigured(legit, modelId, modelFile, outDir, taskList, false);
    }

    public List<TrainingResult> runConfigured(boolean legit, String modelId, Path modelFile, Path outDir, String taskList,
            boolean gearChoice) {
        List<TrainingResult> results = new ArrayList<>();
        for (String raw : taskList.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                results.add(run(id, legit, modelId, modelFile, outDir, gearChoice));
            }
        }
        return results;
    }
}
