package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.bastion.BastionSettings;
import baritone.process.BastionProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class BastionCommand extends Command {

    public BastionCommand(IBaritone baritone) {
        super(baritone, "bastion");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        BastionProcess p = ((Baritone) baritone).getBastionProcess();
        String what = args.hasAny() ? args.getString().toLowerCase() : "";
        switch (what) {
            case "stop": p.stop(); logDirect("Bastion stopped"); return;
            case "status": logDirect(p.status()); return;
            case "settings": logDirect(BastionSettings.describe()); return;
            case "reset": BastionSettings.resetTargets(); logDirect(BastionSettings.describe()); return;
            case "set": {
                String key = args.getString().toLowerCase(), value = args.getString();
                String err = BastionSettings.set(key, value);
                logDirect(err != null ? err : BastionSettings.describe());
                return;
            }
            default: p.start(what.equals("any") ? "" : what);
        }
        logDirect("Bastion: " + (what.isEmpty() ? "any" : what));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("any", "housing", "stables", "treasure", "bridge", "status", "settings", "set", "reset", "stop");
    }

    @Override
    public String getShortDesc() {
        return "Speedrun bastion: loot chests by layout, barter to targets, leave";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Walks to a detected bastion, wears gold, throws gold at calm piglins, collects the loot and fights brutes and hoglins.", "",
                "Usage:", "> bastion [any | housing | stables | treasure | bridge]", "> bastion status", "> bastion settings", "> bastion reset",
                "> bastion set <item id> <count>   (target, 0 removes; e.g. ender_pearl 12, obsidian 10, string 6, fire_resistance 1)",
                "> bastion set <barters|keepingots|maxdrop|lowhealth|exit|exitdistance|chestwait> <value>", "> bastion stop");
    }
}
