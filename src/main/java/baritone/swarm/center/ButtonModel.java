package baritone.swarm.center;

import baritone.api.command.FormDecl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ostinato: a command-center control taken from a registry form. There is no private button list. */
public record ButtonModel(String command, FormDecl form) {

    public String line(Map<String, String> values) {
        return form.line(values);
    }

    public static List<ButtonModel> from(List<RegistryEntry> entries) {
        List<ButtonModel> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (RegistryEntry entry : entries) {
            for (FormDecl form : entry.forms) {
                out.add(new ButtonModel(entry.name, form));
            }
        }
        return List.copyOf(out);
    }
}
