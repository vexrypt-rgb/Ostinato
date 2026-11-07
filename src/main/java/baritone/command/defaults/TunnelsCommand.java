package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.process.TunnelProcess;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Ostinato: public {@code #maze.tunnels}. Endless dig-only 3x3 tunnel maze with a survival kit and bed spawn. */
public class TunnelsCommand extends Command {

    public TunnelsCommand(IBaritone baritone) {
        super(baritone, "maze.tunnels");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(2);
        TunnelProcess p = ((Baritone) baritone).getTunnelProcess();
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "start";
        switch (action) {
            case "start" -> {
                long seed = args.hasAny() ? args.getAs(Long.class) : Baritone.settings().mazeSeed.value;
                if (p.running()) p.stop();
                logDirect(p.start(seed));
            }
            case "status" -> logDirect(p.status());
            case "stop", "cancel" -> {
                p.stop();
                logDirect("Tunnels stopped");
            }
            default -> throw new CommandInvalidTypeException(args.consumed(), "start [seed], status or stop");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasExactlyOne()) return Stream.empty();
        String pre = args.peekString().toLowerCase(Locale.US);
        return Stream.of("start", "status", "stop").filter(s -> s.startsWith(pre));
    }

    @Override
    public String getShortDesc() {
        return "Dig an endless 3x3 tunnel maze (survival)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Digs an endless seeded 3D maze of 3 wide, 3 high tunnels on a 4-block grid (1-block walls) from where you",
                "stand: straight runs, T and cross junctions, and 3 wide stairs between levels 4 apart. Dig only. Torches",
                "only at junctions and stair ends.",
                "Before each tunnel it checks the survival kit (pickaxe, food, torches, copper or iron armor) and keeps a bed within 96",
                "blocks of the dig face for spawn; TenorClef gathers anything missing.",
                "",
                "Usage:",
                "> maze.tunnels [start] [seed] - start digging (seed defaults to mazeSeed)",
                "> maze.tunnels status - progress",
                "> maze.tunnels stop - stop");
    }
}
