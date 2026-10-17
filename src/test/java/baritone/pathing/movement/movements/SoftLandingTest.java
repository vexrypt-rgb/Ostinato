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

import org.junit.Test;

import static org.junit.Assert.*;

public class SoftLandingTest {

    @Test
    public void hayTakesAFifth() {
        float m = MovementDescend.HAY_FALL_MULTIPLIER;
        // the first 3 blocks are free anyway, then a fifth of the rest rounded down: 7 blocks is the last free one
        for (int b = 0; b <= 7; b++) {
            assertEquals("fall " + b, 0, MovementDescend.softFallDamage(b, m));
        }
        assertEquals(1, MovementDescend.softFallDamage(8, m));
        assertEquals(4, MovementDescend.softFallDamage(23, m));
        assertEquals(20, MovementDescend.softFallDamage(103, m));
        // a multiplier of 1 is a plain fall
        assertEquals(ExperimentalMovementFallProbe.plain(20), MovementDescend.softFallDamage(20, 1));
    }

    @Test
    public void slimeSettlesInAFewBounces() {
        double prev = 0;
        for (int b : new int[]{3, 4, 6, 10, 20, 40, 80, 200}) {
            double t = MovementDescend.slimeSettleTicks(b);
            assertTrue("fall " + b, t >= 1);
            // a bigger fall bounces higher and longer, but drag caps it: a terminal-speed landing still settles within 20 seconds
            assertTrue("fall " + b + " " + t + " < " + prev, t >= prev - 1);
            assertTrue("fall " + b + " " + t, t < 400);
            prev = t;
        }
        // memoized, the same answer twice
        assertEquals(MovementDescend.slimeSettleTicks(40), MovementDescend.slimeSettleTicks(40), 0);
        // out of range is clamped, not thrown
        assertTrue(MovementDescend.slimeSettleTicks(100000) >= 1);
        assertTrue(MovementDescend.slimeSettleTicks(-5) >= 1);
    }

    @Test
    public void slimeFromLowLandsAtOnce() {
        // under the sneak threshold the very first landing is the one we keep
        assertEquals(1, MovementDescend.slimeSettleTicks(2), 0);
    }

    /** ExperimentalMovement.fallDamage, which softFallDamage with no multiplier has to agree with. */
    private static final class ExperimentalMovementFallProbe {
        static int plain(int blocks) {
            return baritone.utils.ExperimentalMovement.fallDamage(blocks);
        }
    }
}
