package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.GoalXZ;
import baritone.structure.DetectedStructure;
import baritone.structure.StructureBehavior;
import baritone.structure.StructureInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class StructuresCommand extends Command {

    public StructuresCommand(IBaritone baritone) {
        super(baritone, "structures", "structure");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        StructureBehavior sb = ((Baritone) baritone).getStructureBehavior();
        String what = args.hasAny() ? args.getString().toLowerCase() : "list";
        switch (what) {
            case "types": {
                for (StructureInfo s : StructureInfo.ALL) logDirect(s.id + " (" + s.family + ", " + s.dimension + ")");
                return;
            }
            case "clear": sb.clear(); logDirect("Structure memory cleared"); return;
            case "on": sb.setEnabled(true); logDirect("Structure detection on"); return;
            case "off": sb.setEnabled(false); logDirect("Structure detection off"); return;
            case "source": {
                if (!args.hasAny()) { logDirect("Source: " + sb.getMode().name().toLowerCase()); return; }
                try {
                    sb.setMode(StructureBehavior.Mode.valueOf(args.getString().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    logDirect("Use: client, server or both");
                    return;
                }
                logDirect("Source: " + sb.getMode().name().toLowerCase());
                return;
            }
            case "goto": case "nearest": {
                String q = args.hasAny() ? args.getString() : "";
                List<DetectedStructure> r = sb.find(q);
                if (r.isEmpty()) { logDirect("No " + (q.isEmpty() ? "structure" : q) + " known yet"); return; }
                DetectedStructure s = r.get(0);
                logDirect("Nearest: " + s);
                if (what.equals("goto")) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(s.pos.getX(), s.pos.getZ()));
                }
                return;
            }
            default: {
                String q = what.equals("list") ? (args.hasAny() ? args.getString() : "") : what;
                List<DetectedStructure> r = sb.find(q);
                if (r.isEmpty()) { logDirect("No structures known" + (q.isEmpty() ? "" : " matching " + q)); return; }
                for (int i = 0; i < Math.min(20, r.size()); i++) logDirect(r.get(i).toString());
                if (r.size() > 20) logDirect("... and " + (r.size() - 20) + " more");
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        List<String> o = new ArrayList<>(Arrays.asList("list", "types", "nearest", "goto", "source", "clear", "on", "off"));
        for (StructureInfo s : StructureInfo.ALL) o.add(s.id);
        return o.stream();
    }

    @Override
    public String getShortDesc() {
        return "Find structures";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Detects structures from the blocks you have loaded (and, in singleplayer, from the exact server data).", "",
                "Usage:", "> structures [list] [type]", "> structures nearest [type]", "> structures goto [type]",
                "> structures source <client|server|both>", "> structures types", "> structures clear", "> structures on|off",
                "", "A type is a structure id (village_desert) or a family (village).");
    }
}
