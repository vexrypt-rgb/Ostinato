package baritone.bastion;

import org.junit.Test;
import static org.junit.Assert.*;
import static baritone.bastion.UnderwaterLoot.Step.*;
import static baritone.bastion.UnderwaterLoot.next;

public class UnderwaterLootTest {
    @Test
    public void takesOnlyUsefulItems() {
        assertTrue(UnderwaterLoot.useful("iron_ingot"));
        assertTrue(UnderwaterLoot.useful("emerald"));
        assertFalse(UnderwaterLoot.useful("rotten_flesh"));
    }

    @Test
    public void swimsThenOpens() {
        assertEquals(SWIM_TO, next(10, -5, 300, 5, false, false, false));
        assertEquals(OPEN, next(2, -2, 300, 5, false, false, false));
        assertEquals(SURFACE, next(2, -2, 300, 5, true, false, false));
    }

    @Test
    public void buriedChestPocketThenDig() {
        assertEquals(POCKET, next(0.3, -6, 300, 8, false, false, false));
        assertEquals(DIG_DOWN, next(0.3, -6, 300, 8, false, true, false));
        assertEquals(DONE, next(0.3, -6, 300, 8, false, true, true));
        assertEquals(1, UnderwaterLoot.doorsFor(4));
    }

    @Test
    public void lowAirGoesUpOrPockets() {
        assertEquals(SURFACE, next(10, -5, 90, 3, false, false, false));
        assertEquals(POCKET, next(10, -5, 100, 20, false, false, false));
        assertEquals(POCKET, next(10, -5, 60, -1, false, false, false));
    }

    @Test
    public void shipwreckChestOrder() {
        assertTrue(UnderwaterLoot.shipwreckPriority("minecraft:chests/shipwreck_treasure") < UnderwaterLoot.shipwreckPriority("minecraft:chests/shipwreck_supply"));
        assertTrue(UnderwaterLoot.shipwreckPriority("minecraft:chests/shipwreck_supply") < UnderwaterLoot.shipwreckPriority("minecraft:chests/shipwreck_map"));
    }
}