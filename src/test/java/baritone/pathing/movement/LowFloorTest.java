package baritone.pathing.movement;

import org.junit.Test;

import static org.junit.Assert.*;

/** Standability from a block's collision top: candles, pickles, pots, heads, rods. */
public class LowFloorTest {

    @Test
    public void smallTopsHoldAPlayer() {
        assertTrue(MovementHelper.holdsPlayer(6 / 16.0, 4 / 16.0));   // one candle: 6 high, 2..4 wide
        assertTrue(MovementHelper.holdsPlayer(6 / 16.0, 6 / 16.0));   // flower pot
        assertTrue(MovementHelper.holdsPlayer(8 / 16.0, 8 / 16.0));   // skull on the floor
        assertTrue(MovementHelper.holdsPlayer(1.0, 4 / 16.0));        // upright end rod / chain
        assertTrue(MovementHelper.holdsPlayer(1.0, 12 / 16.0));       // anvil (a falling block, not a full cube)
    }

    @Test
    public void thinOrTallTopsDoNot() {
        assertFalse(MovementHelper.holdsPlayer(1 / 16.0, 1.0));       // carpet, plates: the player stands in that cell
        assertFalse(MovementHelper.holdsPlayer(-1, 0));               // no collision
        assertFalse(MovementHelper.holdsPlayer(1.5, 4 / 16.0));       // fence post: feet a cell up
        assertFalse(MovementHelper.holdsPlayer(0.5, 1 / 16.0));       // too thin to land on
    }

    @Test
    public void lowTopsAreLowFloors() {
        assertTrue(MovementHelper.isLowTop(6 / 16.0));
        assertTrue(MovementHelper.isLowTop(9 / 16.0));                // lantern height, the existing low floor
        assertFalse(MovementHelper.isLowTop(1.0));
        assertFalse(MovementHelper.isLowTop(1 / 16.0));
    }

    @Test
    public void tallTopsNeedAThirdCell() {
        assertEquals(3, MovementHelper.cellsAboveNeeded(1.5));   // fence, wall, gate
        assertEquals(2, MovementHelper.cellsAboveNeeded(1.0));
        assertEquals(2, MovementHelper.cellsAboveNeeded(6 / 16.0));
    }
}
