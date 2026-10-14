package baritone.bastion;

import baritone.bastion.BastionRoutes.Step;
import baritone.bastion.BastionScript.Kind;
import baritone.bastion.BastionScript.Op;
import baritone.bastion.BastionScript.Result;
import baritone.bastion.BastionScript.Runner;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** Fixes from the route audit (2026-10-13). */
public class RouteAuditTest {
    @Test
    public void helmetIsCraftedThenWorn() { // (1)
        List<Op> o = BastionScript.ops(Step.WEAR_GOLD_HELMET);
        assertEquals(Kind.CRAFT_ARMOR, o.get(0).kind());
        assertEquals(Kind.WEAR_GOLD, o.get(1).kind());
        assertEquals(Kind.THROW, o.get(2).kind()); // drop gold from the pocket
        assertEquals(Kind.CRAFT_ARMOR, BastionScript.ops(Step.AGGRO_ONE_GOLD_ARMOR).get(0).kind());
        assertArrayEquals(new int[]{1, 2, 3, 4, 6}, BastionPriors.HELMET_GRID);
    }

    @Test
    public void tradeStepsTradeAtTheirSpot() { // (2)
        for (Step s : new Step[]{Step.TRADE_AT_PIT, Step.TRADE_CORNER, Step.TRADE_TRIPLE, Step.TRADE_SINGLE, Step.TRADE}) {
            List<Op> o = BastionScript.ops(s);
            assertEquals(s.toString(), Kind.MOVE_TARGET, o.get(0).kind());
            assertEquals(new Op(Kind.TRADE_AT, 0, 0, 0, BastionScript.TRADE_SECONDS), o.get(1));
        }
        assertFalse(BastionPriors.tradeAtDone(100, 90, 5, 0, 0));
        assertTrue(BastionPriors.tradeAtDone(1801, 90, 5, 0, 0));
        assertTrue(BastionPriors.tradeAtDone(100, 90, 0, 0, 0));
        assertFalse(BastionPriors.tradeAtDone(100, 90, 0, 0, 2)); // still admiring: wait for the barter
    }

    @Test
    public void bridgeTripleRampartsAreDetected() { // (2)
        List<int[]> chests = J8.listOf(new int[]{10, 70, 0}, new int[]{11, 70, 0}, new int[]{12, 70, 1}, new int[]{-20, 60, 5});
        List<int[]> t = BastionPriors.tripleCentres(chests);
        assertEquals(1, t.size());
        assertEquals(70, t.get(0)[1]);
        BastionRoutes.Features f = new BastionRoutes.Features();
        f.leftTriple = true;
        assertEquals(Step.TRADE_TRIPLE, BastionRoutes.forLayout("bridge", false, false, f).get(1));
        assertEquals(Step.TRADE_SINGLE, BastionRoutes.forLayout("bridge", false, false).get(1));
    }

    @Test
    public void stablesRouteMinesGoldOnRoute() { // (3)
        assertEquals(Kind.MOVE_TARGET, BastionScript.ops(Step.TOP_OF_MIDDLE_STAIRS).get(0).kind());
        assertEquals(2, BastionScript.ops(Step.PROBE_GAPS).stream().filter(o -> o.kind() == Kind.MINE).count());
        BastionRoutes.Features f = new BastionRoutes.Features();
        f.leftGood = true; f.rightGood = false; f.stablesTriple = true;
        List<Step> r = BastionRoutes.forLayout("stables", false, false, f);
        assertTrue(r.toString(), r.contains(Step.GOOD_GAP_GOLD));
        assertTrue(r.toString(), r.contains(Step.TRIPLE_GOLD));
        assertTrue(r.indexOf(Step.GOOD_GAP_GOLD) < r.indexOf(Step.TRADE));
    }

