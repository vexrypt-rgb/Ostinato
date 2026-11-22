package baritone.swarm.center.feed;

import baritone.swarm.center.TileStatus;

/**
 * Ostinato: Phase 3 read API v1. One alert about one bot, derived by {@link Alerts} from the feeds (bots never send
 * alerts). Every alert has a glyph through its {@link TileStatus.Level}, so meaning never rests on color alone.
 * Alerts are drawn in the window only: no dialog, no sound, no focus change.
 */
public record Alert(String bot, Code code, TileStatus.Level level, String text) {

    public enum Code {
        OFFLINE(TileStatus.Level.BAD, "offline"),
        DEAD(TileStatus.Level.BAD, "dead"),
        FAILED(TileStatus.Level.BAD, "failed"),
        STALE(TileStatus.Level.WARN, "stale feed"),
        STUCK(TileStatus.Level.WARN, "stuck"),
        LOW_HEALTH(TileStatus.Level.WARN, "low health"),
        HUNGRY(TileStatus.Level.WARN, "hungry"),
        LINK_DOWN(TileStatus.Level.WARN, "link down"),
        LEASE_ENDED(TileStatus.Level.WARN, "lease ended"),
        LOW_TOOL(TileStatus.Level.WARN, "tool low"),
        INVENTORY_FULL(TileStatus.Level.WARN, "inventory full");

        public final TileStatus.Level level;
        /** Short word for a tile badge. */
        public final String word;

        Code(TileStatus.Level level, String word) {
            this.level = level;
            this.word = word;
        }
    }

    public Alert(String bot, Code code, String text) {
        this(bot, code, code.level, text);
    }

    /** Glyph, then the text: what a tile or the alert list shows. */
    public String badge() {
        return level.glyph + " " + text;
    }
}
