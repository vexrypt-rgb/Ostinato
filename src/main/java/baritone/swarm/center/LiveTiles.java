package baritone.swarm.center;

import baritone.api.swarm.protocol.ObservationState;

import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: tiles the wall can draw without a display.
 * Spawned children are a local link. Health stays unknown until a caller supplies it.
 * Per-bot memory is not on the spawn record, so it stays blank.
 */
public final class LiveTiles {

    private LiveTiles() {}

    public static BotSnapshot child(String name, String server, boolean alive) {
        return new BotSnapshot(name, -1, -1, alive ? "process alive" : "process dead",
                null, ObservationState.UNKNOWN, "local", "", server == null ? "" : server);
    }

    public static BotSnapshot localPlayer(String name, int health, int hunger, String server) {
        return new BotSnapshot(name, health, hunger, "", null, ObservationState.UNKNOWN, "local", "",
                server == null ? "" : server);
    }

    public static List<BotSnapshot> compose(BotSnapshot local, List<BotSnapshot> children) {
        List<BotSnapshot> out = new ArrayList<>();
        if (local != null) {
            out.add(local);
        }
        if (children != null) {
            out.addAll(children);
        }
        return List.copyOf(out);
    }

    public static String serverLabel(String current, List<BotSnapshot> tiles) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        if (tiles != null) {
            for (BotSnapshot tile : tiles) {
                if (!tile.server().isBlank()) {
                    return tile.server();
                }
            }
        }
        return "no server";
    }
}
