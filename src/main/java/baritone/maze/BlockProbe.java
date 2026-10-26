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

/**
 * The world, reduced to what the maze seal check needs. No Minecraft types, so tests can stand in for a world.
 */
public interface BlockProbe {

    BlockKind get(int x, int y, int z);

    /** What a block is, as far as "can a player see through this into the maze" cares. */
    enum BlockKind {
        AIR,
        FLUID,
        SOLID,
        /** Unloaded, or outside the world. Not proof of a seal. */
        UNKNOWN
    }
}
