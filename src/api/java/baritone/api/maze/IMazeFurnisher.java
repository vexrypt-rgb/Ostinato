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

package baritone.api.maze;

/**
 * Ostinato: something that gathers materials and carries out a {@link MazeFurnishJob}, usually TenorClef's task
 * system. Ostinato only plans the job; it does not gather, open containers, or move items itself.
 */
public interface IMazeFurnisher {

    /**
     * Takes the job and starts on it. Returns false to refuse (busy, not in a world), and Ostinato reports that.
     * Called on the client thread; do the work on later ticks, not inside this call.
     */
    boolean furnish(MazeFurnishJob job);

    default String name() {
        return getClass().getSimpleName();
    }
}
