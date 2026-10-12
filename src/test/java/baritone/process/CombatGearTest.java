package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** What wear decides about the weapon in hand: the best tier, the least worn of equals, and nothing broken while something else will do. */
public class CombatGearTest {

    private static final int NONE = -1, FRESH = 1561;

    private static int pick(int[] rank, int[] left, int[] perHit) {
        return CombatInventory.Gear.pick(rank, left, perHit, false);
    }

    @Test
    public void theBestTierIsTakenAndTheLeastWornOfEquals() {
        assertEquals(2, pick(new int[]{NONE, 1, 0, 1}, new int[]{0, FRESH, 40, FRESH}, new int[]{1, 1, 1, 1}));
        assertEquals(3, pick(new int[]{1, NONE, 1, 1}, new int[]{200, 0, 90, 900}, new int[]{1, 1, 1, 1}));
        assertEquals("the first found of two as worn, as before wear was looked at", 0, pick(new int[]{1, 1}, new int[]{50, 50}, new int[]{1, 1}));
        assertEquals(NONE, pick(new int[]{NONE, NONE}, new int[]{9, 9}, new int[]{1, 1}));
        assertEquals(NONE, pick(new int[0], new int[0], new int[0]));
    }

    @Test
    public void oneItsNextHitWouldBreakIsPassedOver() {
        assertEquals("a worse tier that lasts", 1, pick(new int[]{0, 1}, new int[]{1, 30}, new int[]{1, 1}));
        assertEquals("two left is one hit of an axe", NONE, pick(new int[]{0}, new int[]{2}, new int[]{2}));
        assertEquals(0, pick(new int[]{0}, new int[]{3}, new int[]{2}));
        assertEquals(0, pick(new int[]{0}, new int[]{2}, new int[]{1}));
        assertEquals(NONE, pick(new int[]{0, 1}, new int[]{1, 1}, new int[]{1, 1}));
    }

    @Test
    public void withNothingElseTheLastHitIsStillAHit() {
        assertEquals(0, CombatInventory.Gear.pick(new int[]{0, 1}, new int[]{1, 1}, new int[]{1, 1}, true));
        assertEquals(1, CombatInventory.Gear.pick(new int[]{NONE, 2}, new int[]{0, 1}, new int[]{1, 2}, true));
    }

    @Test
    public void aBarePlaceGetsTheBestPieceCarriedForIt() {
        boolean[] legsBare = {false, false, true, false};
        assertEquals("the one that stops more", 2, CombatInventory.Gear.wear(new int[]{2, 0, 2, -1}, new double[]{5, 3, 6, 9}, new int[]{9, 9, 9, 9}, legsBare));
        assertEquals("the least worn of equals", 0, CombatInventory.Gear.wear(new int[]{2, 2}, new double[]{6, 6}, new int[]{200, 30}, legsBare));
        assertEquals("never in exchange for one worn", NONE, CombatInventory.Gear.wear(new int[]{0, 1, 3}, new double[]{3, 8, 3}, new int[]{9, 9, 9}, legsBare));
        assertEquals("what stops nothing is not armour", NONE, CombatInventory.Gear.wear(new int[]{2}, new double[]{0}, new int[]{9}, legsBare));
        assertEquals(NONE, CombatInventory.Gear.wear(new int[]{2, 9}, new double[]{6, 6}, new int[]{9, 9}, new boolean[4]));
        assertTrue(CombatInventory.Gear.WEAR_AGAIN > 2); // a click is asked for on one tick and made on the next
    }

    @Test
    public void aPieceAboutToBreakGivesItsPlaceToACarriedOne() {
        int last = CombatInventory.Gear.ARMOR_LAST, whole = Integer.MAX_VALUE;
        int[] headGoing = {last, 200, whole, 90}; // the legs bare: no end to what is not there
        assertEquals("the one for that place", 1, CombatInventory.Gear.change(new int[]{1, 0, -1}, new double[]{8, 2, 9}, new int[]{240, 165, 9}, headGoing, last));
        assertEquals("whatever the two stop", 0, CombatInventory.Gear.change(new int[]{0}, new double[]{1}, new int[]{55}, headGoing, last));
        assertEquals("the one that stops more", 1, CombatInventory.Gear.change(new int[]{0, 0}, new double[]{2, 3}, new int[]{165, 100}, headGoing, last));
        assertEquals("the least worn of equals", 0, CombatInventory.Gear.change(new int[]{0, 0}, new double[]{2, 2}, new int[]{165, 100}, headGoing, last));
        assertEquals("not for one as far gone", NONE, CombatInventory.Gear.change(new int[]{0}, new double[]{2}, new int[]{last}, headGoing, last));
        assertEquals("one hit more and it is changed", NONE, CombatInventory.Gear.change(new int[]{0}, new double[]{2}, new int[]{165}, new int[]{last + 1, 200, whole, 90}, last));
        assertEquals("a bare place is for wear", NONE, CombatInventory.Gear.change(new int[]{2}, new double[]{6}, new int[]{225}, headGoing, last));
        assertEquals("what stops nothing is not armour", NONE, CombatInventory.Gear.change(new int[]{0}, new double[]{0}, new int[]{432}, headGoing, last));
        assertTrue("the hardest hit by hand takes three from a piece", last >= 3 && last < 55 / 2);
    }

    @Test
    public void whatIsKeptBackIsNotToSpare() {
        assertTrue("nothing kept back: as before a count was looked at", CombatInventory.Gear.spare(1, 0));
        assertTrue(!CombatInventory.Gear.spare(0, 0));
        assertTrue(CombatInventory.Gear.spare(3, 2));
        assertTrue(!CombatInventory.Gear.spare(2, 2) && !CombatInventory.Gear.spare(1, 2));
        assertTrue(CombatInventory.Gear.spare(1, -3));
    }

    @Test
    public void whatDoesNotWearHasNoEnd() {
        assertEquals(Integer.MAX_VALUE, CombatInventory.Gear.left(0, 0));
        assertEquals(336, CombatInventory.Gear.left(336, 0));
        assertEquals(1, CombatInventory.Gear.left(336, 335));
        assertEquals(0, CombatInventory.Gear.left(336, 400));
        assertEquals(0, pick(new int[]{0, 0}, new int[]{Integer.MAX_VALUE, 5}, new int[]{1, 1}));
        assertTrue("a shield with one hard blocked hit left is changed, a fresh one is not", CombatInventory.Gear.SHIELD_LAST >= 16 && CombatInventory.Gear.SHIELD_LAST < 336 / 4);
    }
}
