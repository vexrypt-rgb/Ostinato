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

package baritone.api.swarm.protocol;

/**
 * Ostinato: what the bot is doing with a command. {@code unknown} is not a value here.
 * Freshness of that fact lives in {@link ObservationState}.
 */
public enum ExecutionState {
    ACCEPTED("accepted"),
    RUNNING("running"),
    SUCCEEDED("succeeded (verified)"),
    FAILED("failed (verified)"),
    CANCELED("canceled (confirmed)");

    private final String label;

    ExecutionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    public static ExecutionState parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("missing execution state");
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("unknown".equals(s)) {
            throw new IllegalArgumentException("unknown is not an execution state");
        }
        for (ExecutionState e : values()) {
            if (e.name().equalsIgnoreCase(raw.trim()) || e.label.equals(s)) {
                return e;
            }
        }
        throw new IllegalArgumentException("not an execution state: " + raw);
    }

    /**
     * Legal moves only: null to accepted, accepted to running or a terminal failure or cancel,
     * running to a verified or confirmed terminal. Anything else is refused.
     */
    public static ExecutionState transit(ExecutionState from, ExecutionState to) {
        if (to == null) {
            throw new IllegalStateException("null execution state");
        }
        if (from == null) {
            if (to != ACCEPTED) {
                throw new IllegalStateException("illegal transition null -> " + to);
            }
            return to;
        }
        if (from == to) {
            return to;
        }
        if (from == ACCEPTED && (to == RUNNING || to == CANCELED || to == FAILED)) {
            return to;
        }
        if (from == RUNNING && (to == SUCCEEDED || to == FAILED || to == CANCELED)) {
            return to;
        }
        throw new IllegalStateException("illegal transition " + from + " -> " + to);
    }
}
