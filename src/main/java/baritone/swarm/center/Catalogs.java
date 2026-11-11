package baritone.swarm.center;

import baritone.api.command.ArgDecl;
import baritone.api.command.ArgKind;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;
import baritone.api.command.ICommand;
import baritone.swarm.protocol.CommandCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato: adapters over the existing {@code #} commands and the {@code @} catalog text.
 * This class does not register a second set of command names.
 */
public final class Catalogs {

    private Catalogs() {}

    /** Same cancel exception as {@code RegistryExecutor}: a peer may cancel a command it sent. */
    public static CommandPermission permission(String name, CommandPermission declared) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("cancel".equals(key) || "forcecancel".equals(key)) {
            return CommandPermission.SWARM;
        }
        return declared == null ? CommandPermission.OWNER : declared;
    }

    public static RegistryEntry fromCommand(ICommand command) {
        if (command == null || command.getNames() == null || command.getNames().isEmpty()) {
            return null;
        }
        return fromDescribed(command.getNames(), command.getShortDesc(), command.getPermission(), command.getTargetBot(),
                command.argumentSchema(), command.forms());
    }

    public static RegistryEntry fromDescribed(List<String> names, String help, CommandPermission declared, String target,
                                              List<ArgDecl> schema, List<FormDecl> forms) {
        if (names == null || names.isEmpty() || names.get(0) == null || names.get(0).isBlank()) {
            return null;
        }
        String primary = names.get(0).toLowerCase(Locale.ROOT);
        List<String> aliases = new ArrayList<>();
        for (int i = 1; i < names.size(); i++) {
            if (names.get(i) != null && !names.get(i).isBlank()) {
                aliases.add(names.get(i).toLowerCase(Locale.ROOT));
            }
        }
        CommandPermission perm = permission(primary, declared);
        CommandCatalog.Entry inferred = CommandCatalog.infer(primary, perm);
        return new RegistryEntry(primary, aliases, help, perm, target, schema, forms,
                inferred.leaseNeeding, inferred.worldMutation, false, RegistryEntry.Origin.HASH);
    }

    /** Parse the same {@code ---}/{@code v1} blocks {@link CommandCatalog} and TenorClef's exporter write. */
    public static List<RegistryEntry> parseAt(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] blocks = text.split("(?m)^---\\s*$");
        List<RegistryEntry> out = new ArrayList<>();
        for (String block : blocks) {
            try {
                RegistryEntry entry = atBlock(block);
                if (entry != null) {
                    out.add(entry);
                }
            } catch (RuntimeException ignored) {
                // A bad catalog block is skipped. Its text can contain nothing we would log.
            }
        }
        return out;
    }

    private static RegistryEntry atBlock(String block) {
        String name = null;
        List<String> aliases = new ArrayList<>();
        String help = "";
        CommandPermission perm = null;
        boolean lease = false;
        boolean mutate = false;
        boolean ambiguous = true;
        boolean saw = false;
        List<ArgDecl> schema = new ArrayList<>();
        for (String raw : block.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if ("v1".equals(line)) {
                saw = true;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "name" -> name = norm(value);
                case "alias" -> {
                    if (!value.isEmpty()) {
                        aliases.add(norm(value));
                    }
                }
                case "help", "usage" -> {
                    if ("help".equals(key) || help.isEmpty()) {
                        help = value;
                    }
                }
                case "perm" -> perm = CommandPermission.parse(value);
                case "lease" -> lease = truth(value);
                case "mutate" -> mutate = truth(value);
                case "ambiguous" -> ambiguous = truth(value);
                case "arg" -> {
                    ArgDecl decl = arg(value);
                    if (decl != null) {
                        schema.add(decl);
                    }
                }
                default -> {
                }
            }
        }
        if (!saw || name == null || name.isEmpty()) {
            return null;
        }
        CommandPermission use = perm == null ? CommandPermission.OWNER : perm;
        if (!mutate) {
            mutate = CommandCatalog.infer(name, use).worldMutation;
        }
        return new RegistryEntry(name, aliases, help, use, null, schema, List.of(), lease, mutate, ambiguous,
                RegistryEntry.Origin.AT);
    }

    private static ArgDecl arg(String value) {
        String[] bits = value.split(":", -1);
        if (bits.length < 1 || bits[0].isBlank()) {
            return null;
        }
        String argName = bits[0].trim();
        String type = bits.length >= 2 ? bits[1].trim().toLowerCase(Locale.ROOT) : "string";
        boolean required = bits.length >= 3 && "required".equalsIgnoreCase(bits[2].trim());
        ArgKind kind = switch (type) {
            case "int", "integer" -> ArgKind.INT;
            case "rest" -> ArgKind.REST;
            default -> ArgKind.TOKEN;
        };
        return new ArgDecl(argName, kind, required, List.of());
    }

    private static boolean truth(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static String norm(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("#") || n.startsWith("@")) {
            n = n.substring(1);
        }
        return n;
    }
}
