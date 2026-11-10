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

package baritone.api.process;

/**
 * Ostinato 2.0 Phase 08: what became of one request to a process. Each request gets a new {@code request} number, so
 * a caller that remembers the number it started can tell its own result from a later request's. Terminal states stick:
 * once a request has SUCCEEDED, FAILED, been CANCELLED or ended UNKNOWN, later events for it are ignored.
 * <p>
 * SUCCEEDED means the process saw its postcondition (for an interaction: the server opened the matching container),
 * not merely that it stopped. UNKNOWN means control was lost after an irreversible action was sent, so the server may
 * still act on it.
 */
public record ProcessOutcome(long request, State state, Reason reason) {

    public enum State {
        REQUESTED, ACCEPTED, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNKNOWN;
        }
    }

    public enum Reason {
        NONE, UNREACHABLE, INVALIDATED_GOAL, MISSING_RESOURCE, REJECTED, TIMEOUT, WORLD_CHANGED, OWNERSHIP_LOST, CANCELLED, UNKNOWN
    }

    /** No request yet, or a process that does not track outcomes. */
    public static final ProcessOutcome NONE = new ProcessOutcome(0, State.UNKNOWN, Reason.NONE);

    /** A new request, accepted. */
    public ProcessOutcome accept() {
        return new ProcessOutcome(request + 1, State.ACCEPTED, Reason.NONE);
    }

    /** The accepted request started running; any other state is kept. */
    public ProcessOutcome running() {
        return state == State.ACCEPTED ? new ProcessOutcome(request, State.RUNNING, Reason.NONE) : this;
    }

    /** End this request; a request that already ended keeps its first result. */
    public ProcessOutcome end(State end, Reason why) {
        if (!end.terminal()) {
            throw new IllegalArgumentException(end + " is not terminal");
        }
        return terminal() ? this : new ProcessOutcome(request, end, why);
    }

    public boolean terminal() {
        return state.terminal();
    }

    public boolean succeeded() {
        return state == State.SUCCEEDED;
    }
}
