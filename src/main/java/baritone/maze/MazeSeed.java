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
 * Deterministic mix from the maze seed and a cell. The same numbers always produce the same room, on every bot.
 * This is not {@link java.util.Random}; the steps are fixed here so a JVM change cannot move a wall.
 */
public final class MazeSeed {

    /** Blocks between cell origins. A room is inset inside this, so neighboring cells do not overlap. */
    public static final int CELL = 32;

    /** Inset of the room's minimum corner from the cell origin. */
    public static final int INSET = 8;

    /** Solid blocks required above every ceiling block before the room counts as hidden from the surface. */
    public static final int COVER = 4;

    private MazeSeed() {}

    public static int cellOf(int world) {
        return Math.floorDiv(world, CELL);
    }

    public static int originX(int cellX) {
        return cellX * CELL + INSET;
    }

    public static int originZ(int cellZ) {
        return cellZ * CELL + INSET;
    }

    /**
     * Stafford-style mix, wrapped to 64 bits the way a Java {@code long} multiply wraps.
     * {@code x} and {@code z} are cell coordinates, or any other stable salt.
     */
    public static long mix(long seed, int x, int z) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h ^= x * 0xBF58476D1CE4E5B9L;
        h ^= z * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    public static long roomSeed(long mazeSeed, int cellX, int cellZ) {
        return mix(mazeSeed, cellX, cellZ);
    }
}
