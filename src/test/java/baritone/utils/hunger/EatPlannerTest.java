package baritone.utils.hunger;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato code. */
public class EatPlannerTest {
    private static Food f(String id) {
        return VanillaFoods.get(id);
    }

    @Test
    public void prefersSaturationAndAvoidsWaste() {
        HungerModel m = new HungerModel(10, 0, 0, 20);
        assertEquals("golden_carrot", EatPlanner.choose(m, Arrays.asList(f("bread"), f("golden_carrot"), f("cookie")), false).id);
        // empty saturation: steak's 12.8 saturation still beats bread even with 5 nutrition lost
        assertEquals("cooked_beef", EatPlanner.choose(new HungerModel(17, 0, 0, 20), Arrays.asList(f("cooked_beef"), f("bread")), false).id);
        HungerModel almost = new HungerModel(17, 15, 0, 20);
        // saturation near its cap: steak would waste 5 of 8 and most of its saturation; bread wastes 2
        assertEquals(5, EatPlanner.waste(almost, f("cooked_beef")));
        assertEquals("bread", EatPlanner.choose(almost, Arrays.asList(f("cooked_beef"), f("bread")), false).id);
        // risky last
        assertEquals("bread", EatPlanner.choose(m, Arrays.asList(f("rotten_flesh"), f("bread")), false).id);
    }

    @Test
    public void goldenApplesOnlyForHealth() {
        HungerModel m = new HungerModel(20, 2, 0, 20);
        assertNull(EatPlanner.choose(m, Arrays.asList(f("golden_apple"), f("bread")), false)); // full: only always-edible, and that's reserved
        HungerModel hurt = new HungerModel(20, 2, 0, 6);
        assertEquals("golden_apple", EatPlanner.choose(hurt, Arrays.asList(f("golden_apple"), f("bread")), true).id);
    }

    @Test
    public void eatsInPausesNotWhileRunning() {
        List<Food> foods = Collections.singletonList(f("cooked_beef"));
        HungerModel m = new HungerModel(12, 0, 0, 20);
        assertFalse(EatPlanner.decide(m, foods, EatPlanner.Pause.NONE, -1, -1, 8).eat);
        EatPlanner.Decision d = EatPlanner.decide(m, foods, EatPlanner.Pause.PATH_SEARCH, -1, -1, 8);
        assertTrue(d.eat);
        assertFalse(d.urgent);
        assertTrue(EatPlanner.decide(m, foods, EatPlanner.Pause.LADDER, -1, -1, 8).eat);
        assertTrue(EatPlanner.decide(m, foods, EatPlanner.Pause.MINING, 40, -1, 8).eat);
        assertFalse(EatPlanner.decide(m, foods, EatPlanner.Pause.MINING, 5, -1, 8).eat);
        // 15 food: steak wastes 3 and we're not hurt: wait
        assertFalse(EatPlanner.decide(new HungerModel(15, 0, 0, 20), foods, EatPlanner.Pause.IDLE, -1, -1, 8).eat);
        // ... unless hurt (regen wants 18+) or the route runs out
        assertTrue(EatPlanner.decide(new HungerModel(15, 0, 0, 14), foods, EatPlanner.Pause.IDLE, -1, -1, 8).eat);
    }

    @Test
    public void urgentStops() {
        List<Food> foods = Collections.singletonList(f("bread"));
        assertTrue(EatPlanner.decide(new HungerModel(6, 0, 0, 20), foods, EatPlanner.Pause.NONE, -1, -1, 8).urgent);
        assertTrue(EatPlanner.decide(new HungerModel(12, 0, 0, 20), foods, EatPlanner.Pause.NONE, -1, 150, 8).urgent);
        assertTrue(EatPlanner.decide(new HungerModel(14, 0, 0, 7), foods, EatPlanner.Pause.NONE, -1, -1, 8).urgent);
        assertFalse(EatPlanner.decide(new HungerModel(14, 0, 0, 20), Collections.emptyList(), EatPlanner.Pause.IDLE, -1, -1, 8).eat);
    }

    @Test
    public void damagePricedFromRegen() {
        HungerModel full = new HungerModel(20, 20, 0, 20);
        // 4 hp: 24 exhaustion = 6 hunger points at steak's 20.8 points per 32 ticks, plus 40 ticks of fast regen * 0.05
        double c = EatPlanner.damageCostTicks(full, 4, 0, f("cooked_beef"), 0.05, 8);
        assertEquals(6 * 32 / 20.8 + 40 * 0.05, c, 1e-3);
        // no food: far dearer
        assertTrue(EatPlanner.damageCostTicks(full, 4, 0, null, 0.05, 8) > 300);
        // under the floor
        assertEquals(EatPlanner.INFINITY, EatPlanner.damageCostTicks(new HungerModel(20, 20, 0, 10), 4, 0, f("bread"), 0.05, 8), 0);
        // at 18 food regen is 80 ticks per hp: costlier
        assertTrue(EatPlanner.damageCostTicks(new HungerModel(18, 0, 0, 20), 4, 0, f("cooked_beef"), 0.05, 8) > c);
    }

    @Test
    public void dropAndEatVersusSafeRoute() {
        HungerModel m = new HungerModel(20, 10, 0, 20);
        // 8 block drop (5 hp) in 30 ticks vs a 200 tick staircase: drop
        assertTrue(EatPlanner.takeDamageRoute(30, 200, m, 5, 0, f("cooked_beef"), 0.05, 8));
        // vs a 40 tick staircase: walk
        assertFalse(EatPlanner.takeDamageRoute(30, 40, m, 5, 0, f("cooked_beef"), 0.05, 8));
        // no food at all: walk the long way
        assertFalse(EatPlanner.takeDamageRoute(30, 200, m, 5, 0, null, 0.05, 8));
    }
}
