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

package baritone.api.command;

/**
 * Ostinato: who may run a registry command through the swarm command protocol.
 * Chat {@code #} lines issued by the local player are unchanged.
 */
public enum CommandPermission {
    /** The configured owner (or the local player, when no owner name is set). */
    OWNER,
    /** A member of the sender's SIGIL roster. */
    SWARM,
    /** An AI tool call, checked against {@code AgentPolicy}. */
    AI;

    public static CommandPermission parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OWNER;
        }
        return CommandPermission.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
