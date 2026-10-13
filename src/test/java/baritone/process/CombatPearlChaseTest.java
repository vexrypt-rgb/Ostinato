package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When a pearl is thrown to catch a foe, and which of two maces a smash is taken with: the rules, with the policy's own numbers. */
public class CombatPearlChaseTest {

    private final CombatPolicy.Pearls p = new CombatPolicy().pearls;

    private boolean go(double dist, int slow, boolean eating, float hp, boolean los, boolean grounded) {
        return CombatPearl.Chase.go(dist, p.chaseLo, p.chaseHi, slow, p.chaseAfter, eating, hp, p.chaseHp, los, grounded);
    }

    @Test
    public void aPearlGoesWhenTheRunHasStoppedGaining() {
        double mid = (p.chaseLo + p.chaseHi) / 2;
        assertTrue(go(mid, p.chaseAfter, false, 20, true, true));
        assertFalse("the run is still closing", go(mid, p.chaseAfter - 1, false, 20, true, true));
        assertTrue("a foe that has stopped to eat is not waited on", go(mid, 0, true, 20, true, true));
    }

    @Test
    public void notWhenRunningGetsThereOrTheThrowIsAGuess() {
        assertFalse(go(p.chaseLo, 99, true, 20, true, true));
        assertFalse(go(p.chaseHi, 99, true, 20, true, true));
        assertFalse("unseen", go(p.chaseLo + 1, 99, true, 20, false, true));
        assertFalse("mid-air", go(p.chaseLo + 1, 99, true, 20, true, false));
    }

    /** The landing costs five points: the pearl is not thrown by someone it would leave next to dead. */
    @Test
    public void notWhenTheLandingIsMoreThanWeCanAfford() {
        assertFalse(go(p.chaseLo + 1, 99, true, (float) p.chaseHp - 0.5f, true, true));
        assertTrue(p.chaseHp > 5 + 4);
    }

    /** The throw waits for the turn and for the ground, and a turn that does not finish costs a short wait, not the long one. */
    @Test
    public void thePearlLeavesTheHandFacedAndGrounded() {
        assertTrue(CombatPearl.Chase.release(true, true));
        assertFalse("still turning", CombatPearl.Chase.release(false, true));
        assertFalse("in the air the throw takes our own rise with it", CombatPearl.Chase.release(true, false));
        assertTrue("the turn is given longer than a hop lasts", p.chaseAim >= 12);
        assertTrue(p.chaseRetry < p.chaseRest);
    }

    @Test
    public void theSlowCountRunsOnlyWhileFarAndNotGaining() {
        assertEquals(1, CombatPearl.Chase.slow(0, 20, 19.5, p.chaseGain, p.chaseLo));
        assertEquals(3, CombatPearl.Chase.slow(2, 20, 21, p.chaseGain, p.chaseLo));
        assertEquals("a second that closed the gap starts the count over", 0, CombatPearl.Chase.slow(2, 20, 20 - p.chaseGain, p.chaseGain, p.chaseLo));
        assertEquals("near enough to run", 0, CombatPearl.Chase.slow(2, p.chaseLo, p.chaseLo, p.chaseGain, p.chaseLo));
        assertTrue("a sprint, 5.6 blocks a second, counts as gaining", p.chaseGain < 5.6);
    }

    @Test
    public void theFallBonusIsTheGamesOwn() {
        assertEquals(0, CombatInventory.MacePick.smash(1.4), 1e-9);
        assertEquals(12, CombatInventory.MacePick.smash(3), 1e-9);
        assertEquals(22, CombatInventory.MacePick.smash(8), 1e-9);
        assertEquals(24, CombatInventory.MacePick.smash(10), 1e-9);
    }

    @Test
    public void armourStopsLessOfAHarderHitAndLessStillUnderBreach() {
        assertEquals("no armour, nothing stopped", 10, CombatInventory.MacePick.through(10, 0, 0, 0), 1e-9);
        // full diamond: 20 armour, 8 toughness. 10 damage: worn = 20 - 10/4 = 17.5, 70% stopped
        assertEquals(3.0, CombatInventory.MacePick.through(10, 20, 8, 0), 1e-9);
        // Breach IV takes 60 points off that share
        assertEquals(9.0, CombatInventory.MacePick.through(10, 20, 8, 4), 1e-9);
        assertEquals("the share stopped never goes under nothing", 10, CombatInventory.MacePick.through(10, 5, 0, 4), 1e-9);
    }

    /** What the pick turns on: Breach for the short hop onto armour, Density for the long drop and for bare skin. */
    @Test
    public void breachForAShortFallOnArmourDensityForALongOne() {
        double shortHop = 2, longDrop = 40;
        assertTrue(CombatInventory.MacePick.worth(shortHop, 20, 8, 0, 4) > CombatInventory.MacePick.worth(shortHop, 20, 8, 5, 0));
        assertTrue(CombatInventory.MacePick.worth(longDrop, 20, 8, 5, 0) > CombatInventory.MacePick.worth(longDrop, 20, 8, 0, 4));
        assertTrue("nothing for Breach to go through", CombatInventory.MacePick.worth(shortHop, 0, 0, 5, 0) > CombatInventory.MacePick.worth(shortHop, 0, 0, 0, 4));
        assertEquals("on the ground neither fall bonus counts", CombatInventory.MacePick.worth(0, 0, 0, 5, 0), CombatInventory.MacePick.worth(0, 0, 0, 0, 0), 1e-9);
    }
}
