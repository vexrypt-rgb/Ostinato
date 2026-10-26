package baritone.agent;

// Ostinato: approved agent defaults. Owner-only, a tool whitelist, and dry-run are enforced before any command runs.

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class AgentPolicy {

    public static final String DEFAULT_TOOLS = "goto,mine,follow,settings,route";

    private AgentPolicy() {}

    /**
     * Owner-only allows the local player. A set owner must match the sender.
     * An empty sender is refused once an owner name is set.
     */
    public static boolean ownerAllowed(boolean ownerOnly, String owner, String sender) {
        if (!ownerOnly) {
            return true;
        }
        if (owner == null || owner.isBlank()) {
            return true;
        }
        if (sender == null || sender.isBlank()) {
            return false;
        }
        return owner.trim().equalsIgnoreCase(sender.trim());
    }

    public static Set<String> tools(String csv) {
        String raw = csv == null || csv.isBlank() ? DEFAULT_TOOLS : csv;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return Collections.unmodifiableSet(names);
    }

    public static boolean toolAllowed(String csv, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String needle = name.toLowerCase(Locale.ROOT);
        if ("set".equals(needle)) {
            needle = "settings";
        }
        return tools(csv).contains(needle);
    }
}
