package baritone.swarm.center;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;

import java.util.List;

/**
 * Ostinato: what a tile's status dot and badge say. Status never relies on color alone:
 * every level has its own glyph and a word.
 */
public record TileStatus(Level level, String word) {

    public enum Level {
        OK("●"),     // filled circle
        IDLE("○"),   // hollow circle
        WARN("▲"),   // triangle
        BAD("✖");    // heavy x

        public final String glyph;

        Level(String glyph) {
            this.glyph = glyph;
        }
    }

    public boolean alert() {
        return level == Level.WARN || level == Level.BAD;
    }

    public String badge() {
        return level.glyph + " " + word;
    }

    public static TileStatus of(BotSnapshot bot) {
        if (bot.execution() == ExecutionState.FAILED) {
            return new TileStatus(Level.BAD, "failed");
        }
        if ("process dead".equals(bot.task())) {
            return new TileStatus(Level.BAD, "process dead");
        }
        if (bot.health() >= 0 && bot.health() <= 6) {
            return new TileStatus(Level.WARN, "low health");
        }
        if (bot.observation() == ObservationState.STALE) {
            return new TileStatus(Level.WARN, "stale");
        }
        if (bot.execution() == ExecutionState.RUNNING || bot.execution() == ExecutionState.ACCEPTED) {
            return new TileStatus(Level.OK, bot.execution().label());
        }
        if (bot.execution() == ExecutionState.CANCELED) {
            return new TileStatus(Level.IDLE, "canceled");
        }
        if (bot.execution() == ExecutionState.SUCCEEDED) {
            return new TileStatus(Level.OK, "done");
        }
        return new TileStatus(Level.OK, "online");
    }

    public static int alerts(List<BotSnapshot> tiles) {
        int count = 0;
        if (tiles != null) {
            for (BotSnapshot tile : tiles) {
                if (of(tile).alert()) {
                    count++;
                }
            }
        }
        return count;
    }
}
