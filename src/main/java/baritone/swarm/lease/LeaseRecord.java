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

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;

import java.util.UUID;

/**
 * Ostinato: the coordinator's record of one lease. Everything but the monotonic fields is durable.
 */
public final class LeaseRecord {

    public enum State {
        /** Reserved and offered; nothing granted yet. Holds the resource. */
        OFFERED,
        /** Granted at least once. Holds the resource until the coordinator deadline. */
        GRANTED,
        /** Revoke sent; holds the resource until rack or the coordinator deadline. */
        REVOKING,
        /** The bot said it is done (report or rack). Terminal. */
        RELEASED,
        /** Ran out of time. Terminal. */
        EXPIRED;

        public boolean live() {
            return this == OFFERED || this == GRANTED || this == REVOKING;
        }
    }

    public final UUID leaseId;
    public final String resource;
    public final long gen;
    public final UUID commandId;
    public final String bot;
    public final String commandLine;
    public State state;
    /** Wall-clock ms before which no other lease on {@link #resource} may be granted (durable). */
    public long notBeforeWall;
    /** Last known execution of the bound command; null when never reported. */
    public ExecutionState exec;
    public ObservationState obs = ObservationState.UNKNOWN;
    /** Why the lease ended, or null. */
    public String endReason;

    // Monotonic, not persisted.
    public transient long coordDeadlineMono = Long.MIN_VALUE;
    public transient long offeredAtMono;
    public transient long lastGrantMono = Long.MIN_VALUE;
    public transient boolean commandSent;

    public LeaseRecord(UUID leaseId, String resource, long gen, UUID commandId, String bot, String commandLine,
                       State state) {
        this.leaseId = leaseId;
        this.resource = resource;
        this.gen = gen;
        this.commandId = commandId;
        this.bot = bot;
        this.commandLine = commandLine == null ? "" : commandLine;
        this.state = state;
    }

    @Override
    public String toString() {
        return "lease=" + leaseId + " gen=" + gen + " bot=" + bot + " resource=" + resource + " state="
                + state.name().toLowerCase(java.util.Locale.ROOT);
    }
}
