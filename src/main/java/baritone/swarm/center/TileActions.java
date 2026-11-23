package baritone.swarm.center;

import java.util.Map;

/**
 * Ostinato: a tile button is a registry form. Goto aimed at another bot is sent with {@code swarm send} and mine
 * with a leased {@code swarm lease} offer; that send or offer is itself an owner command in the same registry.
 * Despawn, spawn, layout, and stop-all stay on their own lines. Closing the window is not a command.
 */
public final class TileActions {

    @FunctionalInterface
    public interface Sink {
        boolean run(String line);
    }

    private TileActions() {}

    /** Ostinato: the rule lives in {@link TileRoute} so the standalone window previews the same line. */
    public static String route(ButtonModel button, String bot, Map<String, String> values) {
        return TileRoute.route(button, bot, values);
    }

    /**
     * Admit the button's own command. A remote route also has to be admitted as {@code swarm send}
     * before anything runs. A refused call does not invoke {@code sink}.
     */
    public static RegistryResult press(UnifiedRegistry registry, RegistryCall call, ButtonModel button,
                                       Map<String, String> values, String bot, Sink sink) {
        String line;
        String routed;
        try {
            line = button.line(values);
            routed = route(button, bot, values);
        } catch (IllegalArgumentException e) {
            return RegistryResult.denied(e.getMessage()); // Ostinato: for example a cancel with no running command
        }
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
