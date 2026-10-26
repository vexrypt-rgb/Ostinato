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
 * Ostinato: where a client mod hands Ostinato its {@link IMazeFurnisher}. There is at most one. With none registered,
 * {@code #maze.backrooms furnish} only prints the job and its material list.
 */
public final class MazeFurnishers {

    private static volatile IMazeFurnisher furnisher;

    private MazeFurnishers() {}

    public static void register(IMazeFurnisher value) {
        furnisher = value;
    }

    public static void clear() {
        furnisher = null;
    }

    /** Null when nothing is registered. */
    public static IMazeFurnisher get() {
        return furnisher;
    }
}
