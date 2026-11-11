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

import java.util.List;

/**
 * Ostinato: read-only status. Closing the view must not cancel or pause commands.
 * A desktop window is optional; bots do not need this view in order to run.
 */
public interface ISwarmStatusView extends AutoCloseable {

    List<String> lines();

    boolean isOpen();

    @Override
    void close();
}
