package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.PvpProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class PvpCommand extends Command {

    public PvpCommand(IBaritone baritone) {
        super(baritone, "pvp");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        PvpProcess pvp = ((Baritone) baritone).getPvpProcess();
        String what = args.hasAny() ? args.getString() : "players";
        switch (what.toLowerCase()) {
            case "hostiles": ((Baritone) baritone).getPveProcess().attackHostiles(); logDirect("Mobs are PvE now: running #pve hostiles"); return;
            case "players": pvp.attackPlayers(); break;
            case "stats": logDirect(pvp.stats()); return;
            default: pvp.attackPlayer(what);
        }
        logDirect("PvP: " + what);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("players", "hostiles", "stats");
    }

    @Override
    public String getShortDesc() {
        return "Fight a player or all players (mobs: #pve)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Crits, W-taps, strafing, shield, axe vs shield, bow, gapples, totem.", "",
                "Usage:", "> pvp <name> / players / hostiles", "> pvp stats");
    }
}
