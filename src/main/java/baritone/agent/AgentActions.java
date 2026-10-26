package baritone.agent;

// Ostinato: goto, mine, follow, and settings stubs. Each one maps to an existing command.
// The settings stub refuses provider and secret names so a reply cannot redirect the API key.

import baritone.api.agent.AgentAction;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class AgentActions {

    static final Pattern COORD = Pattern.compile("^(?:~|~-?\\d{1,8}|-?\\d{1,8})$");
    static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_.:\\-]{1,64}$");
    static final Pattern SETTING_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");
    static final Pattern SETTING_VALUE = Pattern.compile("^[A-Za-z0-9_.:\\-]{1,64}$");
    static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    public static final AgentAction GOTO = new Mapped("goto",
            "Walk to x y z or to a block id.",
            List.of("x", "y", "z", "block"),
            AgentActions::gotoCommand);
    public static final AgentAction MINE = new Mapped("mine",
            "Mine a block id. count is optional.",
            List.of("block", "count"),
            AgentActions::mineCommand);
    public static final AgentAction FOLLOW = new Mapped("follow",
            "Follow players, entities, an entity id, or a player name.",
            List.of("target", "entity", "player"),
            AgentActions::followCommand);
    public static final AgentAction SETTINGS = new Mapped("settings",
            "Change one Baritone setting. Cannot change agent settings or anything with a key.",
            List.of("name", "value"),
            AgentActions::settingsCommand);
    public static final AgentAction ROUTE = new Mapped("route",
            "Save or run a named task route. op is save or run. save uses steps separated by semicolons.",
            List.of("op", "name", "steps"),
            AgentActions::routeCommand);
    public static final AgentAction PVP = new Mapped("pvp",
            "Fight players, hostiles, or one player name. The harness does not fight for the agent.",
            List.of("target"),
            AgentActions::pvpCommand);
    public static final AgentAction GEAR = new Mapped("gear",
            "Change one gear or combat setting by name and value. Cannot change agent settings or anything with a key.",
            List.of("name", "value"),
            AgentActions::gearCommand);

    public static final List<AgentAction> ALL = List.of(GOTO, MINE, FOLLOW, PVP, GEAR, SETTINGS, ROUTE);

    private AgentActions() {}

    public static String toCommand(String name, Map<String, String> args) {
        if (name == null) {
            return null;
        }
        String needle = name.toLowerCase(Locale.ROOT);
        for (AgentAction action : ALL) {
            if (action.name().equals(needle) || ("set".equals(needle) && "settings".equals(action.name()))) {
                return action.toCommand(args == null ? Map.of() : args);
            }
        }
        return null;
    }

    static String gotoCommand(Map<String, String> args) {
        String block = args.get("block");
        if (block != null && !block.isBlank()) {
            return TOKEN.matcher(block).matches() ? "goto " + block : null;
        }
        String x = args.get("x");
        String y = args.get("y");
        String z = args.get("z");
        if (x == null || y == null || z == null) {
            return null;
        }
        if (!COORD.matcher(x).matches() || !COORD.matcher(y).matches() || !COORD.matcher(z).matches()) {
            return null;
        }
        return "goto " + x + " " + y + " " + z;
    }

    static String mineCommand(Map<String, String> args) {
        String block = args.get("block");
        if (block == null || !TOKEN.matcher(block).matches()) {
            return null;
        }
        String count = args.get("count");
        if (count == null || count.isBlank() || "0".equals(count)) {
            return "mine " + block;
        }
        if (!count.matches("\\d{1,6}")) {
            return null;
        }
        return "mine " + count + " " + block;
    }

    static String followCommand(Map<String, String> args) {
        String target = args.getOrDefault("target", "");
        if ("players".equalsIgnoreCase(target) || "entities".equalsIgnoreCase(target)) {
            return "follow " + target.toLowerCase(Locale.ROOT);
        }
        String entity = args.get("entity");
        if (entity != null && TOKEN.matcher(entity).matches()) {
            return "follow entity " + entity;
        }
        String player = args.get("player");
        if (player != null && PLAYER.matcher(player).matches()) {
            return "follow player " + player;
        }
        if (TOKEN.matcher(target).matches()) {
            return "follow entity " + target;
        }
        return null;
    }

    static String settingsCommand(Map<String, String> args) {
        String name = args.get("name");
        String value = args.get("value");
        if (name == null || value == null) {
            return null;
        }
        if (!SETTING_NAME.matcher(name).matches() || !SETTING_VALUE.matcher(value).matches()) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("agent") || lower.contains("key") || lower.contains("secret")
                || lower.contains("password") || lower.contains("token")) {
            return null;
        }
        return "set " + name + " " + value;
    }

    /** Route is stored by the chat hook. It is not a Baritone command line. */
    static String routeCommand(Map<String, String> args) {
        return null;
    }

    static String pvpCommand(Map<String, String> args) {
        String target = args.get("target");
        if (target == null || target.isBlank()) {
            target = "players";
        }
        if ("players".equalsIgnoreCase(target) || "hostiles".equalsIgnoreCase(target)) {
            return "pvp " + target.toLowerCase(Locale.ROOT);
        }
        if (PLAYER.matcher(target).matches()) {
            return "pvp " + target;
        }
        return null;
    }

    /** Gear is a settings change. The same secret names are refused. */
    static String gearCommand(Map<String, String> args) {
        return settingsCommand(args);
    }

    private static final class Mapped implements AgentAction {
        private final String name;
        private final String description;
        private final List<String> parameters;
        private final java.util.function.Function<Map<String, String>, String> command;

        private Mapped(String name, String description, List<String> parameters,
                java.util.function.Function<Map<String, String>, String> command) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.command = command;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public List<String> parameterNames() {
            return parameters;
        }

        @Override
        public String toCommand(Map<String, String> args) {
            return command.apply(args == null ? Map.of() : args);
        }
    }
}
