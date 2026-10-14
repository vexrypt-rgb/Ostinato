package baritone.bastion;

import baritone.bastion.BastionPlan.Kind;
import baritone.bastion.BastionPlan.Point;
import baritone.bastion.BastionRouteResolver.Target;
import baritone.bastion.BastionRoutes.Step;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class BastionRouteResolverTest {
    static Point g(int x, int y, int z) { return new Point(x, y, z, Kind.GOLD); }
    static Point c(int x, int y, int z) { return new Point(x, y, z, Kind.CHEST); }

    static Step stepOf(List<Target> t, Point p) {
        for (Target x : t) if (x.point().equals(p)) return x.step();
        throw new AssertionError("missing " + p);
    }

    @Test
    public void treasureStepsClaimTheRightBlocks() {
        Point high = g(0, 90, 10);
        Point s1a = g(5, 70, 0), s1b = g(6, 71, 1), s2a = g(-5, 70, 0), s2b = g(-6, 71, 1);
        Point d1 = c(2, 60, 3), d2 = c(3, 60, 3), single = c(-3, 60, -3), outer = c(40, 60, 0);
        List<Point> pts = List.of(high, s1a, s1b, s2a, s2b, d1, d2, single, outer);
        List<Target> t = BastionRouteResolver.resolve("treasure", BastionRoutes.treasure(false, false, false), 0, 60, 0, 20, 60, 0, pts);
        assertEquals(pts.size(), t.size());
        assertEquals(Step.MINE_HIGH_GOLD_CRAFT_INGOTS, t.get(0).step());
        assertEquals(high, t.get(0).point());
        assertEquals(Step.MINE_GOLD_SIDE_1, stepOf(t, s1a)); // we start on the +x side
        assertEquals(Step.MINE_GOLD_SIDE_2, stepOf(t, s2b));
        assertEquals(Step.LOOT_DOUBLE, stepOf(t, d1));
        assertEquals(Step.LOOT_DOUBLE, stepOf(t, d2));
        assertEquals(Step.LOOT_SINGLES, stepOf(t, single));
        assertNull(stepOf(t, outer)); // outside the core: appended last
        assertEquals(outer, t.get(t.size() - 1).point());
    }

    @Test
    public void bridgeChaliceFirst() {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < 5; i++) pts.add(g(30 + i, 70, 0));
        Point stray = g(-20, 60, 0), ch = c(5, 60, 5);
        pts.add(stray); pts.add(ch);
        List<Target> t = BastionRouteResolver.resolve("bridge", BastionRoutes.bridge(true, false), 0, 60, 0, 0, 60, 0, pts);
        for (int i = 0; i < 5; i++) assertEquals(Step.MINE_CHALICE, t.get(i).step());
        assertEquals(Step.LOOT_RAMPARTS, stepOf(t, ch));
        assertNull(stepOf(t, stray));
    }

    @Test
    public void stablesGapGoldThenBottomChest() {
        Point a = g(0, 50, 20), b = g(1, 50, 20), bottom = c(0, 45, 3), up1 = c(10, 80, 0);
        List<Target> t = BastionRouteResolver.resolve("stables", BastionRoutes.stables(true, false, false), 0, 60, 0, 0, 80, 0, List.of(a, b, bottom, up1));
        assertEquals(Step.GOOD_GAP_GOLD, t.get(0).step());
        assertEquals(Step.MIDDLE_BOTTOM_CHEST, stepOf(t, bottom));
        assertEquals(Step.UPPER_CHESTS, stepOf(t, up1));
    }

    @Test
    public void housingBeginner() {
        Point top1 = c(0, 90, 0), top2 = c(1, 90, 0), topSingle = c(8, 89, 8), bottom = c(5, 40, 5), outer = c(40, 50, 0);
        Point w1 = g(0, 50, 2), w2 = g(1, 50, 2), w3 = g(2, 50, 2);
        List<Target> t = BastionRouteResolver.resolve("housing", BastionRoutes.housing(BastionRoutes.HousingVariant.BEGINNER), 0, 60, 0, 0, 92, 0,
                List.of(top1, top2, topSingle, bottom, outer, w1, w2, w3));
        assertEquals(Step.LOOT_TOP_LARGE, t.get(0).step());
        assertEquals(Step.LOOT_TOP_SINGLE, stepOf(t, topSingle));
        assertEquals(Step.MINE_WALL_GOLD, stepOf(t, w2));
        assertEquals(Step.HOUSING_BOTTOM_CHEST, stepOf(t, bottom));
    }

    @Test
    public void housingLavaOutsideChestLast() {
        Point armor = c(0, 90, 0), corner = c(2, 55, 2), outer = c(40, 50, 0), gb = g(1, 50, 1);
        List<Target> t = BastionRouteResolver.resolve("housing", BastionRoutes.housing(BastionRoutes.HousingVariant.LAVA), 0, 60, 0, 0, 92, 0,
                List.of(armor, corner, outer, gb));
        assertEquals(Step.LOOT_ARMOR_CHEST, t.get(0).step());
        assertEquals(Step.GOLD_ELEVATOR, stepOf(t, gb));
        assertEquals(Step.OUTSIDE_CHESTS, stepOf(t, outer));
    }

    @Test
    public void tacticStepsClaimNothingAndGoldStepsAreGated() {
        List<Point> pts = List.of(g(0, 60, 0), c(1, 60, 1));
        assertTrue(BastionRouteResolver.select("treasure", Step.PILLAR_3_HUG, 0, 60, 0, new int[]{0, 60, 0}, pts).isEmpty());
        assertTrue(BastionRouteResolver.select("treasure", Step.PIGLIN_PIT, 0, 60, 0, new int[]{0, 60, 0}, pts).isEmpty());
        assertTrue(BastionRouteResolver.breaksGold(Step.MINE_CHALICE));
        assertTrue(BastionRouteResolver.breaksGold(Step.MINE_GOLD_SIDE_1));
        assertFalse(BastionRouteResolver.breaksGold(Step.LOOT_DOUBLE));
        assertTrue(BastionRouteResolver.resolve("treasure", BastionRoutes.treasure(true, false, false), 0, 60, 0, 0, 60, 0, List.of()).isEmpty());
    }
}
