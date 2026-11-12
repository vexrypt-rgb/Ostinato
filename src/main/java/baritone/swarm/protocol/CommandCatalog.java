/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.protocol;

import baritone.api.command.CommandPermission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: registry metadata for one command name. This does not replace the {@code #} or {@code @}
 * catalogs. It only records permission, capabilities, argument presence and whether Phase 1 may run it.
 */
public final class CommandCatalog {

    private CommandCatalog() {}

    public static final class Entry {
        public final String name;
        public final List<String> aliases;
        public final CommandPermission permission;
        public final Set<String> requires;
        public final boolean leaseNeeding;
        public final boolean worldMutation;
        public final boolean ambiguous;
        public final boolean argsRequired;
        public final boolean ownerEmergency;
        /** Null, or {@code int} when the whole argument string must be an integer. */
        public final String argType;

        public Entry(String name, List<String> aliases, CommandPermission permission, Set<String> requires,
                     boolean leaseNeeding, boolean worldMutation, boolean ambiguous, boolean argsRequired,
                     boolean ownerEmergency, String argType) {
            this.name = name;
            this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
            this.permission = permission == null ? CommandPermission.OWNER : permission;
            this.requires = requires == null || requires.isEmpty()
                    ? Set.of() : Set.copyOf(requires);
            this.leaseNeeding = leaseNeeding;
            this.worldMutation = worldMutation;
            this.ambiguous = ambiguous;
            this.argsRequired = argsRequired;
            this.ownerEmergency = ownerEmergency;
            this.argType = argType;
        }

        /** Null when the arguments are acceptable. */
        public String validate(String args) {
            String text = args == null ? "" : args.trim();
            if (argsRequired && text.isEmpty()) {
                return "missing arguments";
            }
            if ("int".equals(argType) && !text.isEmpty()) {
                try {
                    Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    return "bad int";
                }
            }
            return null;
        }
    }

    public static CommandPermission defaultPermission(String name) {
        switch (norm(name)) {
            case "stop":
            case "pause":
            case "disconnect":
            case "set":
            case "reset":
            case "reload":
            case "save":
            case "saveall":
            case "explore": // Ostinato: ExploreCommand keeps ICommand#getPermission's OWNER default; never SWARM.
                return CommandPermission.OWNER;
            case "cancel":
            case "forcecancel":
                // Ostinato: chat #cancel defaults to OWNER. A swarm member must still
                // be able to cancel a command it sent to a peer.
                return CommandPermission.SWARM;
            case "agent":
            case "agentdo":
                return CommandPermission.AI;
            default:
                return CommandPermission.SWARM;
        }
    }

    /** Infer tags from a command name. {@code permission} wins over the default. */
    public static Entry infer(String name, CommandPermission permission) {
        String n = norm(name);
        CommandPermission perm = permission == null ? defaultPermission(n) : permission;
        Set<String> caps = capsFor(n);
        boolean mutate = caps.contains("mining") || caps.contains("building") || caps.contains("inventory")
                || "place".equals(n) || "drop".equals(n) || "craft".equals(n) || "use".equals(n);
        boolean emergency = perm == CommandPermission.OWNER && isEmergencyName(n);
        boolean args = switch (n) {
            case "goto", "come", "follow", "goal", "home", "mine", "tunnel", "farm", "build", "path", "explore" -> true;
            default -> false;
        };
        return new Entry(n, List.of(), perm, caps, false, mutate, false, args, emergency, null);
    }

    /** A TenorClef {@code @} command: completion is ambiguous until something verifies it. */
    public static Entry at(String name) {
        Entry e = infer(name, CommandPermission.SWARM);
        return new Entry(e.name, e.aliases, e.permission, e.requires, e.leaseNeeding, e.worldMutation,
                true, e.argsRequired, false, e.argType);
    }

    public static List<Entry> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] blocks = text.split("(?m)^---\\s*$");
        List<Entry> out = new ArrayList<>();
        for (String block : blocks) {
            Entry e = parseBlock(block);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    public static Map<String, Entry> index(List<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        if (entries == null) {
            return map;
        }
        for (Entry e : entries) {
            map.putIfAbsent(e.name.toLowerCase(Locale.ROOT), e);
            for (String alias : e.aliases) {
                map.putIfAbsent(alias.toLowerCase(Locale.ROOT), e);
            }
        }
        return map;
    }

    private static Entry parseBlock(String block) {
        String name = null;
        List<String> aliases = new ArrayList<>();
        CommandPermission perm = null;
        Set<String> caps = new LinkedHashSet<>();
        boolean lease = false;
        boolean mutate = false;
        boolean ambiguous = false;
        boolean argsRequired = false;
        String argType = null;
        boolean saw = false;
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
                case "name":
                    name = norm(value);
                    break;
                case "alias":
                    if (!value.isEmpty()) {
                        aliases.add(norm(value));
                    }
                    break;
                case "perm":
                    perm = CommandPermission.parse(value);
                    break;
                case "caps":
                    for (String part : value.split(",")) {
                        if (!part.isBlank()) {
                            caps.add(part.trim());
                        }
                    }
                    break;
                case "lease":
                    lease = truth(value);
                    break;
                case "mutate":
                    mutate = truth(value);
                    break;
                case "ambiguous":
                    ambiguous = truth(value);
                    break;
                case "arg":
                    String[] bits = value.split(":", -1);
                    if (bits.length >= 3 && "required".equalsIgnoreCase(bits[2])) {
                        argsRequired = true;
                    }
                    if (bits.length >= 2 && "int".equalsIgnoreCase(bits[1]) && argType == null) {
                        argType = "int";
                    }
                    break;
                default:
                    break;
            }
        }
        if (!saw || name == null || name.isEmpty()) {
            return null;
        }
        CommandPermission use = perm == null ? CommandPermission.OWNER : perm;
        boolean emergency = use == CommandPermission.OWNER && isEmergencyName(name);
        return new Entry(name, aliases, use, caps, lease, mutate, ambiguous, argsRequired, emergency, argType);
    }

    private static boolean truth(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static boolean isEmergencyName(String name) {
        switch (name) {
            case "stop":
            case "pause":
            case "disconnect":
            case "cancel":
            case "forcecancel":
                return true;
            default:
                return false;
        }
    }

    private static Set<String> capsFor(String name) {
        switch (name) {
            case "goto":
            case "come":
            case "follow":
            case "path":
            case "goal":
            case "thisway":
            case "explore":
            case "surface":
            case "axis":
            case "home":
            case "elytra":
            case "portal":
                return Set.of("navigation");
            case "mine":
            case "tunnel":
            case "farm":
                return Set.of("mining");
            case "build":
            case "sel":
            case "litematica":
            case "schematic":
            case "place":
                return Set.of("building");
            case "pvp":
            case "pve":
            case "bastion":
                return Set.of("combat");
            case "pickup":
            case "inventory":
            case "stash":
            case "deposit":
            case "give":
                return Set.of("inventory");
            case "render":
            case "freecam":
                return Set.of("liveRendering");
            default:
                return Set.of();
        }
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
