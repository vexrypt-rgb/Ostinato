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

package baritone.pathing.movement.movements;

/**
 * Bound for the "step back, we might be in the way" fallback of a movement that cannot place its block. The step is a
 * plain back key, so it goes wherever the placement aim happens to point away from; unbounded it leaves the source
 * block altogether (in-game: two blocks backwards off the source and into a lava fall).
 */
public final class BackOff {

    private BackOff() {}

    /** How far from the centre of the source block the step back may start (the block edge is at 0.5, we fall off at 0.8). */
    public static final double REACH = 0.3;

    /** Whether a player at (px, pz) may still step back while standing on the source block at (srcX, srcZ). */
    public static boolean allowed(double px, double pz, int srcX, int srcZ) {
        return Math.abs(px - (srcX + 0.5)) < REACH && Math.abs(pz - (srcZ + 0.5)) < REACH;
    }
}
