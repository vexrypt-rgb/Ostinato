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

import baritone.Baritone;
import baritone.api.Settings;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.movement.CalculationContext;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: fastMode is a preset. Off, the planner flags match the settings. On, it does not write them. */
public class FastModeTest {

    private static boolean fast;
    private static boolean experimental;
    private static boolean parkour;
    private static boolean place;
    private static boolean sprintJump;
    private static boolean clutch;
    private static boolean diagA;
    private static boolean diagD;
    private static boolean climb;
    private static boolean tas;
    private static boolean kinematic;
    private static boolean elytra;
    private static double minHealth;
    private static double penalty;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
        Settings s = Baritone.settings();
        fast = s.fastMode.value;
        experimental = s.experimentalMovement.value;
        parkour = s.allowParkour.value;
        place = s.allowParkourPlace.value;
        sprintJump = s.sprintJump.value;
        clutch = s.allowLadderClutch.value;
        diagA = s.allowDiagonalAscend.value;
        diagD = s.allowDiagonalDescend.value;
        climb = s.allowClimbJumps.value;
        tas = s.allowTasJumps.value;
        kinematic = s.kinematicTravel.value;
        elytra = s.elytraAutoJump.value;
        minHealth = s.fastModeMinHealth.value;
        penalty = s.blockPlacementPenalty.value;
    }

    @After
    public void restore() {
        Settings s = Baritone.settings();
        s.fastMode.value = fast;
        s.experimentalMovement.value = experimental;
        s.allowParkour.value = parkour;
        s.allowParkourPlace.value = place;
        s.sprintJump.value = sprintJump;
        s.allowLadderClutch.value = clutch;
        s.allowDiagonalAscend.value = diagA;
        s.allowDiagonalDescend.value = diagD;
        s.allowClimbJumps.value = climb;
        s.allowTasJumps.value = tas;
        s.kinematicTravel.value = kinematic;
        s.elytraAutoJump.value = elytra;
        s.fastModeMinHealth.value = minHealth;
        s.blockPlacementPenalty.value = penalty;
    }

    @Test
    public void offMatchesTheSettings() {
        Settings s = Baritone.settings();
        s.fastMode.value = false;
        s.experimentalMovement.value = false;
        s.allowParkour.value = false;
        s.allowParkourPlace.value = false;
        s.allowDiagonalAscend.value = false;
        s.allowClimbJumps.value = false;
        s.allowTasJumps.value = false;
        s.kinematicTravel.value = false;
        s.sprintJump.value = false;
        s.allowLadderClutch.value = false;
        s.elytraAutoJump.value = false;
        assertFalse(FastMode.on());
        assertFalse(ExperimentalMovement.allowParkour());
        assertFalse(FastMode.allowParkourPlace());
        assertFalse(ExperimentalMovement.allowDiagonalAscend());
        assertFalse(ExperimentalMovement.allowClimbJumps());
        assertFalse(ExperimentalMovement.allowTasJumps());
        assertFalse(ExperimentalMovement.kinematicTravel());
        assertFalse(FastMode.sprintJump());
        assertFalse(FastMode.allowLadderClutch());
        assertFalse(FastMode.elytraGroundTakeoff());
        assertEquals(1, ExperimentalMovement.jumpBias(), 0);
        assertEquals(penalty, ExperimentalMovement.blockPlacementPenalty(), 0);
        CalculationContext ctx = PathBench.context(new SyntheticWorld(8, 8));
        assertFalse(ctx.fastMode);
        assertFalse(ctx.allowParkour);
        assertFalse(ctx.allowParkourPlace);
        assertFalse(ctx.allowDiagonalAscend);
    }

    @Test
    public void onDoesNotRewriteTheSettings() {
        Settings s = Baritone.settings();
        s.fastMode.value = true;
        s.experimentalMovement.value = false;
        s.allowParkour.value = false;
        s.allowParkourPlace.value = false;
        s.allowDiagonalAscend.value = false;
        s.allowDiagonalDescend.value = false;
        s.allowClimbJumps.value = false;
        s.allowTasJumps.value = false;
        s.kinematicTravel.value = false;
        s.sprintJump.value = false;
        s.allowLadderClutch.value = false;
        s.elytraAutoJump.value = false;
        s.fastModeMinHealth.value = 14D;
        assertTrue(ExperimentalMovement.allowParkour());
        assertTrue(ExperimentalMovement.allowClimbJumps());
        assertTrue(ExperimentalMovement.allowDiagonalAscend());
        assertTrue(ExperimentalMovement.allowDiagonalDescend());
        assertFalse(ExperimentalMovement.allowTasJumps());
        assertFalse(ExperimentalMovement.kinematicTravel());
        assertTrue(FastMode.allowParkourPlace());
        assertTrue(FastMode.sprintJump());
        assertTrue(FastMode.allowLadderClutch());
        assertTrue(FastMode.elytraGroundTakeoff());
        assertEquals(s.experimentalJumpBias.value, ExperimentalMovement.jumpBias(), 0);
        assertEquals(Math.min(penalty, s.experimentalBlockPlacementPenalty.value), ExperimentalMovement.blockPlacementPenalty(), 0);
        assertFalse(s.allowParkour.value);
        assertFalse(s.allowParkourPlace.value);
        assertFalse(s.sprintJump.value);
        assertFalse(s.kinematicTravel.value);
        CalculationContext ctx = PathBench.context(new SyntheticWorld(8, 8));
        assertTrue(ctx.fastMode);
        assertTrue(ctx.allowParkour);
        assertTrue(ctx.allowParkourPlace);
        assertTrue(ctx.allowClimbJumps);
        assertFalse(ctx.allowTasJumps);
        assertFalse(ctx.allowParkourFourGap);
        assertEquals(14, ctx.fastModeMinHealth, 0);
    }
}