    @Test
    public void housingWalksBeforeBlockIn() { // (4)
        List<Step> h = BastionRoutes.housing(BastionRoutes.HousingVariant.BEGINNER);
        assertTrue(h.indexOf(Step.WALK_RIGHT_FORK) < h.indexOf(Step.RIGHT_FORK_BLOCK_IN));
        assertEquals(Kind.MOVE_TARGET, BastionScript.ops(Step.HOUSING_ENTRY_BASALT_CUE).get(0).kind());
        List<Op> one = BastionScript.ops(Step.ONE_BY_ONE);
        assertEquals(Kind.MOVE_TARGET, one.get(0).kind()); // to the corner first
        assertTrue(one.contains(new Op(Kind.MINE, 0, 0, -2, 0))); // down 2
        assertEquals(new Op(Kind.PLACE, 0, 0, 0, 0), one.get(one.size() - 1)); // cap overhead
        assertTrue(BastionScript.reanchors(Kind.MOVE_TARGET));
    }

    @Test
    public void treasureEntryAndGold() { // (7)
        Map<Integer, Integer> h = new HashMap<>();
        h.put(-8, 5); h.put(-7, 5); h.put(-3, 4); h.put(0, 1); h.put(2, 6); h.put(6, 4);
        assertEquals(J8.listOf(-8, -3, 2, 6), BastionScript.columns(h)); // 2-wide column is one, a 1-tall floor is none
        List<Op> m = BastionScript.ops(Step.MINE_HIGH_GOLD_CRAFT_INGOTS);
        assertEquals(Kind.MINE_GOLD_NEAR, m.get(0).kind());
        assertEquals(Kind.CRAFT_INGOTS, m.get(1).kind());
        assertEquals(Kind.CRAFT_INGOTS, BastionScript.ops(Step.DISTRACT_CROSSBOWS).get(0).kind());
        assertEquals(new Op(Kind.MOVE, 3, 0, 0, 0), BastionScript.ops(Step.BRIDGE_RAMPARTS).get(4)); // crosses
        assertEquals(Kind.MOVE_TARGET, BastionScript.ops(Step.TRADE_AT_PIT).get(0).kind());
    }

    @Test
    public void pitGoldInVideoOrder() { // (7)
        // lateral, y: mid column 0 at 72/71/70, top-left -1/72, bottom-left -1/70, bottom-right 1/70
        List<int[]> g = J8.listOf(new int[]{1, 70}, new int[]{0, 70}, new int[]{-1, 72}, new int[]{0, 72}, new int[]{-1, 70}, new int[]{0, 71});
        List<Integer> o = BastionRoutes.orderPitGold(g, 1, false);
        assertEquals(J8.listOf(3, 5, 1, 2, 4, 0), o);
        List<Integer> oi = BastionRoutes.orderPitGold(g, 1, true);
        assertFalse(oi.contains(0)); // intermediate side 1 skips bottom-right
        assertTrue(BastionRoutes.orderPitGold(g, 2, true).contains(0));
        assertEquals('M', BastionRoutes.rhythm(0));
        assertEquals('T', BastionRoutes.rhythm(1));
        assertEquals('P', BastionRoutes.rhythm(6));
    }

    @Test
    public void elevatorGoldBeforeElevator() { // (5)
        List<Step> l = BastionRoutes.housing(BastionRoutes.HousingVariant.LAVA);
        assertEquals(l.indexOf(Step.ELEVATOR_GOLD) + 1, l.indexOf(Step.GOLD_ELEVATOR));
        assertTrue(BastionScript.ops(Step.ELEVATOR_GOLD).isEmpty()); // a block step: the gold
    }

    @Test
    public void frameIsFixedAndNoEffectIsSeen() { // (8) + loud no-ops
        assertArrayEquals(new int[]{1, 0}, BastionScript.facing(10, 3));
        assertArrayEquals(new int[]{0, -1}, BastionScript.facing(2, -9));
        Runner r = new Runner(BastionScript.ops(Step.PROBE_GAPS));
        r.report(Result.DONE); r.report(Result.DONE);
        assertTrue(r.noEffect());
        Runner w = new Runner(BastionScript.ops(Step.PROBE_GAPS));
        w.report(Result.WORKING); w.report(Result.DONE); w.report(Result.DONE);
        assertFalse(w.noEffect());
    }
}
