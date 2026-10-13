package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A value moved while running has to reach the shield's own decision, and has to be able to go back. */
public class CombatShieldTuneTest {

    private static final double NEAR = 3.0;

    private final CombatPolicy policy = new CombatPolicy();
    /** Built the way the process builds it: handed the policy's shield group once, before any value moves. */
    private final CombatShield shield = new CombatShield(null, null, null, null, null, null, policy.shield, null);
    private final CombatPolicy.Tuned dueLate = policy.shield.dueLate;
    /** The first tick after the foe's last swing that is past the baseline window. */
    private final int justPast = policy.shield.defaultGap + dueLate.base;

    @Test
    public void theBaselineWindowEndsWhereThePolicySays() {
        assertTrue(shield.swingDue(true, justPast - 1, NEAR));
        assertFalse(shield.swingDue(true, justPast, NEAR));
    }

    /** Left alone, the due test is the expression it was before the value could move, for every gap, tick and distance. */
    @Test
    public void untouchedItDecidesAsItAlwaysDid() {
        CombatPolicy.Shield p = policy.shield;
        for (int learned = 0; learned <= 40; learned++) {
            shield.swingGap = learned;
            int gap = learned > 0 ? learned : p.defaultGap;
            for (int since = -5; since <= 80; since++) {
                for (double dist = 0; dist <= 7; dist += 0.25) {
                    for (boolean sword : new boolean[]{true, false}) {
                        boolean was = sword && since >= gap - 6 && since < gap + 10 && dist < 4.5;
                        assertEquals("gap " + gap + " since " + since + " dist " + dist, was, shield.swingDue(sword, since, dist));
                    }
                }
            }
        }
    }

    /** The numbers that became movable are, left alone, the numbers they were: to the last bit of the comparison made with them. */
    @Test
    public void theBaselinesAreTheNumbersTheyWere() {
        CombatPolicy.Shield p = policy.shield;
        assertTrue((float) p.holdCharge.real() == 0.78f);
        assertTrue((float) p.raiseCharge.real() == 0.45f);
        assertEquals(40, p.holdMaxTicks.get());
        assertEquals(6, p.dueEarly.get());
        assertEquals(10, p.dueLate.get());
        assertTrue(p.dueDist.real() == 4.5);
        CombatPolicy.Mace m = policy.mace;
        assertTrue((float) m.smashCharge.real() == 0.6f);
        assertTrue((float) m.landingCharge.real() == 0.4f);
        assertEquals(14, m.hitCool.get());
        assertEquals(20, m.missCool.get());
        assertEquals("with a spear the dive swings at its own charge", "spear", m.smashCharge.not);
        assertEquals("spear", m.landingCharge.not);
        assertEquals("and waits its own time after a miss", "spear", m.missCool.not);
        assertEquals("the wait after a smash is the same with a spear", null, m.hitCool.not);
        CombatPolicy.Melee sword = policy.melee;
        assertTrue((float) sword.duelCharge.real() == 0.9f);
        assertTrue(sword.duelSpace.real() == 2.9);
        assertTrue((float) sword.spaceCharge.real() == 0.75f);
        assertTrue((float) sword.critCharge.real() == 0.9f);
        assertTrue((float) sword.jumpCharge.real() == 0.55f);
        assertEquals("under 0.9 a falling swing is no crit: the tuner is not to try one", 90, sword.critCharge.min);
        assertEquals("nor a sprinting swing a knockback hit", 90, sword.duelCharge.min);
        assertEquals("a mob does not duel", "/player", sword.duelCharge.kit);
        assertEquals("/player", sword.duelSpace.kit);
        assertEquals("/player", sword.spaceCharge.kit);
        assertEquals("a crit is a crit against anything", null, sword.critCharge.kit);
        assertEquals(null, sword.jumpCharge.kit);
        assertEquals(CombatPolicy.BASE, policy.setting());
        assertEquals("what the autotune may try and the baseline does not do", 0, sword.spaceAny.base + sword.shieldWait.base + sword.hitSelect.base);
        assertEquals("/player", sword.spaceAny.kit);
        assertEquals("/player", sword.shieldWait.kit);
        assertEquals("with an axe the shield is broken, not waited on", "axe", sword.shieldWait.not);
        assertEquals("/player", sword.hitSelect.kit);
        assertEquals("the dive's critical hit is the autotune's to try too", 0, m.smashCrit.base);
        assertEquals("mace", m.smashCrit.kit);
        assertEquals("with a spear the dive swings at its own charge", "spear", m.smashCrit.not);
        assertEquals(19, policy.tuned.size());
        for (CombatPolicy.Tuned t : policy.tuned) {
            assertEquals(t.name, t.base, t.get());
            assertTrue(t.name + " is tuned only in a kit that holds what it is about", t.name.startsWith("melee.") || t.kit != null && t.name.startsWith(t.kit + "."));
            assertEquals(t.name + " is not moved in a kit without a shield", t.name.startsWith("shield."), "shield".equals(t.kit));
            // a baseline may sit on a limit the game sets (a crit needs its charge): it still has one way to go
            boolean floor = t == sword.duelCharge || t == sword.critCharge || t == sword.spaceAny || t == sword.shieldWait || t == sword.hitSelect || t == m.smashCrit;
            assertTrue(t.name, (floor ? t.min == t.base : t.min <= t.base - t.step) && t.base + t.step <= t.max);
        }
    }

