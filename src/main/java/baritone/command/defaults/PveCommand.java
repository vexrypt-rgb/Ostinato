package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.PveProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class PveCommand extends Command {

    public PveCommand(IBaritone baritone) {
        super(baritone, "pve");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        PveProcess pve = ((Baritone) baritone).getPveProcess();
        String what = args.hasAny() ? args.getString() : "hostiles";
        switch (what.toLowerCase()) {
            case "hostiles": case "all": pve.attackHostiles(); break;
            case "stats": logDirect(pve.stats()); return;
            case "clear": case "stop": pve.clearEnemies(); logDirect("PvE stopped"); return;
            default: pve.attackType(what.toLowerCase());
        }
        logDirect("PvE: " + what);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("hostiles", "zombie", "skeleton", "creeper", "spider", "stats", "clear");
    }

    @Override
    public String getShortDesc() {
        return "Fight hostile mobs";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Mob combat: charged swings, back off during the recharge, creeper clearance, shield against arrows, flee what is not worth it.", "",
                "Usage:", "> pve [hostiles | <mob id>]", "> pve stats", "> pve clear");
    }
}
