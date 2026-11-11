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
 * Ostinato: how fresh the report of an {@link ExecutionState} is.
 * {@link #UNKNOWN} is never an execution result.
 */
public enum ObservationState {
    CONFIRMED("confirmed"),
    STALE("stale"),
    UNKNOWN("unknown");

    private final String label;

    ObservationState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ObservationState parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("missing observation state");
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (ObservationState o : values()) {
            if (o.name().equalsIgnoreCase(raw.trim()) || o.label.equals(s)) {
                return o;
            }
        }
        throw new IllegalArgumentException("not an observation state: " + raw);
    }
}
