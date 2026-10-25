package baritone.training;

// Ostinato: model ids and files. The runner does not name a policy class.

import baritone.api.training.ITrainingModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TrainingModels {

    private TrainingModels() {}

    /** {@code modelFile} may be null. A file may set {@code id=} and {@code delegate=heuristic}. */
    public static ITrainingModel load(String modelId, Path modelFile) throws IOException {
        String id = modelId == null || modelId.isBlank() ? "heuristic" : modelId;
        String delegate = "heuristic";
        if (modelFile != null) {
            for (String line : Files.readAllLines(modelFile)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("id=")) {
                    id = trimmed.substring(3).trim();
                } else if (trimmed.startsWith("delegate=")) {
                    delegate = trimmed.substring(9).trim();
                }
            }
        }
        if (!"heuristic".equals(delegate) || (modelFile == null && !"heuristic".equals(id))) {
            throw new IllegalArgumentException("unknown training model " + id + " delegate " + delegate);
        }
        return new HeuristicModel(id);
    }
}
