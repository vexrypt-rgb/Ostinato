package baritone.agent;

// Ostinato: overnight score. everyBlockItem off does not read the block or item registry.

import baritone.training.race.RaceCircuit;
import baritone.training.race.RaceScore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HarnessScorer implements OvernightScorer {

    private final boolean everyBlockItem;
    private final boolean legit;
    private final String modelId;
    private final Path modelFile;

    public HarnessScorer(boolean everyBlockItem, boolean legit, String modelId, Path modelFile) {
        this.everyBlockItem = everyBlockItem;
        this.legit = legit;
        this.modelId = modelId == null || modelId.isBlank() ? "heuristic" : modelId;
        this.modelFile = modelFile;
    }

    public boolean usesCircuit() {
        return everyBlockItem;
    }

    @Override
    public OvernightScore score(HypothesisRun run, OvernightAgent.AgentTick agent, long elapsedMs) {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("variant", run.variant);
        metrics.put("world", run.world);
        metrics.put("timeMs", Long.toString(Math.max(0, elapsedMs)));
        if (agent != null && agent.status == OvernightAgent.AgentTick.Status.ERROR) {
            metrics.put("score", "n/a");
            metrics.put("circuit", "none");
            return new OvernightScore("n/a", "error", metrics);
        }
        if (!everyBlockItem) {
            metrics.put("score", "n/a");
            metrics.put("circuit", "none");
            return new OvernightScore("n/a", "incomplete", metrics);
        }
        try {
            RaceScore race = RaceCircuit.scoreCircuit(legit, modelId, modelFile);
            String text = race.collected + "/" + race.total;
            metrics.putAll(race.metrics());
            metrics.put("variant", run.variant);
            metrics.put("world", run.world);
            metrics.put("score", text);
            metrics.put("timeMs", Long.toString(Math.max(0, elapsedMs)));
            return new OvernightScore(text, race.outcome(), metrics);
        } catch (RuntimeException | IOException ex) {
            metrics.put("score", "n/a");
            metrics.put("circuit", RaceCircuit.ID);
            return new OvernightScore("n/a", "error", metrics);
        }
    }
}
