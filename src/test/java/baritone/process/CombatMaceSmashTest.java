package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The dive's swing and the critical hit it can be made to wait for: the rule, with the policy's own numbers. */
public class CombatMaceSmashTest {

    private final CombatPolicy.Mace p = new CombatPolicy().mace;
    private final float smash = (float) p.smashCharge.real();
    private final float last = (float) p.landingCharge.real();

    @Test
    public void leftAloneTheDiveSwingsAtTheChargeItAlwaysDid() {
        assertEquals(0, p.smashCrit.get());
        assertTrue(CombatMace.Smash.charge(smash, false) == smash);
        assertTrue(CombatMace.Smash.swing(smash, smash, last, false, false));
        assertFalse(CombatMace.Smash.swing(smash - 0.01f, smash, last, false, false));
        assertTrue("on the last tick the lower charge is enough", CombatMace.Smash.swing(last, smash, last, true, false));
        assertFalse(CombatMace.Smash.swing(last - 0.01f, smash, last, true, false));
    }

    @Test
    public void afterTheCriticalHitItWaitsForTheChargeThatGivesOne() {
        float need = CombatMace.Smash.charge(smash, true);
        assertTrue(need >= CombatMace.Smash.CRIT && need > smash);
        assertFalse(CombatMace.Smash.swing(0.85f, need, last, false, false));
        assertTrue(CombatMace.Smash.swing(0.9f, need, last, false, false));
        assertTrue("a charge tuned above it is kept", CombatMace.Smash.charge(0.99f, true) == 0.99f);
    }

    @Test
    public void aSprintHoldsTheSwingBackOnlyUntilTheLastTick() {
        float need = CombatMace.Smash.charge(smash, true);
        assertFalse(CombatMace.Smash.swing(1f, need, last, false, true));
        assertTrue("the fall is not wasted on a sprint that would not end", CombatMace.Smash.swing(1f, need, last, true, true));
    }

    @Test
    public void theSprintIsLeftOnTheWayDownAndNotOnTheWayUp() {
        assertTrue(CombatMace.Smash.unsprint(-0.5, p.critLead, p.critLead));
        assertFalse(CombatMace.Smash.unsprint(-0.5, p.critLead + 1, p.critLead));
        assertFalse(CombatMace.Smash.unsprint(0.3, 0, p.critLead));
        assertTrue("the server hears of a sprint ending a tick before the swing it would spoil", p.critLead >= 2);
    }
}
