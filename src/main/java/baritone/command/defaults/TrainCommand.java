package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.training.TrainingHarnesses;
import baritone.api.training.TrainingResult;
import baritone.training.TrainingSuite;
import baritone.training.TrainingWorld;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Ostinato: start a headless training circuit. Does nothing while trainingTask is off. */
public class TrainCommand extends Command {

    public TrainCommand(IBaritone baritone) {
        super(baritone, "train");
        // Ostinato: register the harness seam. This does not build a world.
        TrainingHarnesses.register(TrainingSuite.INSTANCE);
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        // Ostinato: off by default, so a stray #train does not path or allocate a circuit.
        if (!Baritone.settings().trainingTask.value) {
            logDirect("trainingTask is off");
            return;
        }
        String first = args.hasAny() ? args.getString() : "suite";
        String second = args.hasAny() ? args.getString() : "";
        args.requireMax(0);
        boolean legit = Baritone.settings().trainingLegit.value || "legit".equalsIgnoreCase(first);
        if ("full".equalsIgnoreCase(first)) {
            legit = false;
        }
        String circuit = "legit".equalsIgnoreCase(first) || "full".equalsIgnoreCase(first) || "suite".equalsIgnoreCase(first)
                ? second : first;
        if ("seed".equalsIgnoreCase(circuit)) {
            try {
                circuit = TrainingWorld.circuitForSeed(Baritone.settings().trainingSeed.value);
            } catch (IllegalArgumentException ex) {
                logDirect("unknown trainingSeed " + Baritone.settings().trainingSeed.value);
                return;
            }
        }
        String model = Baritone.settings().trainingModel.value;
        String fileSetting = Baritone.settings().trainingModelFile.value;
        Path file = fileSetting == null || fileSetting.isBlank() ? null : Path.of(fileSetting);
        Path out = Path.of(Baritone.settings().trainingOutDir.value);
        // Ostinato: default off. The property lets a headless launch opt in without a settings file.
        boolean gear = Baritone.settings().trainingGearChoice.value || Boolean.getBoolean("ostinato.trainingGearChoice");
        if (circuit == null || circuit.isBlank()) {
            for (TrainingResult result : TrainingSuite.INSTANCE.runConfigured(legit, model, file, out,
                    Baritone.settings().trainingTasks.value, gear)) {
                logDirect(line(result));
            }
            return;
        }
        logDirect(line(TrainingSuite.INSTANCE.run(circuit, legit, model, file, out, gear)));
    }

    private static String line(TrainingResult result) {
        String text = result.circuitId + " " + result.mode + " success=" + result.success + " ticks=" + result.totalTicks
                + " gear=" + result.gearTool + " bucket=" + result.bucketKey;
        if (!result.gearScored) {
            return text;
        }
        return text + " deaths=" + result.deaths + " durability=" + result.durabilitySpent + " gearScore=" + result.gearScore;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("suite", "legit", "full", "seed", "log-planks", "stone-step", "night-listen", "compass-hunt", "gear-camp");
    }

    @Override
    public String getShortDesc() {
        return "Run a headless training circuit";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Resets a fixed-seed circuit world and scores mine, craft, and place.",
                "trainingTask must be on. trainingLegit restricts the bot to vision, F3 or pie, sound, and the compass needle.",
                "compass-hunt scores that needle against the full-information lodestone. Live follow also needs compassFollow.",
                "trainingGearChoice (off) tries the kits the circuit can supply and keeps the best time, death and durability score.",
                "",
                "Usage:",
                "> train",
                "> train legit night-listen",
                "> train seed",
                "> train log-planks");
    }
}
