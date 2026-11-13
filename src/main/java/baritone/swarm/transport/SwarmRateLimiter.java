/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.swarm.transport;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Outgoing chat limiter: a token bucket ({@code swarmSendRatePerSec}, {@code swarmSendBurst})
 * in front of a priority queue ({@link SwarmPriority#URGENT} first, FIFO within a priority).
 *
 * <p>Vanilla 1.16.1 {@code ServerGamePacketListenerImpl} (verified from the 1.16.1 server jar
 * with Mojang's mappings): every chat packet, commands included, does
 * {@code chatSpamTickCount += 20} and disconnects non-ops with {@code disconnect.spam} when the
 * count is {@code > 200}; {@code tick()} decrements it by 1 while positive. At 20 TPS that is
 * a neutral rate of exactly 1 message/s and at most 10 back-to-back messages from zero (the
 * 11th kicks). A token bucket sends at most {@code burst + rate*T} messages in any window
 * {@code T}, so the server counter never exceeds {@code 20*burst} while {@code rate <= 1/s}.
 *
 * <p>Unless {@code allowUnsafe}, the rate is clamped to {@link #SAFE_MAX_RATE} and the burst to
 * {@link #SAFE_MAX_BURST} (peak 160 of 200), leaving room for network jitter and the player's
 * own chat. Server lag slows the decay (it is per tick), so the defaults (0.5/s, burst 5) keep
 * a margin down to 10 TPS.
 */
public final class SwarmRateLimiter {

    public static final double SAFE_MAX_RATE = 0.9;
    public static final int SAFE_MAX_BURST = 8;
    public static final double MIN_RATE = 0.05;
    /** Ostinato: window the link monitor counts chat lines over. */
    public static final long CHAT_WINDOW_MS = 60_000L;

    private final double rate;
    private final int burst;
    private final int queueMax;
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Entry>[] queues = new ArrayDeque[SwarmPriority.values().length];
    private double tokens;
    private long lastMs;
    private long dropped;
    private long sent;
    private final WindowCounter window = new WindowCounter(CHAT_WINDOW_MS, 4096); // Ostinato:
    // Ostinato: when a line last left for each recipient ("*" = a line every member reads), on this clock.
    private final Map<String, Long> lastSentTo = new HashMap<>();
    private long withdrawn; // Ostinato: TELEMETRY lines taken back because real traffic needed the link
    private long expiredQueued; // Ostinato: lines whose sender gave up while they still waited here
    private Consumer<String> log = s -> {}; // Ostinato:

    /**
     * Ostinato: what the sender knows about a line's useful life. If the line is still queued {@code expiresInMs}
     * after it was offered, the limiter logs {@code label} once: the sender gave up before the line ever left.
     */
    public record Deadline(long expiresInMs, String label) {
    }

    // Ostinato: one queued line, with whom it goes to and an optional deadline on this limiter's clock.
    private static final class Entry {
        final String line;
        final String to;
        final long expiresAtMs;
        final String label;
        boolean expiryLogged;

        Entry(String line, String to, long expiresAtMs, String label) {
            this.line = line;
            this.to = to;
            this.expiresAtMs = expiresAtMs;
            this.label = label;
        }
    }

    public SwarmRateLimiter(double ratePerSec, int burst, boolean allowUnsafe, int queueMax, long nowMs) {
        double r = Double.isNaN(ratePerSec) ? MIN_RATE : Math.max(MIN_RATE, ratePerSec);
        int b = Math.max(1, burst);
        if (!allowUnsafe) {
            r = Math.min(r, SAFE_MAX_RATE);
            b = Math.min(b, SAFE_MAX_BURST);
        }
        this.rate = r;
        this.burst = b;
        this.queueMax = Math.max(1, queueMax);
        this.tokens = b;
        this.lastMs = nowMs;
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ArrayDeque<>();
        }
    }

    public double rate() { return rate; }
    public int burst() { return burst; }

    /** Ostinato: where the one-off "expired while still queued" lines go. */
    public synchronized void setLog(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    /**
     * Queue a line. When the queue is full, the newest line of the lowest priority below
     * {@code p} is dropped to make room; if there is none, this line is dropped.
     *
     * @return whether the line was queued
     */
    public boolean offer(SwarmPriority p, String line) {
        return offer(p, line, null, null);
    }

    /**
     * Ostinato: {@link #offer(SwarmPriority, String)} for a line going to {@code to} (a member, {@code *} for a line
     * every member reads, or null when unknown). A TELEMETRY line is refused while {@link #telemetryRoom(long)} is
     * false.
     */
    public synchronized boolean offer(SwarmPriority p, String line, String to, Deadline deadline) {
        // Ostinato: an identical pending line is already accepted, even when the queue is full.
        for (Entry e : queues[p.ordinal()]) {
            if (e.line.equals(line)) {
                return true;
            }
        }
        if (p == SwarmPriority.TELEMETRY && !telemetryRoom(lastMs)) {
            withdrawn++;
            return false;
        }
        if (queued() >= queueMax) {
            boolean made = false;
            for (int i = queues.length - 1; i > p.ordinal(); i--) {
                if (!queues[i].isEmpty()) {
                    queues[i].pollLast();
                    made = true;
                    break;
                }
            }
            dropped++;
            if (!made) {
                return false;
            }
        }
        long expires = deadline == null ? Long.MAX_VALUE : lastMs + Math.max(0L, deadline.expiresInMs());
        queues[p.ordinal()].addLast(new Entry(line, key(to), expires, deadline == null ? null : deadline.label()));
        return true;
    }

    /** Next line allowed to go out now (highest priority first), or {@code null}. */
    public synchronized String poll(long nowMs) {
        if (nowMs > lastMs) {
            tokens = Math.min(burst, tokens + (nowMs - lastMs) * rate / 1000.0);
            lastMs = nowMs;
        }
        withdrawTelemetry(nowMs); // Ostinato:
        logExpired(nowMs); // Ostinato:
        if (tokens < 1.0) {
            return null;
        }
        for (ArrayDeque<Entry> q : queues) {
            Entry e = q.pollFirst();
            if (e != null) {
                tokens -= 1.0;
                sent++;
                window.add(nowMs); // Ostinato: link monitor budget.
                if (e.to != null) {
                    lastSentTo.put(e.to, nowMs); // Ostinato: liveness for the heartbeat skip
                }
                return e.line;
            }
        }
        return null;
    }

    /**
     * Ostinato: whether TELEMETRY may use the link now: nothing URGENT or HIGH waits and the last minute used under
     * half of {@link #windowCap()}.
     */
    public synchronized boolean telemetryRoom(long nowMs) {
        return queues[SwarmPriority.URGENT.ordinal()].isEmpty() && queues[SwarmPriority.HIGH.ordinal()].isEmpty()
                && 2 * window.count(nowMs) < windowCap();
    }

    // Ostinato: queued samples are stale the moment real traffic needs the link; take them back rather than send.
    private void withdrawTelemetry(long nowMs) {
        ArrayDeque<Entry> t = queues[SwarmPriority.TELEMETRY.ordinal()];
        if (!t.isEmpty() && !telemetryRoom(nowMs)) {
            withdrawn += t.size();
            t.clear();
        }
    }

    // Ostinato: one log line per line whose sender gave up on it while it still waited for a token.
    private void logExpired(long nowMs) {
        int ahead = 0;
        for (ArrayDeque<Entry> q : queues) {
            for (Iterator<Entry> it = q.iterator(); it.hasNext(); ahead++) {
                Entry e = it.next();
                if (e.expiresAtMs != Long.MAX_VALUE && !e.expiryLogged && nowMs >= e.expiresAtMs) {
                    e.expiryLogged = true;
                    expiredQueued++;
                    log.accept("swarm: " + e.label + " expired still queued for " + (e.to == null ? "?" : e.to)
                            + " (" + ahead + " lines ahead of it, " + queued() + " queued)");
                }
            }
        }
    }

    /**
     * Ostinato: whether a line left for {@code peer} (or for every member) less than {@code windowMs} before
     * {@code nowMs}, on this limiter's clock.
     */
    public synchronized boolean sentWithin(String peer, long nowMs, long windowMs) {
        Long to = lastSentTo.get(key(peer));
        Long all = lastSentTo.get(SwarmTransport.BROADCAST);
        long last = Math.max(to == null ? Long.MIN_VALUE : to, all == null ? Long.MIN_VALUE : all);
        return last != Long.MIN_VALUE && nowMs - last < windowMs;
    }

    private static String key(String to) {
        return to == null ? null : to.toLowerCase(Locale.ROOT);
    }

    public synchronized int queued() {
        int n = 0;
        for (ArrayDeque<Entry> q : queues) {
            n += q.size();
        }
        return n;
    }

    /** Ostinato: lines waiting at {@code p}. */
    public synchronized int queued(SwarmPriority p) { return queues[p.ordinal()].size(); }
    public synchronized long withdrawn() { return withdrawn; }
    public synchronized long expiredQueued() { return expiredQueued; }
    public synchronized long dropped() { return dropped; }
    public synchronized long sent() { return sent; }

    /** Ostinato: lines that went out in the last {@link #CHAT_WINDOW_MS}. */
    public synchronized int sentInWindow(long nowMs) { return window.count(nowMs); }

    /** Ostinato: most lines this bucket lets out in any {@link #CHAT_WINDOW_MS}: {@code rate*window + burst}. */
    public int windowCap() { return (int) Math.floor(rate * CHAT_WINDOW_MS / 1000.0) + burst; }

    /**
     * Ostinato: room for telemetry. True only while nothing waits to go out and the last minute used under half
     * of {@link #windowCap()}, so heartbeat samples back off before real traffic brings the link near its cap.
     */
    public synchronized boolean quiet(long nowMs) {
        return queued() == 0 && 2 * window.count(nowMs) < windowCap();
    }
}
