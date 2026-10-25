package baritone.training;

// Ostinato: ticks a circuit until the deposit, a death, or the cap. Does not touch pathfinding.

import baritone.api.training.ITaskView;
import baritone.api.training.ITrainingModel;
import baritone.api.training.TaskDecision;
import baritone.api.training.TrainingResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CircuitRunner {

    public static final int MAX_TICKS = 400;

    private CircuitRunner() {}

    public static TrainingResult run(String circuitId, boolean legit, ITrainingModel model, Path outDir) throws IOException {
        TrainingWorld world = TrainingWorld.create(circuitId);
        world.reset();
        String gearTool = "NONE";
        String gearArmor = "NONE";
        String gearReason = "";
        Set<String> cues = new LinkedHashSet<>();
        List<String> actions = new ArrayList<>();
        ITaskView view = Senses.observe(world, legit);
        absorb(cues, view);
        int guard = 0;
        while (!world.success() && !world.dead() && world.ticks < MAX_TICKS && guard < MAX_TICKS) {
            guard++;
            TaskDecision decision = model.decide(view);
            if (decision.gearTool != null) {
                gearTool = decision.gearTool;
                gearReason = decision.gearReason == null ? "" : decision.gearReason;
            }
            if (decision.gearArmor != null) {
                gearArmor = decision.gearArmor;
            }
            String act = decision.act == null ? "WAIT" : decision.act;
            actions.add(act);
            world.act(act);
            view = Senses.observe(world, legit);
            absorb(cues, view);
            if ("chunkBuilder".equals(view.pieTop())) {
                cues.add("PIE:chunkBuilder");
            }
        }
        boolean success = world.success() && world.pathFailures == 0;
        String runId = circuitId + "-" + world.seed + "-" + (legit ? "LEGIT" : "FULL") + "-" + model.id();
        String actionText = String.join(">", actions);
        TrainingResult result = new TrainingResult(runId, circuitId, world.seed, legit ? "LEGIT" : "FULL", model.id(),
                success, world.ticks, world.mineTicks, world.craftTicks, world.placeTicks, world.deaths, world.deathCause,
                world.pathFailures, world.broken, world.placed, world.durabilitySpent, gearTool, gearArmor, gearReason,
                world.bucketKey(), world.biome, world.temperature, world.mobPressure, world.inventoryPressure(),
                String.join(";", cues), actionText);
        if (outDir != null) {
            MetricsWriter.append(outDir, result, new PathSignals(runId, world.terrain, world.jumpMajority,
                    world.riseBlocks, world.gapBlocks));
        }
        return result;
    }

    private static void absorb(Set<String> cues, ITaskView view) {
        for (String sound : view.sounds()) {
            int at = sound.indexOf('@');
            cues.add(at < 0 ? sound : sound.substring(0, at));
        }
    }
}
