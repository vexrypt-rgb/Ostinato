package baritone.api.training;

import java.nio.file.Path;
import java.util.List;

/**
 * Ostinato: seam for a later task matrix (TenorClef or otherwise). This module does not reference TenorClef.
 * {@code modelFile} may be null. The model id selects the policy.
 */
public interface ITrainingHarness {

    List<String> circuits();

    TrainingResult run(String circuitId, boolean legit, String modelId, Path modelFile, Path outDir);
}
