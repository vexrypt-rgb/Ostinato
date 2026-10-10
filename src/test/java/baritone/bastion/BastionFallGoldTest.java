package baritone.bastion;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BastionFallGoldTest {
    private static BastionDrops.Ledge ledge(int x, int y, int landY, boolean safe) { return new BastionDrops.Ledge(x, y, 0, 1, 0, landY, safe); }

    @Test
    public void damageIsBlocksPastThree() {
        assertEquals(0f, BastionDrops.damage(3), 0);
        assertEquals(9f, BastionDrops.damage(12), 0);
    }

    @Test
    public void choosesSurvivableDropThatSavesTheStairs() {
        BastionDrops.Ledge good = ledge(2, 70, 58, true), lava = ledge(1, 70, 58, false), deadly = ledge(1, 70, 40, true), small = ledge(1, 70, 68, true);
        // target 12 below and 6 across: the 12-block drop (9 damage at 20 hp) beats ~30 blocks of stairs
        assertEquals(good, BastionDrops.choose(List.of(lava, deadly, small, good), 0, 70, 0, 6, 58, 0, 20, 3));
        // at 12 hp the same drop leaves less than the margin
        assertNull(BastionDrops.choose(List.of(good), 0, 70, 0, 6, 58, 0, 12, 3));
    }

    @Test
    public void noDropWhenTargetIsNotBelow() {
        assertNull(BastionDrops.choose(List.of(ledge(2, 70, 58, true)), 0, 70, 0, 6, 70, 0, 20, 3));
    }

    @Test
    public void clutchEarlyNotAtTheBottom() {
        // 2 fallen, 14 to go: place now
        assertEquals(BastionDrops.Clutch.PLACE, BastionDrops.clutch(2, 14, false, false, 20, 3, true));
        // a short hop
        assertEquals(BastionDrops.Clutch.NONE, BastionDrops.clutch(1, 2, false, false, 20, 3, true));
        // a planned verified drop is left alone, unless it turned out to end in lava
        assertEquals(BastionDrops.Clutch.NONE, BastionDrops.clutch(2, 10, false, true, 20, 3, true));
        assertEquals(BastionDrops.Clutch.PLACE, BastionDrops.clutch(2, 10, true, true, 20, 3, true));
        // a 4-block fall (1 damage) is not worth a block
        assertEquals(BastionDrops.Clutch.NONE, BastionDrops.clutch(2, 2.2, false, false, 20, 3, true));
        // nothing to place
        assertEquals(BastionDrops.Clutch.NONE, BastionDrops.clutch(2, 14, false, false, 20, 3, false));
        // landing next tick: a block now saves nothing
        assertEquals(BastionDrops.Clutch.NONE, BastionDrops.clutch(10, 1, false, false, 20, 3, true));
    }

    @Test
    public void goldBookkeeping() {
        assertEquals(9 + 18 + 2, BastionGoals.ingotEquivalent(9, 20, 2));
        assertEquals(9 + 18, BastionGoals.throwable(9, 2));
        Map<String, Integer> miss = Map.of("obsidian", 2);
        int need = BastionGoals.throwsNeeded(miss, 64);
        assertEquals((int) Math.ceil(2 / (40 / 459.0)), need);
        assertTrue(BastionGoals.needGold(miss, need - 1, 0, 64));
        assertFalse(BastionGoals.needGold(miss, need, 0, 64));
        assertFalse(BastionGoals.needGold(Map.of(), 0, 0, 64));
        assertEquals(64, BastionGoals.throwsNeeded(Map.of("ender_pearl", 12), 64));
    }
}