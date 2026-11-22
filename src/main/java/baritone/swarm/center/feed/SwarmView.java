package baritone.swarm.center.feed;

import baritone.swarm.center.channel.HostInfo;

import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1. One snapshot of everything the window shows, as {@link SwarmSource#snapshot()}
 * returns it. Immutable; a new one is built every refresh. Absent parts are null ({@code coordinator}, {@code catalog},
 * {@code host}) or empty, never a half-read file. {@code problems} names each file that was refused and why (for
 * example a newer feed version), so the window can say so instead of silently dropping a bot.
 */
public record SwarmView(long readAtMs, List<BotState> bots, CoordinatorFeed coordinator, CatalogFeed catalog,
                        HostInfo host, Map<String, TerrainFeed> terrain, List<Alert> alerts, List<String> problems) {

    public static final SwarmView EMPTY = new SwarmView(0, List.of(), null, null, null, Map.of(), List.of(), List.of());

    public SwarmView {
        bots = bots == null ? List.of() : List.copyOf(bots);
        terrain = terrain == null ? Map.of() : Map.copyOf(terrain);
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public enum Freshness {
        /** Written within {@link CenterFeed#FRESH_MS}. */
        FRESH,
        /** Older than fresh, not yet offline. */
        STALE,
        /** Older than {@link CenterFeed#OFFLINE_MS}: the bot exited, crashed or froze. */
        OFFLINE
    }

    /** One bot's last feed and how old it is at {@link #readAtMs}. */
    public record BotState(BotFeed feed, long ageMs, Freshness freshness) {
        public String name() {
            return feed.name();
        }

        public static Freshness freshness(long ageMs) {
            return ageMs <= CenterFeed.FRESH_MS ? Freshness.FRESH
                    : ageMs <= CenterFeed.OFFLINE_MS ? Freshness.STALE : Freshness.OFFLINE;
        }
    }

    public BotState bot(String name) {
        for (BotState b : bots) {
            if (b.name().equalsIgnoreCase(name)) {
                return b;
            }
        }
        return null;
    }

    public List<Alert> alertsFor(String name) {
        return alerts.stream().filter(a -> a.bot().equalsIgnoreCase(name)).toList();
    }

    /** The server shown in the header: the host's, else the first bot's, else "no server". */
    public String server() {
        for (BotState b : bots) {
            if (b.feed().commandHost() && !b.feed().server().isBlank()) {
                return b.feed().server();
            }
        }
        for (BotState b : bots) {
            if (!b.feed().server().isBlank()) {
                return b.feed().server();
            }
        }
        return "no server";
    }

    /** The bot that serves the command channel, from host.json, else the bot whose feed says so, else null. */
    public String hostBot() {
        if (host != null && !host.bot().isBlank()) {
            return host.bot();
        }
        for (BotState b : bots) {
            if (b.feed().commandHost()) {
                return b.name();
            }
        }
        return null;
    }
}
