package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When a totem is wanted on us and when a spare one is held in the hand: the rules, with the policy's own number. */
public class CombatTotemTest {

    private final CombatPolicy.Totems p = new CombatPolicy().totems;
    private final double handAt = p.handAt.real(), wantAt = p.wantAt.real();

    /** What the fight did before the hand rule was added, and still does. */
    @Test
    public void aTotemIsWantedWhenLowAndAllThroughAnExplosiveFight() {
        assertTrue(CombatSurvival.Totem.wanted(8, false, wantAt));
        assertFalse(CombatSurvival.Totem.wanted(8.5f, false, wantAt));
        assertTrue(CombatSurvival.Totem.wanted(20, true, wantAt));
    }

    @Test
    public void aSpareTotemIsHeldWhileABlastIsNearAndTheOffhandIsEmpty() {
        assertTrue(CombatSurvival.Totem.inHand(false, 3, true, 20, handAt));
        assertFalse("the offhand has its own: the hand is the weapon's", CombatSurvival.Totem.inHand(true, 3, true, 20, handAt));
        assertFalse("none in the hotbar to hold", CombatSurvival.Totem.inHand(false, -1, true, 20, handAt));
    }

    @Test
    public void withoutABlastOnlyNextToNoHealthTakesTheHand() {
        assertFalse(CombatSurvival.Totem.inHand(false, 0, false, (float) handAt + 0.5f, handAt));
        assertTrue(CombatSurvival.Totem.inHand(false, 0, false, (float) handAt, handAt));
        assertTrue("the hand is not taken from the weapon earlier than a totem is wanted at all", handAt <= wantAt);
    }
}
