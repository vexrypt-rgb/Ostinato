package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.pathing.CostCalibration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ostinato: {@code #timings} prints, per movement family, how many movements finished and the mean ratio of
 * ticks taken to ticks planned. Read-only; {@code #timings reset} clears the samples (and so the scales).
 */
public class TimingsCommand extends Command {

    public TimingsCommand(IBaritone baritone) {
        super(baritone, "timings");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (args.hasAny()) {
            String sub = args.getString();
            if (!sub.equalsIgnoreCase("reset")) {
                logDirect("Usage: #timings [reset]");
                return;
            }
            CostCalibration.reset();
            logDirect("Movement timings cleared");
            return;
        }
        List<String> lines = lines();
        if (lines.isEmpty()) {
            logDirect("No movement timings yet. Walk a path first.");
            return;
        }
        logDirect("family: samples, actual/planned ticks, scale");
        lines.forEach(this::logDirect);
    }

    /** Sorted by family name so the output is stable. */
    static List<String> lines() {
        List<String[]> rows = new ArrayList<>();
        CostCalibration.forEach((family, n, mean) -> rows.add(new String[]{family,
                String.format("%s: %d, %.3f, %.3f", family, n, mean, CostCalibration.scale(family))}));
        rows.sort(Comparator.comparing(r -> r[0]));
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r[1]));
        return out;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasExactlyOne()) {
            return Stream.empty();
        }
        String prefix = args.getString().toLowerCase();
        return Stream.of("reset").filter(s -> s.startsWith(prefix));
    }

    @Override
    public String getShortDesc() {
        return "Planned vs real movement ticks";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Shows, for each movement family, how many movements finished and the mean ratio of ticks taken",
                "to ticks planned. The scale column is what the pathfinder applies (1 until "
                        + CostCalibration.MIN_SAMPLES + " samples, capped at 1.5).",
                "",
                "Usage:",
                "> timings - Show the table",
                "> timings reset - Clear all samples"
        );
    }
}
