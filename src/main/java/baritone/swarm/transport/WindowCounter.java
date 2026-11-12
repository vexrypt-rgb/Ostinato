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
 * Ostinato: how many events happened in the last {@code windowMs}, for the link monitor's chat budget and
 * per-peer traffic. Bounded: past {@code maxEvents} the oldest are forgotten, so the count saturates
 * instead of growing without limit. Not thread safe; callers synchronize.
 */
public final class WindowCounter {

    private final long windowMs;
    private final int maxEvents;
    private final ArrayDeque<Long> times = new ArrayDeque<>();
    private long total;

    public WindowCounter(long windowMs, int maxEvents) {
        this.windowMs = Math.max(1, windowMs);
        this.maxEvents = Math.max(1, maxEvents);
    }

    public long windowMs() { return windowMs; }

    public void add(long nowMs) {
        prune(nowMs);
        if (times.size() >= maxEvents) {
            times.pollFirst();
        }
        times.addLast(nowMs);
        total++;
    }

    /** Events in {@code (now - windowMs, now]}. */
    public int count(long nowMs) {
        prune(nowMs);
        return times.size();
    }

    /** Every event ever added. */
    public long total() { return total; }

    private void prune(long nowMs) {
        while (!times.isEmpty() && times.peekFirst() <= nowMs - windowMs) {
            times.pollFirst();
        }
    }
}
