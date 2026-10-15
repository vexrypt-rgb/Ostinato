package baritone.bastion;

import org.junit.Test;

import java.util.List;

import static baritone.bastion.BastionRoutes.*;
import static org.junit.Assert.*;

public class BastionRoutesTest {
    @Test
    public void treasureBeginnerVsIntermediate() {
        List<Step> b = treasure(false, false, false), i = treasure(true, false, false);
        assertEquals(Step.TAKE_THIRD_BASALT_BRIDGE, b.get(0));
        assertTrue(b.contains(Step.COVER_2_BLOCKS));
        assertFalse(i.contains(Step.COVER_2_BLOCKS));
        assertTrue(b.indexOf(Step.PIGLIN_PIT) < b.indexOf(Step.MINE_GOLD_SIDE_1));
        assertTrue(b.indexOf(Step.LOOT_DOUBLE) < b.indexOf(Step.BACKUP_GOLD_1));
        assertTrue(b.indexOf(Step.BACKUP_GOLD_2) < b.indexOf(Step.TRADE_AT_PIT));
        assertFalse(b.contains(Step.PEARL_HANG_SPAWNER));
        assertTrue(treasure(true, true, true).contains(Step.BOTTOM_ROUTE));
        assertEquals(Step.LEAVE, b.get(b.size() - 1));
    }

    @Test
    public void pitGoldOrderAndRhythm() {
        assertEquals(6, treasurePitGold(1, false).size());
        assertEquals(5, treasurePitGold(1, true).size());
        assertFalse(treasurePitGold(1, true).contains(Gold.BOTTOM_RIGHT));
        assertEquals(6, treasurePitGold(2, true).size());
        assertEquals('M', rhythm(0));
        assertEquals('T', rhythm(1));
        assertEquals('P', rhythm(6));
        assertEquals('T', rhythm(8));
        assertEquals('M', rhythm(9));
    }

    @Test
    public void otherLayouts() {
        assertEquals(Step.HOUSING_ENTRY_BASALT_CUE, housing(HousingVariant.BEGINNER).get(0));
        assertTrue(housing(HousingVariant.BEGINNER).contains(Step.UNSTICK_BY_MINING));
        assertTrue(housing(HousingVariant.INTERMEDIATE).contains(Step.GOLD_ELEVATOR));
        assertEquals(Step.AGGRO_ONE_GOLD_ARMOR, housing(HousingVariant.LAVA).get(0));
        assertEquals(HousingVariant.LAVA, housingVariant(100, 40, false));
        assertEquals(HousingVariant.BEGINNER, housingVariant(100, 5, false));
        assertEquals(HousingVariant.INTERMEDIATE, housingVariant(0, 0, true));
        assertTrue(needsDistractFirst(Step.MINE_WALL_GOLD, 3));
        assertEquals(Step.MINE_CHALICE, bridge(true, false).get(0));
        assertEquals(Step.TRADE_TRIPLE, bridge(false, true).get(1));
        assertEquals(Step.TRADE_SINGLE, bridge(false, false).get(1));
        assertTrue(stables(null, true, false).contains(Step.PROBE_GAPS));
        assertEquals(Step.GOOD_GAP_GOLD, stables(false, true, false).get(1));
        assertEquals(Step.TRIPLE_GOLD, stables(false, false, true).get(1));
        assertEquals(List.of(Step.TRADE, Step.LEAVE), forLayout("x", true, false));
        assertEquals(Step.TAKE_THIRD_BASALT_BRIDGE, forLayout("treasure", true, true).get(0));
    }

    @Test
    public void tradeWaitsForTheRoute() {
        List<Step> h = housing(HousingVariant.BEGINNER);
        assertEquals(h.indexOf(Step.HOUSING_PIT), firstTradeIndex(h));
        List<Step> t = treasure(false, false, false);
        assertEquals(t.indexOf(Step.PIGLIN_PIT), firstTradeIndex(t));
        assertEquals(1, firstTradeIndex(bridge(true, false)));
        assertEquals(0, firstTradeIndex(List.of(Step.TRADE)));
        assertEquals(1, firstTradeIndex(List.of(Step.LEAVE)));
    }

    @Test
    public void distractGate() {
        assertTrue(needsDistractFirst(Step.MINE_CHALICE, 10));
        assertFalse(needsDistractFirst(Step.MINE_CHALICE, 20));
        assertFalse(needsDistractFirst(Step.LOOT_DOUBLE, 5));
    }

    @Test
    public void throwAdapt() {
        ThrowAdapt a = new ThrowAdapt();
        a.landed(0, 0, 0, 0, 1, 1);
        assertEquals(4f, a.bias(), 0);
        for (int k = 0; k < 10; k++) a.landed(0, 0, 0, 0, 1, 1);
        assertEquals(ThrowAdapt.MAX, a.bias(), 0);
        a.landed(3, 3, 0, 0, 1, 1);
        assertEquals(22f, a.bias(), 0);
        a.landed(1, 1, 0, 0, 1, 1);
        assertEquals(22f, a.bias(), 0);
        assertEquals(-89f, a.pitch(-80f), 0);
        assertEquals(-30f, new ThrowAdapt().pitch(-30f), 0);
    }
}