    /** The setting's name is the numbers away from their baselines, in the policy's order, and nothing else. */
    @Test
    public void theSettingIsNamedByWhatHasMoved() {
        assertTrue(policy.shield.dueLate.set(6));
        assertEquals("shield.dueLate:6", policy.setting());
        assertTrue(policy.shield.holdCharge.set(86));
        assertEquals("shield.holdCharge:86,shield.dueLate:6", policy.setting());
        assertTrue((float) policy.shield.holdCharge.real() == 0.86f);
        policy.shield.dueLate.reset();
        assertEquals("shield.holdCharge:86", policy.setting());
        policy.tuned.forEach(CombatPolicy.Tuned::reset);
        assertEquals(CombatPolicy.BASE, policy.setting());
        assertEquals(policy.shield.dueDist, policy.tuned("shield.dueDist"));
        assertEquals(null, policy.tuned("shield.nothing"));
    }

    @Test
    public void aMovedValueReachesTheBlockDecision() {
        assertTrue(dueLate.set(dueLate.base + 1));
        assertTrue("the shield still decides on the value it was built with", shield.swingDue(true, justPast, NEAR));
        assertFalse(shield.swingDue(true, justPast + 1, NEAR));

        assertTrue(dueLate.set(dueLate.base - 1));
        assertFalse("the shield still decides on the value it was built with", shield.swingDue(true, justPast - 1, NEAR));
    }

    @Test
    public void resetPutsTheBaselineDecisionBack() {
        assertTrue(dueLate.set(dueLate.max));
        assertTrue(shield.swingDue(true, justPast, NEAR));
        dueLate.reset();
        assertEquals(dueLate.base, dueLate.get());
        assertFalse(shield.swingDue(true, justPast, NEAR));
    }

    @Test
    public void aValueOutsideTheBoundsChangesNothing() {
        assertFalse(dueLate.set(dueLate.max + 1));
        assertFalse(dueLate.set(dueLate.min - 1));
        assertEquals(dueLate.base, dueLate.get());
        assertFalse(shield.swingDue(true, justPast, NEAR));

        assertTrue(dueLate.set(dueLate.min));
        assertFalse(dueLate.set(Integer.MAX_VALUE));
        assertEquals(dueLate.min, dueLate.get());
    }

    @Test
    public void theRestOfTheDueTestIsUntouched() {
        assertTrue(dueLate.set(dueLate.max));
        assertFalse("only a sword's swing is waited for", shield.swingDue(false, justPast, NEAR));
        assertFalse("out of its reach", shield.swingDue(true, justPast, policy.shield.dueDist.real()));
        assertFalse("too soon after its last swing", shield.swingDue(true, policy.shield.defaultGap - policy.shield.dueEarly.get() - 1, NEAR));
        assertTrue(policy.shield.dueDist.set(60));
        assertTrue("its reach moved out", shield.swingDue(true, justPast, 4.5));
        policy.shield.dueDist.reset();
        assertTrue(policy.shield.dueEarly.set(9));
        assertTrue("and how soon", shield.swingDue(true, policy.shield.defaultGap - 7, NEAR));
        policy.shield.dueEarly.reset();
        // a gap learned in the fight replaces the assumed one, and the window moves with it
        shield.swingGap = 20;
        assertTrue(shield.swingDue(true, 20 + dueLate.max - 1, NEAR));
        assertFalse(shield.swingDue(true, 20 + dueLate.max, NEAR));
    }
}
