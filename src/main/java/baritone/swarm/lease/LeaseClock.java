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

    /**
     * Chat whispers are budgeted at 35/min. Ostinato: a renew every D/6 (10 s at D = 60 s) is 6 renews and 6 grants
     * a minute per lease, two lines each; timer resends stay at D/3 ({@link #resendEvery}).
     */
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

    // Ostinato: D/6, was D/3. Live b8r3 (D = 60 s) fenced a kept lease 4 s past its deadline: a renew that was due
    // while the coordinator relaunched waited a whole D/3 backoff step. Twice as many tries leave twice the margin.
    public static long renewEvery(long durationMs) {
        return Math.max(1, durationMs / 6);
    }

    /**
     * Ostinato: timer resends that are not renews (OFFER, REVOKE, RREQ, a pending REQ) and a reconciling coordinator's
     * wait for an unreported chat holder: D/3, the old renew period, so only renews got faster.
     */
    public static long resendEvery(long durationMs) {
        return Math.max(1, durationMs / 3);
    }

    /**
     * Ostinato: shortest gap between two identical replies (REVOKE to a REQ, RACK to a REVOKE) for one lease.
     * Timer resends come every {@link #resendEvery}; anything sooner crossed an earlier reply in flight.
     */
    public static long repeatGap(long durationMs) {
        return Math.max(1, resendEvery(durationMs) / 2);
    }
}
