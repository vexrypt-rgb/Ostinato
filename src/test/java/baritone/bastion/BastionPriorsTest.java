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
        assertEquals(1, guaranteedChests("stables"));
        assertEquals(2, guaranteedChests("bridge"));
        assertEquals(0, guaranteedChests("unknown"));
        assertEquals(4 / 3.0, housingLeftExpected(), 1e-9);
        assertEquals(5, housingBackupGoldBlocks());
        assertEquals(2, treasureBridgeGoldBlocks());
        assertArrayEquals(new int[]{-1, 1}, cubeChestOffset(true, false));
        assertArrayEquals(new int[]{1, -1}, cubeChestOffset(false, true));
        assertNull(cubeChestOffset(false, false));
    }
    @Test
    public void bridge() {
        assertEquals(4.0, bridgeExpectedChests(), 1e-9);
        assertEquals(BridgeStep.MINE_CHALICE, bridgeRoute(true, true).get(0));
        assertEquals(BridgeStep.TRADE_LEFT_TRIPLE, bridgeRoute(true, true).get(1));
        assertEquals(BridgeStep.TRADE_RIGHT_TRIPLE, bridgeRoute(false, true).get(1));
        assertEquals(BridgeStep.TRADE_SINGLE, bridgeRoute(false, false).get(1));
        assertEquals(3, chests(Rampart.TRIPLE));
    }

    @Test
    public void stables() {
        assertEquals(Rampart.TRIPLE, stablesUpper(true, true));
        assertEquals(Rampart.DOUBLE, stablesUpper(true, false));
        assertEquals(Rampart.SINGLE, stablesUpper(false, false));
        assertEquals(4, stablesTripleGoldBlocks(Rampart.TRIPLE));
        assertEquals(0, stablesTripleGoldBlocks(Rampart.DOUBLE));
        assertEquals(Boolean.FALSE, stablesGapGood(false, 1, true));
        assertEquals(Boolean.TRUE, stablesGapGood(null, 1, false));
        assertEquals(Boolean.FALSE, stablesGapGood(null, 2, true));
        assertEquals(Boolean.TRUE, stablesGapGood(null, 0, true));
        assertNull(stablesGapGood(null, 0, null));
        assertEquals(StablesStep.GOOD_GAP_GOLD, stablesRoute(true, true).get(1));
        assertEquals(StablesStep.TRIPLE_GOLD, stablesRoute(false, true).get(1));
        assertEquals(StablesStep.MIDDLE_BOTTOM_CHEST, stablesRoute(false, false).get(1));
    }
}
