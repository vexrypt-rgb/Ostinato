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

import java.util.UUID;

/**
 * Ostinato: host-mod commands (TenorClef {@code @}) that are not {@code ICommand}s.
 * The swarm protocol calls this only after {@code ICommandManager} has no match,
 * and only when {@code swarmCommandProtocol} is on.
 */
public interface ExternalCommandHook {

    /** True when this hook owns {@code name} (no leading {@code @} or {@code #}). */
    boolean knows(String name);

    /**
     * Start the command once. Return false when it did not start.
     * Must not run when the caller is in dry-run.
     */
    boolean start(UUID commandId, String name, String args);

    /**
     * True only when the process for {@code commandId} has actually stopped.
     * False leaves the command running.
     */
    boolean confirmStopped(UUID commandId);
}
