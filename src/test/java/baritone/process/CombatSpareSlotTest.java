package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Which hotbar slot gives way when combat pulls an item out of the inventory. */
public class CombatSpareSlotTest {

    private static final boolean[] NONE = new boolean[9];

    private static boolean[] at(int... slots) {
        boolean[] out = new boolean[9];
        for (int s : slots) out[s] = true;
        return out;
    }

    @Test
    public void anEmptySlotComesFirst() {
        assertEquals(5, CombatInventory.Spare.slot(at(2, 5), at(8), new long[9], 0));
    }

    @Test
    public void swordsAndAxesStay() {
        assertEquals(6, CombatInventory.Spare.slot(NONE, at(7, 8), new long[9], 0));
    }

    @Test
    public void theSlotInHandGoesLast() {
        assertEquals(7, CombatInventory.Spare.slot(NONE, NONE, new long[9], 8));
        // nothing else to give: every other slot holds a sword or an axe
        assertEquals(3, CombatInventory.Spare.slot(NONE, at(0, 1, 2, 4, 5, 6, 7, 8), new long[9], 3));
    }

    @Test
    public void allWeaponsFallsBackToTheLastSlot() {
        assertEquals(8, CombatInventory.Spare.slot(NONE, at(0, 1, 2, 3, 4, 5, 6, 7, 8), new long[9], 0));
    }

    /** Two absent items asked for in turn, as a fight does every tick: the second must not throw the first out. */
    @Test
    public void twoItemsPulledInTurnDoNotEvictEachOther() {
        long[] pulled = new long[9];
        long pulls = 0;
        boolean[] weapon = at(0);
        int first = CombatInventory.Spare.slot(NONE, weapon, pulled, 0);
        pulled[first] = ++pulls;
        int second = CombatInventory.Spare.slot(NONE, weapon, pulled, 0);
        pulled[second] = ++pulls;
        assertEquals(8, first);
        assertEquals(7, second);
    }

    /** With more items wanted than slots to give, the one pulled longest ago goes, not the one just pulled. */
    @Test
    public void whenEverySlotWasPulledIntoTheOldestGoes() {
        long[] pulled = {0, 0, 0, 0, 0, 0, 3, 1, 2};
        assertEquals(7, CombatInventory.Spare.slot(NONE, at(0, 1, 2, 3, 4, 5), pulled, 0));
    }
}
