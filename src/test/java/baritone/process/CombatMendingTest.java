package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When bottles o' enchanting come out and when they are put away: the rules, with the policy's own numbers. */
public class CombatMendingTest {

    private final CombatPolicy.Mend p = new CombatPolicy().mend;

    @Test
    public void bottlesComeOutForAWornPieceWhenTheFoeGivesRoom() {
        assertTrue(CombatMending.Rule.start(p.startAt, p.startAt, p.room + 1, p.room, false));
        assertFalse("sound armour is left alone", CombatMending.Rule.start(p.startAt + 0.01, p.startAt, p.room + 1, p.room, false));
        assertFalse("not beside the foe", CombatMending.Rule.start(p.startAt, p.startAt, p.room, p.room, false));
        assertTrue("a foe that is eating gives the room itself", CombatMending.Rule.start(p.startAt, p.startAt, p.room, p.room, true));
    }

    /** Begun at one share and put away at a higher one, or every bottle would start and stop the mending. */
    @Test
    public void onceBegunItGoesOnUntilThePieceIsSound() {
        assertTrue(p.stopAt > p.startAt);
        assertTrue(CombatMending.Rule.go(p.startAt + 0.1, p.stopAt, p.room, p.close));
        assertFalse(CombatMending.Rule.go(p.stopAt, p.stopAt, p.room, p.close));
    }

    @Test
    public void theFoeComingBackEndsIt() {
        assertTrue(p.close < p.room);
        assertFalse(CombatMending.Rule.go(0.1, p.stopAt, p.close, p.close));
        assertTrue(CombatMending.Rule.go(0.1, p.stopAt, p.close + 0.1, p.close));
    }

    @Test
    public void groundIsGivenOnlyForAPieceAboutToBreak() {
        assertTrue(p.urgentAt < p.startAt);
        assertTrue(CombatMending.Rule.leave(p.urgentAt, p.urgentAt, 3, p.room, 0, p.leaveTicks, p.leaveRest, p.leaveRest));
        assertFalse(CombatMending.Rule.leave(p.startAt, p.urgentAt, 3, p.room, 0, p.leaveTicks, p.leaveRest, p.leaveRest));
        assertFalse("with the room already there, there is nothing to leave", CombatMending.Rule.leave(p.urgentAt, p.urgentAt, p.room + 1, p.room, 0, p.leaveTicks, p.leaveRest, p.leaveRest));
    }

    /** A foe as fast as we are is never shaken: the fight is not to be run from for good. */
    @Test
    public void aFoeThatCannotBeShakenIsFought() {
        assertFalse(CombatMending.Rule.leave(0, p.urgentAt, 3, p.room, p.leaveTicks, p.leaveTicks, p.leaveRest, p.leaveRest));
        assertFalse("and not left again at once", CombatMending.Rule.leave(0, p.urgentAt, 3, p.room, 0, p.leaveTicks, p.leaveRest - 1, p.leaveRest));
        assertTrue(CombatMending.Rule.leave(0, p.urgentAt, 3, p.room, 0, p.leaveTicks, p.leaveRest, p.leaveRest));
    }
}
