package baritone.swarm;

import java.util.ArrayDeque;

/**
 * Ostinato: client tick rate over a sliding wall-clock window, for the swarm perf strip.
 * Pure: the caller passes the clock. Reports tenths of a tick per second, or -1 until the
 * window has seen at least {@code minSpanMs} of ticks.
 */
public final class TickRateMeter {

    private final long windowMs;
    private final long minSpanMs;
    private final ArrayDeque<Long> ticks = new ArrayDeque<>();

    public TickRateMeter(long windowMs, long minSpanMs) {
        this.windowMs = Math.max(1L, windowMs);
        this.minSpanMs = Math.max(1L, Math.min(minSpanMs, this.windowMs));
    }

    public TickRateMeter() {
        this(5_000L, 1_000L);
    }

    public synchronized void tick(long nowMs) {
        ticks.addLast(nowMs);
        trim(nowMs);
        // Hard bound: 20 TPS for 5 s is 100; anything far past that is a runaway caller.
        while (ticks.size() > 4096) {
            ticks.pollFirst();
        }
    }

    /** Ticks per second times ten, or -1 when there is not enough recent data. */
    public synchronized int tpsX10(long nowMs) {
        trim(nowMs);
        if (ticks.size() < 2) {
            return -1;
        }
        long span = nowMs - ticks.peekFirst();
        if (span < minSpanMs) {
            return -1;
        }
        // Intervals between ticks in the window, measured up to now so a stall drags the rate down.
        return (int) Math.round((ticks.size() - 1) * 10_000.0 / span);
    }

    private void trim(long nowMs) {
        while (!ticks.isEmpty() && nowMs - ticks.peekFirst() > windowMs) {
            ticks.pollFirst();
        }
    }
}
