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

public class BackOffTest {

    @Test
    public void allowedNearTheCentreOfTheSource() {
        assertTrue(BackOff.allowed(-1103.5, 435.5, -1104, 435));
        assertTrue(BackOff.allowed(-1103.3, 435.7, -1104, 435));
    }

    @Test
    public void refusedOnceOffCentreOnEitherAxis() {
        assertFalse(BackOff.allowed(-1103.85, 435.5, -1104, 435));
        assertFalse(BackOff.allowed(-1103.5, 435.15, -1104, 435));
    }

    @Test
    public void refusedOnANeighbouringBlock() {
        // the in-game case: one and two blocks west of the source, still walking backwards
        assertFalse(BackOff.allowed(-1104.5, 435.5, -1104, 435));
        assertFalse(BackOff.allowed(-1105.5, 435.5, -1104, 435));
    }

    @Test
    public void aFullBackwardStepFromTheLimitStaysOnTheBlock() {
        // walking backwards covers at most ~0.22 a tick; the feet leave the block 0.8 from its centre
        double worst = BackOff.REACH + 0.22;
        assertTrue(worst < 0.8);
    }
}
