package baritone.swarm.center.app;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;

import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: Phase 3 window, the words on a tile. Pure, so the wording is unit tested without Swing and every slice
 * that shows a bot (wall tile, map inspector) says the same thing.
 */
public final class TileText {

    private TileText() {}

    /** What the bot is doing: process, then its newest command and that command's state. */
    public static String task(BotFeed f) {
        StringBuilder sb = new StringBuilder(f.process());
        BotFeed.Execution last = null;
        for (BotFeed.Execution e : f.executions()) {
            if (e.ageMs() >= 0 && (last == null || e.ageMs() < last.ageMs())) {
                last = e;
            }
        }
        if (last != null && !last.command().isEmpty()) {
            sb.append(" · ").append(last.command()).append(' ').append(last.execution());
        }
        if (!"idle".equals(f.path().state())) {
            sb.append(" · path ").append(f.path().state());
        }
        return sb.toString();
    }

    /** Free slots, food, the top items, the lowest tool. "inventory n/a" when the bot sent none. */
    public static String inventory(BotFeed.Inventory inv) {
        if (inv.totalSlots() < 0) {
            return "inventory n/a";
        }
        List<String> parts = new ArrayList<>();
        parts.add("free " + inv.freeSlots() + "/" + inv.totalSlots());
        parts.add("food " + Math.max(0, inv.food()));
        List<String> items = new ArrayList<>();
        for (BotFeed.Item i : inv.top()) {
            items.add(i.count() + " " + i.id());
        }
        if (!items.isEmpty()) {
            parts.add(String.join(", ", items));
        }
        if (inv.lowestToolPct() >= 0) {
            parts.add((inv.lowestTool().isEmpty() ? "tool" : inv.lowestTool()) + " " + inv.lowestToolPct() + "%");
        }
        return String.join(" · ", parts);
    }

    /** Block position and dimension, or "not in a world". */
    public static String position(BotFeed f) {
        BotFeed.Pos p = f.pos();
        return p == null ? "not in a world" : p.blockX() + ", " + p.blockY() + ", " + p.blockZ() + " (" + dim(p.dim()) + ")";
    }

    public static String dim(char d) {
        return switch (d) {
            case 'o' -> "overworld";
            case 'n' -> "nether";
            case 'e' -> "end";
            default -> "other";
        };
    }

    /** "updated 2 s ago" for a stale or offline feed, empty for a fresh one. */
    public static String age(SwarmView.BotState b) {
        return b.freshness() == SwarmView.Freshness.FRESH ? "" : "updated " + b.ageMs() / 1000 + " s ago";
    }
}
