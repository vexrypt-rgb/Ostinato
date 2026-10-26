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
 * Ostinato: watches key presses for up up down down left right left right B A.
 * The maze easter egg toggles when this returns true. Key codes are passed in so this has no Minecraft types.
 */
public final class KonamiCode {

    private final int[] sequence;
    private int matched;

    public KonamiCode(int up, int down, int left, int right, int b, int a) {
        this.sequence = new int[]{up, up, down, down, left, right, left, right, b, a};
    }

    /** Returns true on the key that finishes the code, then starts over. */
    public boolean feed(int key) {
        if (key == sequence[matched]) {
            matched++;
        } else {
            // a wrong key can still start the code again (UP after UP UP keeps two matched)
            matched = key == sequence[0] ? (matched >= 2 && sequence[matched - 1] == key ? matched : 1) : 0;
        }
        if (matched == sequence.length) {
            matched = 0;
            return true;
        }
        return false;
    }
}