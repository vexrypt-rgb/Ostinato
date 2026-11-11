package baritone.swarm.center;

import baritone.agent.AgentPolicy;
import baritone.api.command.CommandPermission;
import baritone.api.command.ICommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Ostinato: the one {@code execute} for local {@code #} chat, local {@code @} chat, the command-center window,
 * and AI tool calls. Phase 1 does not take a distributed lease. A lease-needing or multi-bot mutating command
 * is refused, or returned as a preview when the call is a dry run. The dispatcher is not called on a refusal.
 */
public final class UnifiedRegistry {

    // Ostinato: one lookup per prefix, so an @ line is checked against the @ entry even when # has the same name.
    private final Map<String, RegistryEntry> byHash;
    private final Map<String, RegistryEntry> byAt;
    private final List<RegistryEntry> entries;
    private final Map<String, String> inflight = new LinkedHashMap<>();
    /** Says a target has nothing running, so its busy slot can clear after a task finishes on its own. */
    private volatile Predicate<String> idle = target -> false;

    private UnifiedRegistry(Map<String, RegistryEntry> byHash, Map<String, RegistryEntry> byAt, List<RegistryEntry> entries) {
        this.byHash = byHash;
        this.byAt = byAt;
        this.entries = entries;
    }

    public static UnifiedRegistry of(List<RegistryEntry> hash, List<RegistryEntry> at) {
        Map<String, RegistryEntry> hashMap = new LinkedHashMap<>();
        Map<String, RegistryEntry> atMap = new LinkedHashMap<>();
        List<RegistryEntry> list = new ArrayList<>();
        add(atMap, list, at);
        add(hashMap, list, hash);
        // Ostinato: buttons show one entry per name; the # command wins, the @ lookup keeps its own entry.
        list.removeIf(e -> e.origin == RegistryEntry.Origin.AT && hashMap.get(e.name) != null
                && hashMap.get(e.name).name.equals(e.name));
        return new UnifiedRegistry(hashMap, atMap, List.copyOf(list));
    }

    /** Ostinato: lets a finished path or mine free its slot without a cancel through the registry. */
    public void idleProbe(Predicate<String> probe) {
        idle = probe == null ? target -> false : probe;
    }

    public static UnifiedRegistry fromCommands(Iterable<ICommand> commands, String atCatalog) {
        List<RegistryEntry> hash = new ArrayList<>();
        if (commands != null) {
            for (ICommand command : commands) {
                RegistryEntry entry = Catalogs.fromCommand(command);
                if (entry != null) {
                    hash.add(entry);
                }
            }
        }
        return of(hash, Catalogs.parseAt(atCatalog));
    }

    private static void add(Map<String, RegistryEntry> map, List<RegistryEntry> list, List<RegistryEntry> entries) {
        if (entries == null) {
            return;
        }
        for (RegistryEntry entry : entries) {
            if (entry == null || entry.name.isEmpty()) {
                continue;
            }
            RegistryEntry previous = map.get(entry.name);
            if (previous != null && previous.name.equals(entry.name)) {
                list.remove(previous);
                map.values().removeIf(e -> e == previous);
            }
            // Ostinato: a name always wins over another entry's alias; an alias never takes over an existing key.
            map.put(entry.name, entry);
            for (String alias : entry.aliases) {
                map.putIfAbsent(alias.toLowerCase(Locale.ROOT), entry);
            }
            list.add(entry);
        }
    }

    public List<RegistryEntry> entries() {
        return entries;
    }

    public RegistryEntry find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("@")) {
            return byAt.get(key.substring(1).trim());
        }
        if (key.startsWith("#")) {
            return byHash.get(key.substring(1).trim());
        }
        RegistryEntry hash = byHash.get(key);
        return hash != null ? hash : byAt.get(key);
    }

    public List<ButtonModel> buttons() {
        return ButtonModel.from(entries);
    }

    public RegistryResult execute(RegistryCall call, BooleanSupplier dispatch) {
        if (call == null) {
            return RegistryResult.denied("unauthenticated");
        }
        String name = nameOf(call.line);
        String args = argsOf(call.line);
        if (call.kind == RegistryCall.Kind.LOCAL_CHAT) {
            if (dispatch == null) {
                return RegistryResult.missed();
            }
            boolean ok = dispatch.getAsBoolean();
            RegistryEntry local = find(prefixOf(call.line) + name);
            if (ok && local != null && releases(local, args)) {
                synchronized (inflight) {
                    inflight.remove("local"); // Ostinato: a cancel typed in local chat frees the slot too
                }
            }
            return ok ? RegistryResult.accepted() : RegistryResult.missed();
        }
        RegistryEntry entry = find(prefixOf(call.line) + name);
        if (entry == null) {
            return RegistryResult.denied("unknown");
        }
        String refusal = deny(entry, call, args);
        if (refusal != null) {
            return "preview".equals(refusal) ? RegistryResult.preview() : RegistryResult.denied(refusal);
        }
        if (dispatch == null) {
            return RegistryResult.denied("failed");
        }
        boolean ok;
        try {
            ok = dispatch.getAsBoolean();
        } catch (Throwable ignored) {
            return RegistryResult.denied("failed");
        }
        if (ok) {
            note(entry, call, args);
        }
        return ok ? RegistryResult.accepted() : RegistryResult.missed();
    }

    private String deny(RegistryEntry entry, RegistryCall call, String args) {
        if (!call.authenticated) {
            return "unauthenticated";
        }
        switch (entry.permission) {
            case OWNER -> {
                if (call.kind != RegistryCall.Kind.WINDOW || !isOwner(call)) {
                    return "unauthorized";
                }
            }
            case SWARM -> {
                if (!swarmAllowed(call, entry)) {
                    return "unauthorized";
                }
            }
            case AI -> {
                if (call.kind == RegistryCall.Kind.WINDOW) {
                    if (!isOwner(call)) {
                        return "unauthorized";
                    }
                } else if (call.kind != RegistryCall.Kind.AI || !aiAllowed(call, entry)) {
                    return "unauthorized";
                }
            }
            default -> {
                return "unauthorized";
            }
        }
        String bad = entry.invalid(args);
        if (bad != null) {
            return "invalid";
        }
        if (call.dryRun) {
            return "preview";
        }
        if (entry.leaseNeeding) {
            return "lease";
        }
        if (occupies(entry) && (call.scope == RegistryCall.Scope.GROUP || call.scope == RegistryCall.Scope.ALL)) {
            return "phase1";
        }
        // Ostinato: the window owner may replace in-flight work. Busy still blocks AI and swarm peers.
        if (busy(entry, call, args) && !(call.kind == RegistryCall.Kind.WINDOW && isOwner(call))) {
            return "busy";
        }
        return null;
    }

    private boolean swarmAllowed(RegistryCall call, RegistryEntry entry) {
        return switch (call.kind) {
            case WINDOW -> isOwner(call) || call.swarmMember;
            case SWARM -> call.swarmMember;
            case AI -> aiAllowed(call, entry);
            case LOCAL_CHAT -> true;
            case PUBLIC -> false;
        };
    }

    private boolean aiAllowed(RegistryCall call, RegistryEntry entry) {
        if (entry.permission == CommandPermission.OWNER) {
            return false;
        }
        if (!AgentPolicy.ownerAllowed(call.ownerOnly, call.ownerName, call.actor)) {
            return false;
        }
        return AgentPolicy.toolAllowed(call.tools, entry.name);
    }

    private boolean isOwner(RegistryCall call) {
        if (call.ownerName.isBlank()) {
            return call.kind == RegistryCall.Kind.WINDOW || call.kind == RegistryCall.Kind.LOCAL_CHAT;
        }
        return AgentPolicy.ownerAllowed(true, call.ownerName, call.actor);
    }

    private boolean busy(RegistryEntry entry, RegistryCall call, String args) {
        if (releases(entry, args) || !occupies(entry)) {
            return false;
        }
        String slot = slot(call);
        synchronized (inflight) {
            if (!inflight.containsKey(slot)) {
                return false;
            }
        }
        boolean free;
        try {
            free = idle.test(slot);
        } catch (RuntimeException ignored) {
            free = false;
        }
        if (free) {
            synchronized (inflight) {
                inflight.remove(slot);
            }
            return false;
        }
        return true;
    }

    private void note(RegistryEntry entry, RegistryCall call, String args) {
        String slot = slot(call);
        synchronized (inflight) {
            if (releases(entry, args)) {
                inflight.remove(slot);
            } else if (occupies(entry)) {
                inflight.put(slot, entry.name);
            }
        }
    }

    /** One bot, one ordinary path or mine. Navigation counts even though it is not a block edit. */
    private static boolean occupies(RegistryEntry entry) {
        if (entry.worldMutation) {
            return true;
        }
        return switch (entry.name) {
            case "goto", "come", "follow", "path", "goal", "mine", "tunnel", "farm", "build" -> true;
            default -> false;
        };
    }

    private static String slot(RegistryCall call) {
        if (call.target == null || call.target.isBlank() || "local".equalsIgnoreCase(call.target)) {
            return "local";
        }
        return call.target.toLowerCase(Locale.ROOT);
    }

    static boolean releases(RegistryEntry entry, String args) {
        String name = entry.name;
        if ("cancel".equals(name) || "forcecancel".equals(name) || "stop".equals(name) || "pause".equals(name)) {
            return true;
        }
        if ("swarm".equals(name)) {
            String action = first(args);
            return "despawn".equals(action) || "cancel".equals(action) || "stop".equals(action);
        }
        return false;
    }

    private static String first(String args) {
        String text = args == null ? "" : args.trim();
        int space = text.indexOf(' ');
        return (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static String prefixOf(String line) {
        String text = line == null ? "" : line.trim();
        return text.startsWith("@") ? "@" : text.startsWith("#") ? "#" : "";
    }

    public static String nameOf(String line) {
        String text = line == null ? "" : line.trim();
        if (text.startsWith("#") || text.startsWith("@")) {
            text = text.substring(1).trim();
        }
        int space = text.indexOf(' ');
        return (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    public static String argsOf(String line) {
        String text = line == null ? "" : line.trim();
        if (text.startsWith("#") || text.startsWith("@")) {
            text = text.substring(1).trim();
        }
        int space = text.indexOf(' ');
        return space < 0 ? "" : text.substring(space + 1).trim();
    }

    /** Visible for tests that check a second command is refused while one is in flight. */
    public boolean inFlight(String target) {
        synchronized (inflight) {
            return inflight.containsKey(target == null || target.isBlank() ? "local" : target.toLowerCase(Locale.ROOT));
        }
    }
}
