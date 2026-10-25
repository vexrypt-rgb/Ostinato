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

import baritone.Baritone;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: grabbing a ladder that is already in the fall column. Placing one was already covered by LadderClutchTest.
 * Feet start at y 120. Cell 106 is 14 down and a tick starts inside it. Cell 108 is 12 down and the fall skips it.
 */
public class LadderGrabTest {

    private static final int FEET = 120;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void theCellsThisFallActuallyEnters() {
        assertTrue(LadderClutch.grabs(FEET, 106));
        assertFalse(LadderClutch.grabs(FEET, 108));
        assertTrue(FEET - 106 > 11);
        assertTrue(FEET - 108 > 11);
        assertTrue(FEET - 112 <= 11);
    }

    @Test
    public void offIgnoresALadderPastElevenBlocks() {
        MutableMoveResult off = price(false, 106, 104);
        assertEquals(COST_INF, off.cost, 0);
        assertEquals(0, off.damage, 0);
    }

    @Test
    public void onCatchesThatSameLadderAndLandsSafe() {
        MutableMoveResult on = price(true, 106, 104);
        assertTrue("cost " + on.cost, on.cost < COST_INF);
        assertEquals(105, on.y);
        assertEquals(0, on.damage, 0);
        assertFalse(on.clutch);
    }

    @Test
    public void aSkippedCellStillDoesNotSaveYou() {
        MutableMoveResult skipped = price(true, 108, 104);
        assertEquals(COST_INF, skipped.cost, 0);
    }

    @Test
    public void aShortLadderCostsTheSameEitherWay() {
        MutableMoveResult off = price(false, 112, 110);
        MutableMoveResult on = price(true, 112, 110);
        assertTrue(off.cost < COST_INF);
        assertEquals(off.cost, on.cost, 0);
        assertEquals(off.y, on.y);
        assertEquals(off.damage, on.damage, 0);
        assertEquals(off.clutch, on.clutch);
    }

    @Test
    public void aMissThatKillsIsNotOffered() {
        CalculationContext full = context(false, 12);
        assertTrue(MovementDescend.clutchMissSurvivable(full, 16));
        assertFalse(MovementDescend.clutchMissSurvivable(full, 23));
        CalculationContext careful = context(true, 12);
        assertTrue(MovementDescend.clutchMissSurvivable(careful, 10));
        assertFalse(MovementDescend.clutchMissSurvivable(careful, 12));
    }

    @Test
    public void fastModeMissUsesItsOwnFloorAndTheHigherOneWins() {
        boolean prevFast = Baritone.settings().fastMode.value;
        double prevFastMin = Baritone.settings().fastModeMinHealth.value;
        boolean prevExp = Baritone.settings().experimentalMovement.value;
        double prevExpMin = Baritone.settings().experimentalMinHealth.value;
        Baritone.settings().experimentalMovement.value = false;
        Baritone.settings().fastMode.value = true;
        Baritone.settings().fastModeMinHealth.value = 14D;
        try {
            CalculationContext fast = PathBench.context(new SyntheticWorld(8, 8));
            assertTrue(fast.fastMode);
            assertFalse(fast.experimental);
            // 8 blocks costs 5 half-hearts (15 left). 10 blocks costs 7 (13 left). Floor is 14.
            assertTrue(MovementDescend.clutchMissSurvivable(fast, 8));
            assertFalse(MovementDescend.clutchMissSurvivable(fast, 10));
            Baritone.settings().experimentalMovement.value = true;
            Baritone.settings().experimentalMinHealth.value = 16D;
            Baritone.settings().fastModeMinHealth.value = 10D;
            CalculationContext both = PathBench.context(new SyntheticWorld(8, 8));
            // Fast alone would allow 8 blocks (floor 10). The experimental floor of 16 wins.
            assertFalse(MovementDescend.clutchMissSurvivable(both, 8));
        } finally {
            Baritone.settings().fastMode.value = prevFast;
            Baritone.settings().fastModeMinHealth.value = prevFastMin;
            Baritone.settings().experimentalMovement.value = prevExp;
            Baritone.settings().experimentalMinHealth.value = prevExpMin;
        }
    }

    @Test
    public void slideOnlyAfterTheSettingCatchesTheColumn() {
        assertFalse(MovementDescend.slideOnGrabbedLadder(false, true, 11, 106, 10, 11, 10, FEET, 105));
        assertFalse(MovementDescend.slideOnGrabbedLadder(true, false, 11, 106, 10, 11, 10, FEET, 105));
        assertFalse(MovementDescend.slideOnGrabbedLadder(true, true, 12, 106, 10, 11, 10, FEET, 105));
        assertFalse(MovementDescend.slideOnGrabbedLadder(true, true, 11, 105, 10, 11, 10, FEET, 105));
        assertFalse(MovementDescend.slideOnGrabbedLadder(true, true, 11, FEET, 10, 11, 10, FEET, 105));
        assertTrue(MovementDescend.slideOnGrabbedLadder(true, true, 11, 106, 10, 11, 10, FEET, 105));
    }

    private static MutableMoveResult price(boolean clutch, int ladderY, int floorY) {
        boolean prev = Baritone.settings().allowLadderClutch.value;
        Baritone.settings().allowLadderClutch.value = clutch;
        try {
            SyntheticWorld w = shaft(ladderY, floorY);
            MutableMoveResult res = new MutableMoveResult();
            MovementDescend.cost(PathBench.context(w), 10, FEET, 10, 11, 10, res);
            return res;
        } finally {
            Baritone.settings().allowLadderClutch.value = prev;
        }
    }

    private static SyntheticWorld shaft(int ladderY, int floorY) {
        SyntheticWorld w = new SyntheticWorld(32, 32);
        w.set(10, FEET - 1, 10, Blocks.STONE.defaultBlockState());
        w.set(11, floorY, 10, Blocks.STONE.defaultBlockState());
        w.set(11, ladderY, 10, Blocks.LADDER.defaultBlockState());
        return w;
    }

    private static CalculationContext context(boolean experimental, double minHealth) {
        boolean prevExp = Baritone.settings().experimentalMovement.value;
        double prevMin = Baritone.settings().experimentalMinHealth.value;
        Baritone.settings().experimentalMovement.value = experimental;
        Baritone.settings().experimentalMinHealth.value = minHealth;
        try {
            return PathBench.context(new SyntheticWorld(8, 8));
        } finally {
            Baritone.settings().experimentalMovement.value = prevExp;
            Baritone.settings().experimentalMinHealth.value = prevMin;
        }
    }
}
