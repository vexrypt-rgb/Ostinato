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

package baritone.swarm.lease;

/**
 * Ostinato: the conservative lease clock. No clock is shared between machines.
 * <ul>
 * <li>The bot stamps each req/renew with its own monotonic time {@code t0}; a grant echoes it, so the bot is
 * authorised until {@code t0 + D} on its own clock. The grant left the coordinator after {@code t0}, so the
 * bot's window always ends no later than the coordinator thinks.</li>
 * <li>The coordinator counts the lease live until {@code lastGrantSend + D + M} on its monotonic clock.
 * {@code M} covers tick granularity and the bot noticing expiry one tick late.</li>
 * <li>Across a coordinator restart the monotonic clock is gone, so the durable record keeps wall time
 * {@code send + D + M + skew}, where skew bounds the wall clock jumping backwards across the restart.</li>
 * </ul>
 */
public final class LeaseClock {

    /** Chat whispers are budgeted at 35/min; a renew every D/3 must leave room for the rest. */
    public static final long CHAT_MIN_DURATION_MS = 60_000L;
    public static final long MIN_DURATION_MS = 5_000L;

    private LeaseClock() {}

    public static long botAuthUntil(long nonceMono, long durationMs) {
        return nonceMono + durationMs;
    }

    public static long coordDeadline(long sendMono, long durationMs, long marginMs) {
        return sendMono + durationMs + marginMs;
    }

    public static long notBeforeWall(long sendWall, long durationMs, long marginMs, long skewMs) {
        return sendWall + durationMs + marginMs + Math.max(0, skewMs);
    }

    /** The lease length for a peer: chat peers get at least {@link #CHAT_MIN_DURATION_MS}. */
    public static long effectiveDuration(long configuredMs, boolean chatPeer) {
        long d = Math.max(MIN_DURATION_MS, configuredMs);
        return chatPeer ? Math.max(CHAT_MIN_DURATION_MS, d) : d;
    }

    public static long renewEvery(long durationMs) {
        return Math.max(1, durationMs / 3);
    }
}
