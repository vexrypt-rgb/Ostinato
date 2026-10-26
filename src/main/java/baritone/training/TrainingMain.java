package baritone.training;

// Ostinato: headless entry. args: [outDir] [suite|circuit|legit|full|every_block_item] [circuit] [modelId] [modelFile]

import baritone.api.training.TrainingResult;
import baritone.training.race.RaceCircuit;

import java.nio.file.Path;
import java.util.List;

public final class TrainingMain {

    private TrainingMain() {}

    public static void main(String[] args) {
        Path out = Path.of(args.length > 0 ? args[0] : "build/training");
        String which = args.length > 1 ? args[1] : "suite";
        String circuit = args.length > 2 ? args[2] : "";
        String model = args.length > 3 ? args[3] : "heuristic";
        Path file = args.length > 4 ? Path.of(args[4]) : null;
        boolean legit = "legit".equalsIgnoreCase(which);
        // Ostinato: absent property keeps the stock suite. In game, #train reads trainingGearChoice instead.
        boolean gear = Boolean.getBoolean("ostinato.trainingGearChoice");
        // Ostinato: every_block_item is not in the suite. The property is the headless switch.
        if ((RaceCircuit.ID.equals(which) || RaceCircuit.ID.equals(circuit)) && !RaceCircuit.optedIn(false)) {
            System.out.println("raceEveryBlockItem is off");
            return;
        }
        if ("full".equalsIgnoreCase(which) || "suite".equalsIgnoreCase(which) || "legit".equalsIgnoreCase(which)) {
            if (!circuit.isBlank()) {
                report(TrainingSuite.INSTANCE.run(circuit, legit, model, file, out, gear));
                return;
            }
            List<TrainingResult> all = TrainingSuite.INSTANCE.runAll(legit, model, file, out, gear);
            for (TrainingResult result : all) {
                report(result);
            }
            return;
        }
        report(TrainingSuite.INSTANCE.run(which, legit, model, file, out, gear));
    }

    private static void report(TrainingResult result) {
        String line = result.circuitId + " " + result.mode + " success=" + result.success
                + " ticks=" + result.totalTicks + " mine=" + result.mineTicks + " craft=" + result.craftTicks
                + " place=" + result.placeTicks + " gear=" + result.gearTool + " bucket=" + result.bucketKey
                + " deaths=" + result.deaths + " pathFailures=" + result.pathFailures;
        if (result.gearScored) {
            line = line + " durability=" + result.durabilitySpent + " gearScore=" + result.gearScore;
        }
        if (RaceCircuit.ID.equals(result.circuitId)) {
            line = line + " progress=" + result.actions;
        }
        System.out.println(line);
    }
}
