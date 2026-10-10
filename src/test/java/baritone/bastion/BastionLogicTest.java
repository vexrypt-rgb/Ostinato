package baritone.bastion;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BastionLogicTest {
    private static BastionPlan.Point chest(int x, int y, int z) { return new BastionPlan.Point(x, y, z, BastionPlan.Kind.CHEST); }
    private static BastionPlan.Point gold(int x, int y, int z) { return new BastionPlan.Point(x, y, z, BastionPlan.Kind.GOLD); }

    @Test
    public void treasureVisitsCoreBeforeRamparts() {
        BastionPlan.Point rampart = chest(30, 70, 0), core1 = chest(3, 40, 2), core2 = gold(-4, 45, -3);
        // start right next to the rampart chest: still the core comes first
        List<BastionPlan.Point> o = BastionPlan.order("treasure", 0, 50, 0, 28, 70, 0, List.of(rampart, core1, core2));
        assertEquals(rampart, o.get(2));
    }

    @Test
    public void bridgePrefersChestsOverGold() {
        BastionPlan.Point g = gold(5, 64, 0), c = chest(20, 64, 0);
        List<BastionPlan.Point> o = BastionPlan.order("bridge", 0, 64, 0, 0, 64, 0, List.of(g, c));
        assertEquals(c, o.get(0));
    }

    @Test
    public void nearestFirstChainsFromLastPoint() {
        BastionPlan.Point a = chest(5, 64, 0), b = chest(10, 64, 0), far = chest(-12, 64, 0);
        List<BastionPlan.Point> o = BastionPlan.order("housing", 0, 64, 0, 0, 64, 0, List.of(far, b, a));
        assertEquals(List.of(a, b, far), o);
    }

    @Test
    public void climbingCostsMoreThanDropping() {
        BastionPlan.Point up = chest(4, 74, 0), down = chest(4, 54, 0);
        assertEquals(down, BastionPlan.order("housing", 0, 64, 0, 0, 64, 0, List.of(up, down)).get(0));
    }

    @Test
    public void barterStopsWhenTargetsMet() {
        Map<String, Integer> t = Map.of("ender_pearl", 12, "obsidian", 10);
        Map<String, Integer> have = new HashMap<>(Map.of("ender_pearl", 12, "obsidian", 10));
        assertTrue(BastionGoals.met(have, t));
        assertFalse(BastionGoals.shouldThrow(have, t, 20, 0, 0, 4));
        have.put("obsidian", 9);
        assertTrue(BastionGoals.shouldThrow(have, t, 20, 0, 0, 4));
        assertEquals(Map.of("obsidian", 1), BastionGoals.missing(have, t));
        // one missing, one barter already in flight: wait for it
        assertFalse(BastionGoals.shouldThrow(have, t, 20, 0, 1, 4));
    }

    @Test
    public void barterRespectsReserveAndConcurrency() {
        Map<String, Integer> t = Map.of("ender_pearl", 12);
        Map<String, Integer> have = Map.of();
        assertFalse(BastionGoals.shouldThrow(have, t, 2, 2, 0, 4));
        assertFalse(BastionGoals.shouldThrow(have, t, 20, 0, 4, 4));
        assertTrue(BastionGoals.shouldThrow(have, t, 20, 0, 3, 4));
    }

    @Test
    public void lootFilters() {
        assertTrue(BastionGoals.chestUseful("obsidian", false));
        assertTrue(BastionGoals.chestUseful("cooked_salmon", true));
        assertFalse(BastionGoals.chestUseful("rotten_flesh", true));
        assertFalse(BastionGoals.chestUseful("golden_sword", false));
        assertFalse(BastionGoals.barterUseful("quartz"));
        assertTrue(BastionGoals.barterUseful("ender_pearl"));
    }

    @Test
    public void settingsParse() {
        assertNull(BastionSettings.set("ender_pearl", "16"));
        assertEquals(16, (int) BastionSettings.TARGETS.get("ender_pearl"));
        assertNotNull(BastionSettings.set("barters", "x"));
        BastionSettings.resetTargets();
    }
}
