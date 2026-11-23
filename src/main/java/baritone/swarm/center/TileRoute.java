package baritone.swarm.center;

import baritone.api.command.FormPlacement;

import java.util.Map;

/**
 * Ostinato: where a tile button's line runs. Pure, so the in-game window ({@link TileActions}), the command host and
 * the standalone window all compute the same line: goto aimed at another bot becomes
 * {@code swarm send <bot> <line>} on the host and mine a leased offer {@code swarm lease <bot> obs:<block> <line>} (mine
 * from a non-owner runs only under a lease); a cancel names the command it stops ({@code swarm cancel <id>}, run on
 * the host that issued it) and a cancel with no id is refused, because the protocol rejects a bare cancel
 * ({@code bad-cancel-target}); everything else runs as written.
 */
public final class TileRoute {

    private TileRoute() {}

    public static String route(ButtonModel button, String bot, Map<String, String> values) {
        String line = button.line(values);
        if (button.form().placement() != FormPlacement.TILE) {
            return line;
        }
        if ("swarm".equals(button.command()) && "cancel".equals(button.form().id())) {
            String id = values == null ? null : values.get("id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("no running command to cancel");
            }
            return line;
        }
        if (bot == null || bot.isBlank() || "local".equalsIgnoreCase(bot)) {
            return line;
        }
        String command = button.command();
        if ("goto".equals(command)) {
            return "swarm send " + bot + " " + line;
        }
        if ("mine".equals(command)) {
            // Ostinato: mine from a non-owner runs only under a lease from the coordinator, so the tile offers one: the
            // coordinator picks a known vein of the first block named, and the bot mines inside that lease's fence.
            return "swarm lease " + bot + " obs:" + firstBlock(line) + " " + line;
        }
        return line;
    }

    /** The first block a {@code mine [count] <block...>} line names, without its {@code minecraft:} namespace. */
    static String firstBlock(String mineLine) {
        String[] w = mineLine.trim().split("\\s+");
        for (int i = 1; i < w.length; i++) {
            if (!w[i].matches("\\d+")) {
                return w[i].startsWith("minecraft:") ? w[i].substring("minecraft:".length()) : w[i];
            }
        }
        throw new IllegalArgumentException("mine names no block");
    }

    /** The values a tile sends: a form with a {@code name} argument gets the tile's bot unless one was typed. */
    public static Map<String, String> fill(ButtonModel button, Map<String, String> values, String bot) {
        Map<String, String> out = new java.util.LinkedHashMap<>(values == null ? Map.of() : values);
        boolean hasName = button.form().args().stream().anyMatch(a -> "name".equals(a.name()));
        if (hasName && bot != null && !bot.isBlank() && (out.get("name") == null || out.get("name").isBlank())) {
            out.put("name", bot);
        }
        return out;
    }

    /** The target as the host sees it: its own name means itself (no {@code swarm send}). */
    public static String target(String bot, String hostName) {
        if (bot == null || bot.isBlank() || (hostName != null && bot.equalsIgnoreCase(hostName))) {
            return null;
        }
        return bot;
    }

    /**
     * The equivalent {@code #} line the window shows before sending, run on {@code hostName}; throws
     * {@link IllegalArgumentException} with the form's reason while a required field is empty or malformed.
     */
    public static String preview(ButtonModel button, String bot, Map<String, String> values, String hostName) {
        return "#" + route(button, target(bot, hostName), values);
    }
}
