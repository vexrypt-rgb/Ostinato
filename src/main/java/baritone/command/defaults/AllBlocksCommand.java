package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.allblocks.AllBlocksRunners;
import baritone.api.allblocks.IAllBlocksRunner;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ostinato: public {@code #allblocks}. Survival collection run: one of every obtainable item into labeled chests and
 * one of every placeable block on a grid. The work is done by the registered {@link IAllBlocksRunner} (TenorClef).
 */
public class AllBlocksCommand extends Command {

    private static final List<String> ACTIONS = Arrays.asList("start", "status", "stop", "report", "reset");

    public AllBlocksCommand(IBaritone baritone) {
        super(baritone, "allblocks");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        IAllBlocksRunner runner = AllBlocksRunners.get();
        if (runner == null) {
            throw new CommandInvalidStateException("#allblocks needs TenorClef: no runner is registered");
        }
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "status";
        String result;
        switch (action) {
            case "start", "resume" -> {
                List<String> rest = new ArrayList<>();
                while (args.hasAny()) rest.add(args.getString());
                long nums = rest.stream().filter(s -> !s.equalsIgnoreCase("retry")).count();
                if (nums != 0 && nums != 3) {
                    throw new CommandInvalidTypeException(args.consumed(), "start [x y z] [retry]");
                }
                result = runner.start(String.join(" ", rest));
            }
            case "status" -> result = runner.status();
            case "stop", "cancel" -> result = runner.stop();
            case "report" -> result = runner.report(args.hasAny() ? args.getAs(Integer.class) : 20);
            case "reset" -> result = runner.reset();
            default -> throw new CommandInvalidTypeException(args.consumed(), "start [x y z] [retry], status, stop, report [n] or reset");
        }
        for (String line : result.split("\n")) logDirect(line);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasExactlyOne()) return Stream.empty();
        String pre = args.peekString().toLowerCase(Locale.US);
        return ACTIONS.stream().filter(s -> s.startsWith(pre));
    }

    @Override
    public String getShortDesc() {
        return "Collect one of every item and place every block (survival)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Survival collection run, done by TenorClef. For every item in the registry that a survival player can",
                "obtain, it gathers one (mining, crafting, smelting, mobs; never /give) and stores it in a labeled chest.",
                "Every placeable block it gets is also placed once on a grid next to the chests. Progress is saved per",
                "world, so a restart continues with the next missing item. Before each gather it checks the kit: a",
                "pickaxe (copper, then iron, then stone), food and armor.",
                "",
                "Layout (O = origin, the block you stand in at the first start): chest k at O + (2*(k%16), 0, 3*(k/16)),",
                "its sign at +1 z; grid cell n at O + (2*(n%32), 0, 12 + 2*(n/32)) with a floor block under each cell.",
                "",
                "Usage:",
                "> allblocks start [x y z] [retry] - start or resume (origin defaults to your feet, fixed after the first",
                "  start); retry gives stuck items another go",
                "> allblocks status - obtained / placed / skipped / remaining",
                "> allblocks report [n] - unsupported and skipped entries with the reason and failing code path",
                "> allblocks stop - stop; progress is kept",
                "> allblocks reset - forget this world's progress");
    }
}
