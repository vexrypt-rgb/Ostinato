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

package baritone.utils;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Ostinato: fastMode corner cuts and flat shortcuts. No world and no settings. */
public class FastTravelTest {

    @Test
    public void straightIsNotACutAndARightAngleIsNotShallow() {
        assertEquals(0, FastTravel.turnDegrees(1, 0, 2, 0), 1e-6);
        assertEquals(90, FastTravel.turnDegrees(1, 0, 0, 1), 1e-6);
        assertEquals(180, FastTravel.turnDegrees(1, 0, -1, 0), 1e-6);
        assertFalse(FastTravel.shallow(0));
        assertFalse(FastTravel.shallow(90));
        assertTrue(FastTravel.shallow(45));
        assertTrue(FastTravel.shallow(60));
    }

    @Test
    public void shallowCornerAimsPastTheCornerAndKeepsSprint() {
        FastTravel.Steer steer = FastTravel.choose(1, 0, 1, 1, 5, 10, 7, 6, 8, 1, 5, 7, true, false, false);
        assertTrue(steer.aim);
        assertTrue(steer.keepSprint);
        double step = FastTravel.LEAD / Math.sqrt(2);
        assertEquals(5.5 + step, steer.x, 1e-9);
        assertEquals(7.5 + step, steer.z, 1e-9);
    }

    @Test
    public void widerBendAimsAtTheFarBlockOnlyWhenTheBodyFits() {
        FastTravel.Steer cut = FastTravel.choose(1, 0, 0, 1, 0, 2, 0, 0, 1, 1, 0, 0, true, true, false);
        assertTrue(cut.aim);
        assertFalse(cut.keepSprint);
        assertEquals(0.5, cut.x, 0);
        assertEquals(1.5, cut.z, 0);
        assertFalse(FastTravel.choose(1, 0, 0, 1, 0, 2, 0, 0, 1, 1, 0, 0, true, false, false).aim);
    }

    @Test
    public void straightRunAimsAtTheFarStep() {
        FastTravel.Steer steer = FastTravel.choose(1, 0, 1, 0, 0, 4, 0, 1, 0, 4, 3, 0, true, false, true);
        assertTrue(steer.aim);
        assertFalse(steer.keepSprint);
        assertEquals(3.5, steer.x, 0);
        assertEquals(0.5, steer.z, 0);
        assertFalse(FastTravel.choose(1, 0, 1, 0, 0, 4, 0, 1, 0, 2, 1, 0, true, false, false).aim);
    }

    @Test
    public void lowHealthNetherAndWorkRefuseTheCut() {
        assertFalse(FastTravel.sprintCutAllowed(6, false, false, false));
        assertTrue(FastTravel.sprintCutAllowed(6.1, false, false, false));
        assertFalse(FastTravel.sprintCutAllowed(20, true, false, false));
        assertFalse(FastTravel.sprintCutAllowed(20, false, true, false));
        assertFalse(FastTravel.sprintCutAllowed(20, false, false, true));
        assertFalse(FastTravel.choose(1, 0, 1, 1, 0, 0, 0, 1, 1, 1, 0, 0, false, true, true).aim);
    }

    @Test
    public void fallFloorIsTheStricterPreset() {
        assertTrue(Double.isNaN(FastTravel.fallFloor(false, 12, false, 10)));
        assertEquals(10, FastTravel.fallFloor(false, 12, true, 10), 0);
        assertEquals(12, FastTravel.fallFloor(true, 12, false, 10), 0);
        assertEquals(14, FastTravel.fallFloor(true, 12, true, 14), 0);
    }

    @Test
    public void shortcutNeedsAirAndFloorAcrossTheWholeBody() {
        assertTrue(FastTravel.shortcutClear(0, 0, 1, 1, 2, world(set(), set())));
        assertFalse(FastTravel.shortcutClear(0, 0, 1, 1, 2, world(set("0,1,1"), set())));
        assertFalse(FastTravel.shortcutClear(0, 0, 1, 1, 2, world(set(), set("1,3,1"))));
        assertFalse(FastTravel.shortcutClear(0, 0, 3, 0, 2, world(set(), set("2,2,0"))));
    }

    private static Set<String> set(String... keys) {
        Set<String> s = new HashSet<>();
        for (String k : keys) {
            s.add(k);
        }
        return s;
    }

    private static FastTravel.Cells world(Set<String> holes, Set<String> walls) {
        return new FastTravel.Cells() {
            @Override
            public boolean air(int x, int y, int z) {
                return !walls.contains(x + "," + y + "," + z);
            }

            @Override
            public boolean floor(int x, int y, int z) {
                return !holes.contains(x + "," + y + "," + z);
            }
        };
    }
}
