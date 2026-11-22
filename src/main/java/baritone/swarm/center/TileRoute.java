package baritone.swarm.center;

import baritone.api.command.FormPlacement;

import java.util.Map;

/**
 * Ostinato: where a tile button's line runs. Pure, so the in-game window ({@link TileActions}), the command host and
 * the standalone window all compute the same line: goto and mine aimed at another bot become
 * {@code swarm send <bot> <line>} on the host; a cancel with no id for another bot becomes
 * {@code swarm send <bot> cancel}; everything else runs as written.
 */
public final class TileRoute {

    private TileRoute() {}

    public static String route(ButtonModel button, String bot, Map<String, String> values) {
        String line = button.line(values);
        if (button.form().placement() != FormPlacement.TILE) {
            return line;
        }
        if (bot == null || bot.isBlank() || "local".equalsIgnoreCase(bot)) {
            return line;
        }
        String command = button.command();
        if ("goto".equals(command) || "mine".equals(command)) {
            return "swarm send " + bot + " " + line;
        }
        if ("swarm".equals(command) && "cancel".equals(button.form().id())) {
            String id = values == null ? null : values.get("id");
            if (id == null || id.isBlank()) {
                return "swarm send " + bot + " cancel";
            }
        }
        return line;
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
