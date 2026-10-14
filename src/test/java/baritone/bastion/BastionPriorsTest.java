package baritone.bastion;

import org.junit.Test;

import static baritone.bastion.BastionPriors.*;
import static org.junit.Assert.*;

public class BastionPriorsTest {
    @Test
    public void tiers() {
        assertEquals(Tier.T1, tier(10, true, false, false, false, false, false));
        assertEquals(Tier.NONE, tier(10, true, true, false, false, false, false));
        assertEquals(Tier.NONE, tier(20, true, false, false, false, false, false));
        assertEquals(Tier.T2, tier(12, false, true, true, false, false, false));
        assertEquals(Tier.NONE, tier(12, false, true, false, true, false, false));
        assertEquals(Tier.T2, tier(12, true, true, false, true, false, false));
        assertEquals(Tier.T2, tier(8, false, true, false, false, false, true));
        assertEquals(Tier.T3, tier(30, false, true, false, false, true, false));
    }

    @Test
    public void goldBreakingAndOpening() {
        assertFalse(mayBreakGold(15.9));
        assertTrue(mayBreakGold(16.5));
        assertFalse(mayOpen(10));
        assertTrue(mayOpen(Double.MAX_VALUE));
    }

    @Test
    public void losAndFloorGold() {
        assertTrue(slipWindow(1, 10));
        assertFalse(slipWindow(15, 10));
        assertTrue(floorGoldTraded(-1));
        assertFalse(floorGoldTraded(199));
        assertTrue(floorGoldTraded(200));
    }

    @Test
    public void routesAndChests() {
        assertEquals(Spot.TOP, housingRoute(true).get(0));
        assertEquals(Spot.RIGHT_TOP_CHESTS, housingRoute(false).get(0));
        assertEquals(Spot.MID_GOLD, housingRoute(true).get(4));
        assertEquals(5, guaranteedChests("housing"));
        assertEquals(4, guaranteedChests("treasure"));
        assertEquals(0, guaranteedChests("stables"));
        assertEquals(4 / 3.0, housingLeftExpected(), 1e-9);
        assertEquals(5, housingBackupGoldBlocks());
        assertEquals(2, treasureBridgeGoldBlocks());
        assertArrayEquals(new int[]{-1, 1}, cubeChestOffset(true, false));
        assertArrayEquals(new int[]{1, -1}, cubeChestOffset(false, true));
        assertNull(cubeChestOffset(false, false));
    }
}
