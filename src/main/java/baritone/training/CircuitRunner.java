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
        return play(circuitId, legit, model, outDir, null);
    }

    /**
     * Ostinato: {@code equip == null} is the stock circuit, the same actions and files as the harness baseline.
     * A locker kit spends its equip ticks first, then the same policy runs with that tool and armor.
     */
    static TrainingResult play(String circuitId, boolean legit, ITrainingModel model, Path outDir, GearChoice.Equip equip)
            throws IOException {
        TrainingWorld world = TrainingWorld.create(circuitId);
        world.reset();
        if (equip != null) {
            world.equip(equip);
            for (int i = 0; i < equip.ticks; i++) {
                world.act("WAIT");
            }
        }
        String gearTool = equip == null ? "NONE" : equip.tool;
        String gearArmor = equip == null ? "NONE" : equip.armor;
        String gearReason = equip == null ? "" : equip.reason;
        Set<String> cues = new LinkedHashSet<>();
        List<String> actions = new ArrayList<>();
        ITaskView view = Senses.observe(world, legit);
        absorb(cues, view);
        int guard = 0;
        while (!world.success() && !world.dead() && world.ticks < MAX_TICKS && guard < MAX_TICKS) {
            guard++;
            TaskDecision decision = model.decide(view);
            if (equip == null && decision.gearTool != null) {
                gearTool = decision.gearTool;
                gearReason = decision.gearReason == null ? "" : decision.gearReason;
            }
            if (equip == null && decision.gearArmor != null) {
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
