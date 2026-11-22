package baritone.swarm.center.feed;

import baritone.swarm.center.TileStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1. The alert rules, in one place, applied to feeds the window has already read. Pure
 * apart from {@link Stuck}, which remembers where each busy bot was. Thresholds are constants so tests and the handoff
 * name them.
 */
public final class Alerts {

    public static final int LOW_HEALTH = 6;
    public static final int HUNGRY = 6;
    public static final int LOW_TOOL_PCT = 10;
    /** A busy bot that has not moved {@link #STUCK_BLOCKS} from where it was this long ago is stuck. */
    public static final long STUCK_MS = 30_000L;
    public static final double STUCK_BLOCKS = 1.0;
    /** A failed command older than this no longer alerts. */
    public static final long FAILED_MS = 60_000L;

    private Alerts() {}

    /** Every alert for these bots, worst level first, then by bot name, then by rule order. */
    public static List<Alert> derive(List<SwarmView.BotState> bots, CoordinatorFeed coordinator, Stuck stuck,
                                     long nowMs) {
        List<Alert> out = new ArrayList<>();
        for (SwarmView.BotState b : bots) {
            out.addAll(forBot(b, coordinator, stuck, nowMs));
        }
        out.sort(Comparator.comparingInt((Alert a) -> -a.level().ordinal()).thenComparing(a -> a.bot().toLowerCase(Locale.ROOT))
                .thenComparingInt(a -> a.code().ordinal()));
        return out;
    }

    static List<Alert> forBot(SwarmView.BotState b, CoordinatorFeed coordinator, Stuck stuck, long nowMs) {
        BotFeed f = b.feed();
        String n = f.name();
        List<Alert> out = new ArrayList<>();
        if (b.freshness() == SwarmView.Freshness.OFFLINE) {
            out.add(new Alert(n, Alert.Code.OFFLINE, "offline " + b.ageMs() / 1000 + " s"));
            if (stuck != null) {
                stuck.forget(n);
            }
            return out; // Ostinato: the rest of an offline feed is old news.
        }
        if (b.freshness() == SwarmView.Freshness.STALE) {
            out.add(new Alert(n, Alert.Code.STALE, "no update for " + b.ageMs() / 1000 + " s"));
        }
        if (f.dead()) {
            out.add(new Alert(n, Alert.Code.DEAD, "dead"));
        }
        BotFeed.Execution last = latest(f.executions());
        if (last != null && "failed".equals(last.execution()) && last.ageMs() >= 0 && last.ageMs() < FAILED_MS) {
            out.add(new Alert(n, Alert.Code.FAILED, last.command().isEmpty() ? "command failed"
                    : last.command() + " failed" + (last.endReason().isEmpty() ? "" : " (" + last.endReason() + ")")));
        }
        if (stuck != null && stuck.note(n, f, nowMs)) {
            out.add(new Alert(n, Alert.Code.STUCK, "stuck " + stuck.stuckForMs(n, nowMs) / 1000 + " s"));
        }
        if (!f.dead() && f.health() >= 0 && f.health() <= LOW_HEALTH) {
            out.add(new Alert(n, Alert.Code.LOW_HEALTH, "health " + f.health()));
        }
        if (!f.dead() && f.hunger() >= 0 && f.hunger() <= HUNGRY) {
            out.add(new Alert(n, Alert.Code.HUNGRY, "hunger " + f.hunger()));
        }
        if (!f.link().up() && running(f.executions())) {
            out.add(new Alert(n, Alert.Code.LINK_DOWN, "link down, outcome unknown"));
        }
        if (coordinator != null) {
            for (CoordinatorFeed.Lease l : coordinator.leases()) {
                if (l.bot().equalsIgnoreCase(n) && "expired".equals(l.state())) {
                    out.add(new Alert(n, Alert.Code.LEASE_ENDED, "lease " + l.resource() + " expired"
                            + (l.endReason().isEmpty() ? "" : " (" + l.endReason() + ")")));
                }
            }
        }
        BotFeed.Inventory inv = f.inventory();
        if (inv.lowestToolPct() >= 0 && inv.lowestToolPct() <= LOW_TOOL_PCT) {
            out.add(new Alert(n, Alert.Code.LOW_TOOL, (inv.lowestTool().isEmpty() ? "tool" : inv.lowestTool()) + " "
                    + inv.lowestToolPct() + "%"));
        }
        if (inv.freeSlots() == 0 && inv.totalSlots() > 0) {
            out.add(new Alert(n, Alert.Code.INVENTORY_FULL, "inventory full"));
        }
        return out;
    }

    /** The bot's one-word status for a tile: its worst alert, else what it is doing. */
    public static TileStatus status(SwarmView.BotState b, List<Alert> alerts) {
        Alert worst = null;
        for (Alert a : alerts) {
            if (a.bot().equalsIgnoreCase(b.name()) && (worst == null || a.level().ordinal() > worst.level().ordinal())) {
                worst = a;
            }
        }
        if (worst != null) {
            return new TileStatus(worst.level(), worst.code().word);
        }
        BotFeed f = b.feed();
        if (running(f.executions()) || !"idle".equals(f.process())) {
            return new TileStatus(TileStatus.Level.OK, "running");
        }
        return new TileStatus(TileStatus.Level.IDLE, "idle");
    }

    static BotFeed.Execution latest(List<BotFeed.Execution> list) {
        BotFeed.Execution best = null;
        for (BotFeed.Execution e : list) {
            if (e.ageMs() >= 0 && (best == null || e.ageMs() < best.ageMs())) {
                best = e;
            }
        }
        return best;
    }

    static boolean running(List<BotFeed.Execution> list) {
        for (BotFeed.Execution e : list) {
            if ("running".equals(e.execution()) || "accepted".equals(e.execution())) {
                return true;
            }
        }
        return false;
    }

    /** Busy means a process is in control or a path is being calculated or followed. */
    static boolean busy(BotFeed f) {
        return !"idle".equals(f.process()) || "pathing".equals(f.path().state())
                || "calculating".equals(f.path().state());
    }

    /**
     * Window-side stuck detection: per bot, where it was when it became busy (or last moved {@link #STUCK_BLOCKS}).
     * Not thread-safe; the window's refresh thread owns one.
     */
    public static final class Stuck {
        private final Map<String, double[]> anchor = new HashMap<>(); // x, y, z, sinceMs

        /** Records this sample; true when the bot counts as stuck now. */
        public boolean note(String bot, BotFeed f, long nowMs) {
            String k = bot.toLowerCase(Locale.ROOT);
            if (f.pos() == null || f.dead() || !busy(f)) {
                anchor.remove(k);
                return false;
            }
            double[] a = anchor.get(k);
            BotFeed.Pos p = f.pos();
            if (a == null || dist(a, p) >= STUCK_BLOCKS) {
                anchor.put(k, new double[]{p.x(), p.y(), p.z(), nowMs});
                return false;
            }
            return nowMs - (long) a[3] >= STUCK_MS;
        }

        public long stuckForMs(String bot, long nowMs) {
            double[] a = anchor.get(bot.toLowerCase(Locale.ROOT));
            return a == null ? 0 : nowMs - (long) a[3];
        }

        public void forget(String bot) {
            anchor.remove(bot.toLowerCase(Locale.ROOT));
        }

        private static double dist(double[] a, BotFeed.Pos p) {
            double dx = a[0] - p.x(), dy = a[1] - p.y(), dz = a[2] - p.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}
