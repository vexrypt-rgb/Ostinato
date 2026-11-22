package baritone.swarm.center.app;

import baritone.swarm.center.feed.Alert;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato: Phase 3 window, the words in the wall's focus panel and the tile's perf strip. Pure, so the wording is
 * unit tested without Swing. The tile and the panel both call these, so they cannot drift.
 */
public final class TileDetails {

    private TileDetails() {}

    /** One-line perf strip for a tile: TPS, ping, RAM. Unknown figures say n/a. */
    public static String perfStrip(BotFeed.Perf p) {
        return tps(p) + " tps · " + ms(p.pingMs()) + " · " + ram(p);
    }

    /** Path position / length while a path is running, otherwise empty. */
    public static String progress(BotFeed.Route path) {
        if (path == null || "idle".equals(path.state()) || path.length() <= 0) {
            return "";
        }
        return path.position() + "/" + path.length();
    }

    public static String dimension(BotFeed f) {
        return f.pos() == null ? "not in a world" : TileText.dim(f.pos().dim());
    }

    /** State, goal, distance, node count, position / length. */
    public static String path(BotFeed.Route path) {
        BotFeed.Route r = path == null ? BotFeed.Route.IDLE : path;
        String goal = r.goal().isEmpty() ? "no goal" : r.goal();
        String distance = r.distance() < 0 ? "distance n/a" : String.format(Locale.ROOT, "%.1f blocks", r.distance());
        return r.state() + " · " + goal + " · " + distance + " · " + r.nodes().size() + " nodes · "
                + r.position() + "/" + r.length();
    }

    /** Command, issuer, state, observation, age, end reason. */
    public static String execution(BotFeed.Execution e) {
        String age = e.ageMs() < 0 ? "age n/a" : (e.ageMs() / 1000) + " s";
        String end = e.endReason().isEmpty() ? "no end reason" : e.endReason();
        return e.command() + " · " + (e.issuer().isEmpty() ? "no issuer" : e.issuer()) + " · " + e.execution()
                + " · " + e.observation() + " · " + age + " · " + end;
    }

    public static String executions(List<BotFeed.Execution> list) {
        if (list == null || list.isEmpty()) {
            return "no commands";
        }
        List<String> lines = new ArrayList<>();
        for (BotFeed.Execution e : list) {
            lines.add(execution(e));
        }
        return String.join(" | ", lines);
    }

    /** Resource, generation, phase, time left. */
    public static String lease(BotFeed.HeldLease l) {
        String left = l.leftMs() < 0 ? "time n/a" : (l.leftMs() / 1000) + " s left";
        return l.resource() + " · gen " + l.gen() + " · " + l.phase() + " · " + left;
    }

    public static String leases(List<BotFeed.HeldLease> list) {
        if (list == null || list.isEmpty()) {
            return "no leases";
        }
        List<String> lines = new ArrayList<>();
        for (BotFeed.HeldLease l : list) {
            lines.add(lease(l));
        }
        return String.join(" | ", lines);
    }

    /** Path ms, TPS, ping, RAM used / max, chat used / cap, dropped, retried. */
    public static String perf(BotFeed.Perf p) {
        BotFeed.Perf f = p == null ? BotFeed.Perf.UNKNOWN : p;
        return "path " + ms(f.pathMs()) + " · " + tps(f) + " tps · ping " + ms(f.pingMs()) + " · ram " + ram(f)
                + " · chat " + pair(f.chatUsed(), f.chatCap()) + " · dropped " + num(f.dropped())
                + " · retried " + num(f.retried());
    }

    /** Up, peers seen, failure. */
    public static String link(BotFeed.Link l) {
        BotFeed.Link f = l == null ? BotFeed.Link.DOWN : l;
        String failure = f.failure().isEmpty() ? "no failure" : f.failure();
        return (f.up() ? "up" : "down") + " · peers " + f.peersSeen() + " · " + failure;
    }

    /** Feed age and how fresh it is. */
    public static String age(SwarmView.BotState b) {
        return (b.ageMs() / 1000) + " s · " + b.freshness().name().toLowerCase(Locale.ROOT);
    }

    public static String gather(BotFeed f) {
        return f.gatherUs() < 0 ? "gather n/a" : "gather " + f.gatherUs() + " µs";
    }

    public static String alerts(List<Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return "no alerts";
        }
        List<String> lines = new ArrayList<>();
        for (Alert a : alerts) {
            lines.add(a.badge());
        }
        return String.join(" | ", lines);
    }

    private static String tps(BotFeed.Perf p) {
        return p.tpsX10() < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", p.tpsX10() / 10.0);
    }

    private static String ram(BotFeed.Perf p) {
        return pair(p.ramUsedMb(), p.ramMaxMb()) + " MB";
    }

    private static String pair(long used, long max) {
        return num(used) + "/" + num(max);
    }

    private static String ms(long v) {
        return v < 0 ? "n/a" : v + " ms";
    }

    private static String num(long v) {
        return v < 0 ? "n/a" : Long.toString(v);
    }
}
