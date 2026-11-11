package baritone.swarm.center;

import baritone.api.command.ArgDecl;
import baritone.api.command.ArgKind;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;

import java.util.List;
import java.util.Locale;

/** Ostinato: one command in the unified registry. Built from a {@code #} or {@code @} catalog entry, not a third catalog. */
public final class RegistryEntry {

    public enum Origin { HASH, AT }

    public final String name;
    public final List<String> aliases;
    public final String help;
    public final CommandPermission permission;
    public final String targetBot;
    public final List<ArgDecl> schema;
    public final List<FormDecl> forms;
    public final boolean leaseNeeding;
    public final boolean worldMutation;
    public final boolean ambiguous;
    public final Origin origin;

    public RegistryEntry(String name, List<String> aliases, String help, CommandPermission permission, String targetBot,
                         List<ArgDecl> schema, List<FormDecl> forms, boolean leaseNeeding, boolean worldMutation,
                         boolean ambiguous, Origin origin) {
        this.name = name == null ? "" : name.toLowerCase(Locale.ROOT);
        this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
        this.help = help == null ? "" : help;
        this.permission = permission == null ? CommandPermission.OWNER : permission;
        this.targetBot = targetBot;
        this.schema = schema == null ? List.of() : List.copyOf(schema);
        this.forms = forms == null ? List.of() : List.copyOf(forms);
        this.leaseNeeding = leaseNeeding;
        this.worldMutation = worldMutation;
        this.ambiguous = ambiguous;
        this.origin = origin == null ? Origin.HASH : origin;
    }

    /** Null when the argument string matches the declared schema. */
    public String invalid(String args) {
        if (schema.isEmpty()) {
            return null;
        }
        String text = args == null ? "" : args.trim();
        if (schema.size() == 1 && schema.get(0).kind() == ArgKind.REST) {
            if (text.isEmpty()) {
                return schema.get(0).required() ? "missing " + schema.get(0).name() : null;
            }
            return schema.get(0).reject(text);
        }
        if (text.isEmpty()) {
            for (ArgDecl decl : schema) {
                if (decl.required() && decl.kind() != ArgKind.REST) {
                    return "missing " + decl.name();
                }
            }
            return null;
        }
        int space = text.indexOf(' ');
        String first = space < 0 ? text : text.substring(0, space);
        String rest = space < 0 ? "" : text.substring(space + 1).trim();
        String bad = schema.get(0).reject(first);
        if (bad != null) {
            return bad;
        }
        if (schema.size() > 1) {
            ArgDecl tail = schema.get(1);
            if (rest.isEmpty()) {
                return tail.required() ? "missing " + tail.name() : null;
            }
            return tail.reject(rest);
        }
        return null;
    }
}
