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
        List<Point> pts = J8.listOf(high, s1a, s1b, s2a, s2b, d1, d2, single, outer);
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
        List<Target> t = BastionRouteResolver.resolve("stables", BastionRoutes.stables(true, false, false), 0, 60, 0, 0, 80, 0, J8.listOf(a, b, bottom, up1));
        assertEquals(Step.GOOD_GAP_GOLD, t.get(0).step());
        assertEquals(Step.MIDDLE_BOTTOM_CHEST, stepOf(t, bottom));
        assertEquals(Step.UPPER_CHESTS, stepOf(t, up1));
    }

    @Test
    public void tacticStepsClaimNothingAndGoldStepsAreGated() {
        List<Point> pts = J8.listOf(g(0, 60, 0), c(1, 60, 1));
        assertTrue(BastionRouteResolver.select("treasure", Step.PILLAR_3_HUG, 0, 60, 0, new int[]{0, 60, 0}, pts).isEmpty());
        assertTrue(BastionRouteResolver.select("treasure", Step.PIGLIN_PIT, 0, 60, 0, new int[]{0, 60, 0}, pts).isEmpty());
        assertTrue(BastionRouteResolver.breaksGold(Step.MINE_CHALICE));
        assertTrue(BastionRouteResolver.breaksGold(Step.MINE_GOLD_SIDE_1));
        assertFalse(BastionRouteResolver.breaksGold(Step.LOOT_DOUBLE));
        assertTrue(BastionRouteResolver.resolve("treasure", BastionRoutes.treasure(true, false, false), 0, 60, 0, 0, 60, 0, java.util.Collections.emptyList()).isEmpty());
    }

    /** Housing pieces placed from the 26.3 bastion/units templates (positions inside each template plus an offset). */
    static List<Point> housingFromTemplates() {
        List<Point> p = new ArrayList<>();
        int[] ro = {-30, 40, -8};   // ramparts_0
        for (int[] c : new int[][]{{7, 17, 7}, {7, 17, 8}, {7, 17, 12}}) p.add(c(ro[0] + c[0], ro[1] + c[1], ro[2] + c[2]));
        int[] wo = {10, 40, -8};    // wall_base
        for (int[] c : new int[][]{{2, 4, 5}, {3, 4, 5}}) p.add(c(wo[0] + c[0], wo[1] + c[1], wo[2] + c[2]));
        for (int[] g : new int[][]{{5, 3, 10}, {5, 3, 11}, {6, 3, 11}, {6, 3, 12}, {7, 3, 10}, {7, 4, 9}, {7, 4, 11}, {7, 4, 13}, {8, 4, 12}, {9, 17, 10}, {9, 18, 12}, {10, 17, 12}})
            p.add(g(wo[0] + g[0], wo[1] + g[1], wo[2] + g[2]));
        int[] co = {-10, 40, 20};   // connected_wall
        for (int[] g : new int[][]{{3, 4, 9}, {6, 3, 11}, {6, 4, 11}, {7, 3, 11}, {7, 16, 3}}) p.add(g(co[0] + g[0], co[1] + g[1], co[2] + g[2]));
        p.add(c(-5 + 8, 41, -5 + 3)); // center_0 single
        return p;
    }

    @Test
    public void housingPicksMatchTemplates() {
        List<Point> pts = housingFromTemplates();
        List<Target> t = BastionRouteResolver.resolve("housing", BastionRoutes.housing(BastionRoutes.HousingVariant.BEGINNER), 0, 50, 0, -23, 60, 0, pts);
        assertEquals(Step.LOOT_TOP_LARGE, stepOf(t, c(-23, 57, -1)));
        assertEquals(Step.LOOT_TOP_LARGE, stepOf(t, c(-23, 57, 0)));
        assertEquals(Step.LOOT_TOP_SINGLE, stepOf(t, c(-23, 57, 4)));
        assertEquals(Step.HOUSING_BOTTOM_CHEST, stepOf(t, c(12, 44, -3)));
        assertEquals(Step.HOUSING_BOTTOM_CHEST, stepOf(t, c(13, 44, -3)));
        // the 9 low wall_base gold = strip-tunnel wall gold; connected_wall low gold = T-tunnel; high gold = backup
        long wall = t.stream().filter(x -> x.step() == Step.MINE_WALL_GOLD).count();
        assertEquals(9, wall);
        assertEquals(Step.T_TUNNEL_GOLD, stepOf(t, g(-4, 43, 31)));
        assertEquals(Step.HOUSING_BACKUP_GOLD, stepOf(t, g(19, 57, 2)));
        assertEquals(Step.HOUSING_BACKUP_GOLD, stepOf(t, g(-3, 56, 23)));
        assertEquals(pts.size(), t.size());
    }

    @Test
    public void housingIntermediateTopTripleTogether() {
        List<Target> t = BastionRouteResolver.resolve("housing", BastionRoutes.housing(BastionRoutes.HousingVariant.INTERMEDIATE), 0, 50, 0, -23, 60, 0, housingFromTemplates());
        for (int i = 0; i < 3; i++) assertEquals(Step.LOOT_TOP_TRIPLE, t.get(i).step());
        assertEquals(9, t.stream().filter(x -> x.step() == Step.GOLD_ELEVATOR).count());
    }
}
