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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KonamiCodeTest {

    private static final int U = 1, D = 2, L = 3, R = 4, B = 5, A = 6;

    private static boolean feedAll(KonamiCode code, int... keys) {
        boolean last = false;
        for (int k : keys) {
            last = code.feed(k);
        }
        return last;
    }

    @Test
    public void fullCodeFiresOnce() {
        KonamiCode code = new KonamiCode(U, D, L, R, B, A);
        assertTrue(feedAll(code, U, U, D, D, L, R, L, R, B, A));
        assertFalse(code.feed(A));
    }

    @Test
    public void wrongKeyResetsAndExtraUpStillWorks() {
        KonamiCode code = new KonamiCode(U, D, L, R, B, A);
        assertFalse(feedAll(code, U, U, D, B, A));
        assertTrue(feedAll(code, U, U, U, D, D, L, R, L, R, B, A));
    }
}