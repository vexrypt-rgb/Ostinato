package baritone.swarm.center;

import baritone.swarm.SwarmSample;

import java.util.List;

/**
 * Ostinato: text and levels for the swarm link monitor. Pure, so it is tested without a display.
 * A row is one bot: its chat budget used against the limiter's cap per minute, the command its chat
 * link sends with, lines it dropped, frames it retried, the traffic we heard from it, the route it is
 * reached over and how old its sample is. Every figure the bot did not report reads {@code n/a}.
 */
public final class LinkFormatter {

    /** Vanilla kicks for spam somewhere near this many chat lines a minute; shown next to our own cap. */
    public static final int VANILLA_LINES_PER_MIN = 70;
    /** Chat budget use at or above this percent of the cap warns. */
    public static final int CHAT_WARN_PCT = 80;
    /** A sample older than this many samples' worth of time is stale. */
    public static final int STALE_SAMPLES = 3;

    private LinkFormatter() {}

    public static PerfFormatter.Cell chat(SwarmSample s) {
        if (s.chatUsed() < 0) {
            return new PerfFormatter.Cell("chat n/a", TileStatus.Level.IDLE);
        }
        if (s.chatCap() <= 0) {
            return new PerfFormatter.Cell("chat " + s.chatUsed() + "/min", TileStatus.Level.OK);
        }
        long pct = s.chatUsed() * 100L / s.chatCap();
        return new PerfFormatter.Cell("chat " + s.chatUsed() + "/" + s.chatCap() + " per min",
                s.chatUsed() >= s.chatCap() ? TileStatus.Level.BAD
                        : pct >= CHAT_WARN_PCT ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    public static PerfFormatter.Cell command(SwarmSample s) {
        return s.command().isEmpty() ? new PerfFormatter.Cell("cmd n/a", TileStatus.Level.IDLE)
                : new PerfFormatter.Cell("cmd " + s.command(), TileStatus.Level.OK);
    }

    public static PerfFormatter.Cell dropped(SwarmSample s) {
        if (s.dropped() < 0) {
            return new PerfFormatter.Cell("dropped n/a", TileStatus.Level.IDLE);
        }
        return new PerfFormatter.Cell("dropped " + s.dropped(),
                s.dropped() > 0 ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    public static PerfFormatter.Cell retried(SwarmSample s) {
        if (s.retried() < 0) {
            return new PerfFormatter.Cell("retried n/a", TileStatus.Level.IDLE);
        }
        return new PerfFormatter.Cell("retried " + s.retried(),
                s.retried() > 0 ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    /** Lines we heard from this peer in the last minute; negative means not counted (this client). */
    public static PerfFormatter.Cell received(int inWindow) {
        return inWindow < 0 ? new PerfFormatter.Cell("rx n/a", TileStatus.Level.IDLE)
                : new PerfFormatter.Cell("rx " + inWindow + "/min", TileStatus.Level.OK);
    }

    public static PerfFormatter.Cell route(boolean local, boolean self) {
        if (self) {
            return new PerfFormatter.Cell("this client", TileStatus.Level.OK);
        }
        return new PerfFormatter.Cell(local ? "local spool" : "chat", TileStatus.Level.OK);
    }

    /** How old a peer's sample is; {@code sampleAtMs < 0} means none has arrived (an older bot). */
    public static PerfFormatter.Cell age(long sampleAtMs, long nowMs, long intervalMs) {
        if (sampleAtMs < 0) {
            return new PerfFormatter.Cell("no sample", TileStatus.Level.IDLE);
        }
        long sec = Math.max(0, nowMs - sampleAtMs) / 1000;
        return new PerfFormatter.Cell("sample " + sec + " s ago",
                nowMs - sampleAtMs > STALE_SAMPLES * intervalMs ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    /** The link fields of a sample, in monitor order: chat budget, command, dropped, retried. */
    public static List<PerfFormatter.Cell> cells(SwarmSample s) {
        SwarmSample v = s == null ? SwarmSample.UNKNOWN : s;
        return List.of(chat(v), command(v), dropped(v), retried(v));
    }

    /** The caption under the monitor: our cap against the vanilla limit. */
    public static String budgetNote(int cap) {
        return cap <= 0 ? "chat budget n/a (vanilla ~" + VANILLA_LINES_PER_MIN + "/min)"
                : "chat budget " + cap + "/min (vanilla ~" + VANILLA_LINES_PER_MIN + "/min)";
    }
}
