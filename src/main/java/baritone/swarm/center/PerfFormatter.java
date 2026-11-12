package baritone.swarm.center;

import baritone.swarm.SwarmSample;

import java.util.List;

/**
 * Ostinato: text and levels for the performance strip. Pure, so it is tested without a display.
 * An unknown figure is the word {@code n/a} at {@link TileStatus.Level#IDLE}, never a zero.
 */
public final class PerfFormatter {

    /** One strip cell: its text and how worried the strip should look about it. */
    public record Cell(String text, TileStatus.Level level) {}

    public static final long PATH_WARN_MS = 500;
    public static final long PATH_BAD_MS = 2_000;
    public static final int TPS_WARN_X10 = 150;
    public static final int TPS_BAD_X10 = 100;
    public static final int PING_WARN_MS = 150;
    public static final int PING_BAD_MS = 400;
    public static final int RAM_WARN_PCT = 85;
    public static final int RAM_BAD_PCT = 95;

    private PerfFormatter() {}

    public static Cell path(SwarmSample s) {
        if (s.pathMs() < 0) {
            return new Cell("path n/a", TileStatus.Level.IDLE);
        }
        return new Cell("path " + s.pathMs() + " ms",
                s.pathMs() >= PATH_BAD_MS ? TileStatus.Level.BAD
                        : s.pathMs() >= PATH_WARN_MS ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    public static Cell tps(SwarmSample s) {
        if (s.tpsX10() < 0) {
            return new Cell("tps n/a", TileStatus.Level.IDLE);
        }
        return new Cell((s.tpsX10() / 10) + "." + (s.tpsX10() % 10) + " tps",
                s.tpsX10() < TPS_BAD_X10 ? TileStatus.Level.BAD
                        : s.tpsX10() < TPS_WARN_X10 ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    public static Cell ping(SwarmSample s) {
        if (s.pingMs() < 0) {
            return new Cell("ping n/a", TileStatus.Level.IDLE);
        }
        return new Cell("ping " + s.pingMs() + " ms",
                s.pingMs() > PING_BAD_MS ? TileStatus.Level.BAD
                        : s.pingMs() > PING_WARN_MS ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    public static Cell ram(SwarmSample s) {
        if (s.ramUsedMb() < 0) {
            return new Cell("ram n/a", TileStatus.Level.IDLE);
        }
        if (s.ramMaxMb() <= 0) {
            return new Cell("ram " + s.ramUsedMb() + " MB", TileStatus.Level.OK);
        }
        long pct = s.ramUsedMb() * 100L / s.ramMaxMb();
        return new Cell("ram " + s.ramUsedMb() + "/" + s.ramMaxMb() + " MB",
                pct >= RAM_BAD_PCT ? TileStatus.Level.BAD
                        : pct >= RAM_WARN_PCT ? TileStatus.Level.WARN : TileStatus.Level.OK);
    }

    /** Path, tick rate, ping and RAM, in strip order. */
    public static List<Cell> cells(SwarmSample s) {
        SwarmSample v = s == null ? SwarmSample.UNKNOWN : s;
        return List.of(path(v), tps(v), ping(v), ram(v));
    }

    /** One line for a tile, e.g. {@code "path 12 ms · 20.0 tps · ping 35 ms · ram 812/2048 MB"}. */
    public static String summary(SwarmSample s) {
        if (s == null || s.isUnknown()) {
            return "n/a";
        }
        StringBuilder b = new StringBuilder();
        for (Cell c : cells(s)) {
            if (b.length() > 0) {
                b.append(" · ");
            }
            b.append(c.text());
        }
        return b.toString();
    }

    /** The worst level among known figures, IDLE when nothing is known. */
    public static TileStatus.Level worst(List<Cell> cells) {
        TileStatus.Level worst = TileStatus.Level.IDLE;
        for (Cell c : cells) {
            if (rank(c.level()) > rank(worst)) {
                worst = c.level();
            }
        }
        return worst;
    }

    static int rank(TileStatus.Level level) {
        switch (level) {
            case BAD: return 3;
            case WARN: return 2;
            case OK: return 1;
            default: return 0;
        }
    }
}
