package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When a potion is thrown: the rules, with the policy's own numbers. */
public class CombatPotionsTest {

    private final CombatPolicy.Pots p = new CombatPolicy().pots;
    private final double healAt = p.healAt.real();

    @Test
    public void aHealingPotionGoesAtTheLineAndNotAboveIt() {
        assertTrue(CombatPotions.Rule.heal((float) healAt, healAt, 0));
        assertFalse(CombatPotions.Rule.heal((float) healAt + 0.5f, healAt, 0));
    }

    /** A bite takes 32 ticks and is most of the way to its own healing: it is only dropped for a potion when that is too far off. */
    @Test
    public void aBiteGoingDownIsFinishedUnlessItIsTooLate() {
        assertFalse(CombatPotions.Rule.heal((float) healAt, healAt, 12));
        assertTrue(CombatPotions.Rule.heal((float) (healAt / 2), healAt, 12));
    }

    /** A splash reaches four blocks: thrown with the foe inside that, it is the foe's boost as well. */
    @Test
    public void aBoostIsNotSharedWithTheFoe() {
        assertTrue("a splash reaches 4 blocks", p.boostDist >= 4);
        assertTrue(CombatPotions.Rule.boost(0, p.renewTicks, p.boostDist + 0.1, p.boostDist, false, 0));
        assertFalse(CombatPotions.Rule.boost(0, p.renewTicks, p.boostDist, p.boostDist, false, 0));
        assertFalse(CombatPotions.Rule.boost(0, p.renewTicks, 3.0, p.boostDist, false, 0));
    }

    @Test
    public void aBoostStillRunningIsNotThrownAgain() {
        assertFalse(CombatPotions.Rule.boost(p.renewTicks, p.renewTicks, 20, p.boostDist, false, 0));
        assertFalse(CombatPotions.Rule.boost(Integer.MAX_VALUE, p.renewTicks, 20, p.boostDist, false, 0));
        assertTrue(CombatPotions.Rule.boost(p.renewTicks - 1, p.renewTicks, 20, p.boostDist, false, 0));
    }

    @Test
    public void regenerationWaitsForSomethingToRegenerate() {
        assertFalse(CombatPotions.Rule.boost(0, p.renewTicks, 20, p.boostDist, true, 3.5f));
        assertTrue(CombatPotions.Rule.boost(0, p.renewTicks, 20, p.boostDist, true, 4f));
        assertTrue("any other boost does not ask", CombatPotions.Rule.boost(0, p.renewTicks, 20, p.boostDist, false, 0f));
    }

    @Test
    public void whatHarmsIsThrownFromItsBandAndInSight() {
        double mid = (p.harmLo + p.harmHi) / 2;
        assertTrue(CombatPotions.Rule.harm(mid, p.harmLo, p.harmHi, true, false));
        assertFalse("not through a wall", CombatPotions.Rule.harm(mid, p.harmLo, p.harmHi, false, false));
        assertFalse("not from inside a sword's reach", CombatPotions.Rule.harm(p.harmLo, p.harmLo, p.harmHi, true, false));
        assertFalse(CombatPotions.Rule.harm(p.harmHi, p.harmLo, p.harmHi, true, false));
        assertFalse("not while the last one is still at work", CombatPotions.Rule.harm(mid, p.harmLo, p.harmHi, true, true));
    }

    /** The numbers as they were when the healing and harming throws lived among the side tools. */
    @Test
    public void theBaselinesAreTheNumbersTheyWere() {
        assertEquals(9, healAt, 0);
        assertEquals(3, p.harmLo, 0);
        assertEquals(12, p.harmHi, 0);
        assertEquals(25, p.harmGap);
    }
}
