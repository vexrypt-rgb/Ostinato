package baritone.swarm.center;

import baritone.api.command.FormPlacement;

import java.util.Map;

/**
 * Ostinato: a tile button is a registry form. Goto and mine aimed at another bot are sent with
 * {@code swarm send}, and that send is itself an owner command in the same registry.
 * Despawn, spawn, layout, and stop-all stay on their own lines. Closing the window is not a command.
 */
public final class TileActions {

    @FunctionalInterface
    public interface Sink {
        boolean run(String line);
    }

    private TileActions() {}

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

    /**
     * Admit the button's own command. A remote route also has to be admitted as {@code swarm send}
     * before anything runs. A refused call does not invoke {@code sink}.
     */
    public static RegistryResult press(UnifiedRegistry registry, RegistryCall call, ButtonModel button,
                                       Map<String, String> values, String bot, Sink sink) {
        String line = button.line(values);
        String routed = route(button, bot, values);
        RegistryCall scoped = call.withLine(line);
        if (bot != null && !bot.isBlank()) {
            scoped = scoped.withScope(RegistryCall.Scope.ONE, bot);
        }
        if (!routed.equals(line)) {
            RegistryResult user = registry.execute(scoped, () -> false);
            if (!user.admitted()) {
                return user;
            }
            RegistryResult send = registry.execute(call.withLine(routed), () -> false);
            if (!send.admitted()) {
                return send;
            }
        }
        String run = routed;
        return registry.execute(scoped, () -> sink != null && sink.run(run));
    }
}
