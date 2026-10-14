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

package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;
import org.junit.Test;

import static baritone.pathing.movement.DigTime.*;
import static org.junit.Assert.*;

/** Ostinato: break times against vanilla 26.3 (MultiPlayerGameMode / getDestroyProgress) and the wiki. */
public class DigTimeTest {

    private static final int GAP = 6; // blockBreakSpeed default = vanilla destroyDelay 5 + the start tick

    private static double breakTicks(double speed, int eff, double hardness, boolean correct) {
        return ticks(progress(speed, eff, hardness, correct), GAP) - GAP;
    }

    @Test
    public void wikiBreakTimes() {
        assertEquals(23, breakTicks(2, 0, 1.5, true), 0);    // stone, wooden pick: 1.15 s
        assertEquals(6, breakTicks(8, 0, 1.5, true), 0);     // stone, diamond: 0.3 s
        assertEquals(188, breakTicks(8, 0, 50, true), 0);    // obsidian, diamond: 9.4 s
        assertEquals(834, breakTicks(6, 0, 50, false), 0);   // obsidian, iron (no drop, /100): 41.7 s
        assertEquals(15, breakTicks(1, 0, 0.5, true), 0);    // dirt by hand: 0.75 s
        assertEquals(60, breakTicks(1, 0, 2.0, true), 0);    // log by hand (no tool needed): 3 s
        assertEquals(150, breakTicks(1, 0, 1.5, false), 0);  // stone by hand: 7.5 s
        assertEquals(3, breakTicks(4, 0, 0.4, true), 0);     // netherrack, stone pick: 0.15 s
        assertEquals(17, breakTicks(8, 0, 4.5, true), 0);    // deepslate diamond ore, diamond: 0.85 s
    }

    @Test
    public void instantBreaksHaveNoGap() {
        assertEquals(1, ticks(progress(12, 0, 0.4, true), GAP), 0);   // netherrack, gold pick
        assertEquals(2 + GAP, ticks(progress(8, 5, 1.5, true), GAP), 0);
        assertEquals(1, ticks(progress(9, 5, 1.0, true), GAP), 0);    // netherite Eff V on 1.0 hardness: 35 >= 30
        assertEquals(1, ticks(Double.POSITIVE_INFINITY, GAP), 0);     // hardness 0
        assertTrue(ticks(0, GAP) >= ActionCosts.COST_INF);            // bedrock
    }

    @Test
    public void efficiencyIsLevelSquaredPlusOne() {
        assertEquals((8 + 26) / 1.5 / 30, progress(8, 5, 1.5, true), 1e-12);
        assertEquals(1 / 1.5 / 30, progress(1, 5, 1.5, true), 1e-12); // only applies when the tool speed is > 1
    }

    @Test
    public void effects() {
        assertEquals(1.4, haste(1), 1e-12);      // Haste II
        assertEquals(1, haste(-1), 0);
        assertEquals(0.3, fatigue(0), 1e-12);
        assertEquals(0.027, fatigue(2), 1e-12);  // Baritone had 0.0027
        assertEquals(0.0081, fatigue(3), 1e-12); // and 0.00081
        assertEquals(0.2, situation(true, 0.2, true), 1e-12);
        assertEquals(0.04, situation(true, 0.2, false), 1e-12);
        assertEquals(1, situation(true, 1.0, true), 0); // Aqua Affinity
    }

    @Test
    public void gapClampedToOne() {
        assertEquals(23 + 1, ticks(progress(2, 0, 1.5, true), 0), 0);
    }
}
