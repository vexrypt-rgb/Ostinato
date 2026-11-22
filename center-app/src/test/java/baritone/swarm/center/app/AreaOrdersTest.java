package baritone.swarm.center.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: spreading bots over an area, and splitting it into mine strips. */
public class AreaOrdersTest {

    @Test public void yRangeIsTheMedianPlusOrMinusEight() {
        assertEquals(56, AreaOrders.yRange(List.of())[0]);
        assertEquals(72, AreaOrders.yRange(null)[1]);
        assertEquals(32, AreaOrders.yRange(List.of(40))[0]);
        assertEquals(48, AreaOrders.yRange(List.of(40))[1]);
        int[] two = AreaOrders.yRange(List.of(64, 40));
        assertEquals(44, two[0]);
        assertEquals(60, two[1]);
        int[] three = AreaOrders.yRange(List.of(64, 10, 40));
        assertEquals(32, three[0]);
        assertEquals(48, three[1]);
    }

    @Test public void spreadPutsOnePointPerBotInsideTheArea() {
        AreaOrders.Box area = new AreaOrders.Box(0, 0, 0, 9, 0, 9);
        List<AreaOrders.Assign> one = AreaOrders.spread(area, List.of(new AreaOrders.BotAt("Azalea", 0, 64, 0)));
        assertEquals(1, one.size());
        assertEquals("5 5", one.get(0).target());
        assertTrue(inside(area, one.get(0)));

        List<AreaOrders.Assign> four = AreaOrders.spread(area, List.of(
                new AreaOrders.BotAt("Azalea", 0, 64, 0),
                new AreaOrders.BotAt("Copper", 9, 64, 0),
                new AreaOrders.BotAt("Spelunker", 0, 64, 9),
                new AreaOrders.BotAt("Oak", 9, 64, 9)));
        assertEquals(4, four.size());
        assertEquals("Azalea", four.get(0).bot());
        assertEquals(2, four.get(0).x());
        assertEquals(2, four.get(0).z());
        assertEquals(7, four.get(1).x());
        assertEquals(2, four.get(1).z());
        assertEquals(2, four.get(2).x());
        assertEquals(7, four.get(2).z());
        assertEquals(7, four.get(3).x());
        assertEquals(7, four.get(3).z());
        assertTrue(AreaOrders.spread(area, List.of()).isEmpty());
        assertTrue(AreaOrders.spread(null, List.of(new AreaOrders.BotAt("Azalea", 0, 64, 0))).isEmpty());
    }

    @Test public void stripsCoverOddSizesForOneToElevenBots() {
        for (int n = 1; n <= 11; n++) {
            AreaOrders.Box box = new AreaOrders.Box(0, 60, 0, 54, 70, 6);
            AreaOrders.Mine mine = AreaOrders.strips(box, names(n));
            assertTrue("n=" + n + " " + mine.refusal(), mine.ok());
            assertEquals(n, mine.strips().size());
            assertCovered(box, mine, true);
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (AreaOrders.Strip s : mine.strips()) {
                min = Math.min(min, s.box().spanX());
                max = Math.max(max, s.box().spanX());
            }
            assertTrue(max - min <= 1);
        }
        AreaOrders.Box odd = new AreaOrders.Box(0, 0, 0, 6, 4, 2);
        AreaOrders.Mine three = AreaOrders.strips(odd, names(3));
        assertEquals(3, three.strips().get(0).box().spanX());
        assertEquals(2, three.strips().get(1).box().spanX());
        assertEquals(2, three.strips().get(2).box().spanX());
        assertEquals(0, three.strips().get(0).box().x1());
        assertEquals(2, three.strips().get(0).box().x2());
        assertEquals(6, three.strips().get(2).box().x2());

        AreaOrders.Box tall = new AreaOrders.Box(0, 0, 0, 2, 4, 8);
        AreaOrders.Mine cutZ = AreaOrders.strips(tall, names(2));
        assertEquals(0, cutZ.strips().get(0).box().x1());
        assertEquals(2, cutZ.strips().get(0).box().x2());
        assertEquals(0, cutZ.strips().get(0).box().z1());
        assertEquals(4, cutZ.strips().get(0).box().z2());
        assertEquals(5, cutZ.strips().get(1).box().z1());
        assertEquals(8, cutZ.strips().get(1).box().z2());
    }

    @Test public void anEdgeOfSixtyFourOrMoreIsRefusedWhole() {
        assertTrue(AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 63, 10, 10), names(1)).ok());
        assertEquals(AreaOrders.EDGE_REASON, AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 64, 10, 10), names(1)).refusal());
        assertTrue(AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 127, 10, 10), names(2)).ok());
        assertEquals(63, AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 127, 10, 10), names(2)).strips().get(0).box().x2()
                - AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 127, 10, 10), names(2)).strips().get(0).box().x1());
        assertEquals(AreaOrders.EDGE_REASON, AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 128, 10, 10), names(2)).refusal());
        assertEquals(AreaOrders.EDGE_REASON, AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 10, 64, 10), names(4)).refusal());
        assertEquals(AreaOrders.SMALL_REASON, AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 2, 4, 2), names(4)).refusal());
        assertEquals(AreaOrders.SELECT_REASON, AreaOrders.strips(new AreaOrders.Box(0, 0, 0, 4, 4, 4), List.of()).refusal());
        assertEquals(AreaOrders.BLOCK_REASON, AreaOrders.mine(new AreaOrders.Box(0, 0, 0, 4, 4, 4), names(1), " ").refusal());
    }

    @Test public void mineLinesNameEachStrip() {
        AreaOrders.Box box = new AreaOrders.Box(0, 56, 0, 6, 72, 2);
        AreaOrders.Mine mine = AreaOrders.mine(box, List.of("Azalea", "Spelunker"), " iron_ore ");
        assertTrue(mine.ok());
        List<String> lines = mine.lines('o', " iron_ore ");
        assertEquals("swarm lease Azalea r:o:0,56,0:3,72,2 mine iron_ore", lines.get(0));
        assertEquals("swarm lease Spelunker r:o:4,56,0:6,72,2 mine iron_ore", lines.get(1));
        assertEquals(2, lines.size());
    }

    private static void assertCovered(AreaOrders.Box box, AreaOrders.Mine mine, boolean alongX) {
        int cursor = alongX ? box.x1() : box.z1();
        for (AreaOrders.Strip s : mine.strips()) {
            assertEquals(cursor, alongX ? s.box().x1() : s.box().z1());
            assertEquals(box.y1(), s.box().y1());
            assertEquals(box.y2(), s.box().y2());
            if (alongX) {
                assertEquals(box.z1(), s.box().z1());
                assertEquals(box.z2(), s.box().z2());
            } else {
                assertEquals(box.x1(), s.box().x1());
                assertEquals(box.x2(), s.box().x2());
            }
            assertFalse(s.box().x2() - s.box().x1() >= AreaOrders.MAX_EDGE);
            cursor = (alongX ? s.box().x2() : s.box().z2()) + 1;
        }
        assertEquals((alongX ? box.x2() : box.z2()) + 1, cursor);
    }

    private static boolean inside(AreaOrders.Box box, AreaOrders.Assign a) {
        return a.x() >= box.x1() && a.x() <= box.x2() && a.z() >= box.z1() && a.z() <= box.z2();
    }

    private static List<String> names(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("b" + i);
        }
        return out;
    }
}
