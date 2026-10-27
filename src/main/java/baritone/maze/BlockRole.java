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

package baritone.maze;

/** One cell of a room plan. The Minecraft block it becomes depends on the theme; the seal check does not. */
public enum BlockRole {
    /** Inside the bounding box but not part of the build. The builder leaves the world block alone. */
    OUTSIDE,
    SHELL,
    FLOOR,
    WALL,
    CEILING,
    AIR,
    LIGHT,
    FEATURE,
    /** Solid plug at the far end of the corridor stub. */
    STUB_CAP;

    public boolean solid() {
        return this != OUTSIDE && this != AIR && this != LIGHT; // Ostinato: the light is a torch, not a full block
    }
}
