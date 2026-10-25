package baritone.training;

// Ostinato: headless entry. args: [outDir] [suite|circuit|legit|full] [circuit] [modelId] [modelFile]

import baritone.api.training.TrainingResult;

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
        if ("full".equalsIgnoreCase(which) || "suite".equalsIgnoreCase(which) || "legit".equalsIgnoreCase(which)) {
            if (!circuit.isBlank()) {
                report(TrainingSuite.INSTANCE.run(circuit, legit, model, file, out));
                return;
            }
            List<TrainingResult> all = TrainingSuite.INSTANCE.runAll(legit, model, file, out);
            for (TrainingResult result : all) {
                report(result);
            }
            return;
        }
        report(TrainingSuite.INSTANCE.run(which, legit, model, file, out));
    }

    private static void report(TrainingResult result) {
        System.out.println(result.circuitId + " " + result.mode + " success=" + result.success
                + " ticks=" + result.totalTicks + " mine=" + result.mineTicks + " craft=" + result.craftTicks
                + " place=" + result.placeTicks + " gear=" + result.gearTool + " bucket=" + result.bucketKey
                + " deaths=" + result.deaths + " pathFailures=" + result.pathFailures);
    }
}
