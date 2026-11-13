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

/**
 * Outgoing priority. {@link #URGENT} is sent first, {@link #LOW} last; order within one
 * priority is FIFO. (Declaration order is the send order: lower ordinal = sent sooner.)
 */
public enum SwarmPriority {
    /**
     * Ostinato: lease reconciliation after a coordinator relaunch (RREQ, REPORT, the renew it triggers and its GRANT):
     * a held lease's deadline is running. Shares the token bucket with everything else, so it never exceeds the budget.
     */
    URGENT,
    /** Time-critical replies (pong, later acks/cancels). */
    HIGH,
    /** Ordinary requests (ping, later assignments). */
    NORMAL,
    /** Bulk/status traffic that may wait or be dropped first. */
    LOW,
    /**
     * Ostinato: status samples. Sent last, and the rate limiter withdraws them while URGENT or HIGH traffic waits or
     * the last minute used half the link's cap: a sample never delays a lease message or a command.
     */
    TELEMETRY
}
