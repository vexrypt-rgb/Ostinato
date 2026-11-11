package baritone.api.command;

import java.util.List;
import java.util.Map;

/**
 * Ostinato: a button or form generated from one registry command.
 * {@link #prefix} is the chat line without the typed fields, for example {@code swarm spawn}.
 */
public record FormDecl(String id, String label, String prefix, List<ArgDecl> args, FormPlacement placement) {

    public FormDecl {
        if (id == null || id.isBlank() || prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("form id and prefix");
        }
        label = label == null || label.isBlank() ? id : label;
        args = args == null ? List.of() : List.copyOf(args);
        placement = placement == null ? FormPlacement.TILE : placement;
    }

    public static FormDecl of(String id, String label, String prefix, FormPlacement placement, ArgDecl... args) {
        return new FormDecl(id, label, prefix, List.of(args), placement);
    }

    /** The chat line. Throws when a required field is missing or a field is not its declared type. */
    public String line(Map<String, String> values) {
        StringBuilder sb = new StringBuilder(prefix.trim());
        Map<String, String> given = values == null ? Map.of() : values;
        for (ArgDecl arg : args) {
            String raw = given.get(arg.name());
            if (raw == null || raw.isBlank()) {
                if (arg.required()) {
                    throw new IllegalArgumentException("missing " + arg.name());
                }
                continue;
            }
            String text = raw.trim();
            String bad = arg.reject(text);
            if (bad != null) {
                throw new IllegalArgumentException(bad);
            }
            if ("account".equals(arg.name())) {
                sb.append(" account:").append(text);
            } else {
                sb.append(' ').append(text);
            }
        }
        return sb.toString();
    }
}
