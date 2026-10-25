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
        try {
            ITrainingModel model = TrainingModels.load(modelId, modelFile);
            return CircuitRunner.run(circuitId, legit, model, outDir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<TrainingResult> runAll(boolean legit, String modelId, Path modelFile, Path outDir) {
        return runConfigured(legit, modelId, modelFile, outDir, String.join(",", circuits()));
    }

    /** {@code taskList} is the trainingTasks setting: comma-separated circuit ids. */
    public List<TrainingResult> runConfigured(boolean legit, String modelId, Path modelFile, Path outDir, String taskList) {
        List<TrainingResult> results = new ArrayList<>();
        for (String raw : taskList.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                results.add(run(id, legit, modelId, modelFile, outDir));
            }
        }
        return results;
    }
}
