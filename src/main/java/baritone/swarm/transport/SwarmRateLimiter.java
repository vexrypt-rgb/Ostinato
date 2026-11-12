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

/**
 * Outgoing chat limiter: a token bucket ({@code swarmSendRatePerSec}, {@code swarmSendBurst})
 * in front of a priority queue ({@link SwarmPriority#HIGH} first, FIFO within a priority).
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
    private final ArrayDeque<String>[] queues = new ArrayDeque[SwarmPriority.values().length];
    private double tokens;
    private long lastMs;
    private long dropped;
    private long sent;
    private final WindowCounter window = new WindowCounter(CHAT_WINDOW_MS, 4096); // Ostinato:

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

    /**
     * Queue a line. When the queue is full, the newest line of the lowest priority below
     * {@code p} is dropped to make room; if there is none, this line is dropped.
     *
     * @return whether the line was queued
     */
    public synchronized boolean offer(SwarmPriority p, String line) {
        // Ostinato: an identical pending line is already accepted, even when the queue is full.
        if (queues[p.ordinal()].contains(line)) {
            return true;
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
        queues[p.ordinal()].addLast(line);
        return true;
    }

    /** Next line allowed to go out now (highest priority first), or {@code null}. */
    public synchronized String poll(long nowMs) {
        if (nowMs > lastMs) {
            tokens = Math.min(burst, tokens + (nowMs - lastMs) * rate / 1000.0);
            lastMs = nowMs;
        }
        if (tokens < 1.0) {
            return null;
        }
        for (ArrayDeque<String> q : queues) {
            String line = q.pollFirst();
            if (line != null) {
                tokens -= 1.0;
                sent++;
                window.add(nowMs); // Ostinato: link monitor budget.
                return line;
            }
        }
        return null;
    }

    public synchronized int queued() {
        int n = 0;
        for (ArrayDeque<String> q : queues) {
            n += q.size();
        }
        return n;
    }

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
