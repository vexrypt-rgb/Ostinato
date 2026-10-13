package baritone.process;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Which ticks of a fight count, whose damage is whose, and that nothing unwelcome is dropped on the way. */
public class CombatOutcomeTest {

    private static final String KIT = "sword+shield", CTX = KIT + "/player";
    private static final int FOE = 7;
    private static final String BASE = CombatPolicy.BASE, MOVED = "shield.dueLate:14";
    private static final double NEAR = 2.5, FAR = CombatOutcome.Rules.ACTIVE_DIST + 0.5, D = 1.0e-4;

    private final List<String> lines = new ArrayList<>();
    /** The windows' lines only: what is written of a whole engagement is CombatFightScoreTest's. */
    private final CombatOutcome out = new CombatOutcome(l -> {
        if (!l.startsWith("acs-fight ")) lines.add(l);
    });

    /** One fight tick against a player, near, with the kit and the baseline value. */
    private void tick(int foe, float foeHp, float myHp, double dist, boolean clicked) {
        out.tick(foe, true, foeHp, 0, 10, myHp, dist, clicked, out.idle() ? KIT : null, BASE);
    }

    private void quiet(int ticks) {
        for (int i = 0; i < ticks; i++) tick(FOE, 20, 20, NEAR, false);
    }

    private CombatOutcome.Tally closed() {
        out.interrupt("lost");
        return out.total(CTX, BASE);
    }

    @Test
    public void theFirstTickOnAFoeIsNotActive() {
        quiet(10);
        CombatOutcome.Tally t = closed();
        assertEquals(9, t.active);
        assertEquals(9, t.wall); // the engagement starts on the first active tick
        assertEquals(1, t.windows);
    }

    @Test
    public void aFoeOutOfRangeIsNotActiveButWhatWeTakeThereIsKept() {
        quiet(5);
        tick(FOE, 20, 17, FAR, false);
        tick(FOE, 14, 17, FAR, true);
        tick(FOE, 14, 15, NEAR, false);
        CombatOutcome.Tally t = closed();
        assertEquals(5, t.active);
        assertEquals(7, t.wall);
        assertEquals(0, t.drop, D);
        assertEquals(2, t.taken, D);
        assertEquals(3, t.out, D);
        assertEquals(5.0 / 5, t.takenRate(), D);
    }

    @Test
    public void aDropWithinTwoTicksOfOurClickIsOurs() {
        quiet(3);
        tick(FOE, 20, 20, NEAR, true);
        tick(FOE, 16, 20, NEAR, false); // one tick on
        tick(FOE, 16, 20, NEAR, true);
        tick(FOE, 16, 20, NEAR, false);
        tick(FOE, 13, 20, NEAR, false); // two ticks on
        tick(FOE, 13, 20, NEAR, true);
        tick(FOE, 13, 20, NEAR, false);
        tick(FOE, 13, 20, NEAR, false);
        tick(FOE, 11, 20, NEAR, false); // three ticks on: someone else's, or a fall
        CombatOutcome.Tally t = closed();
        assertEquals(7, t.dealt, D);
        assertEquals(9, t.drop, D);
        assertEquals(3, t.clicks);
        assertEquals(3, t.drops);
        assertEquals(7.0 / t.active, t.dealtRate(), D);
    }

    @Test
    public void healingIsNotNegativeDamage() {
        quiet(3);
        tick(FOE, 12, 9, NEAR, true);
        tick(FOE, 20, 20, NEAR, false);
        CombatOutcome.Tally t = closed();
        assertEquals(8, t.dealt, D);
        assertEquals(8, t.drop, D);
        assertEquals(11, t.taken, D);
    }

    @Test
    public void aNewTargetWithLessHealthIsNotAHit() {
        quiet(3);
        tick(FOE, 20, 20, NEAR, true);
        tick(FOE + 1, 6, 20, NEAR, false); // retargeted: its health was never 20
        tick(FOE + 1, 4, 20, NEAR, false); // and our click was at the other one
        CombatOutcome.Tally t = closed();
        assertEquals(0, t.dealt, D);
        assertEquals(2, t.drop, D);
        assertEquals(4, t.active);
        assertEquals(5, t.wall);
    }

    @Test
    public void anUnreadableHealthCountsNothingAndPoisonsNothing() {
        quiet(3);
        tick(FOE, Float.NaN, 20, NEAR, true);
        tick(FOE, 5, 20, NEAR, false); // no reading to compare with
        tick(FOE, 5, Float.NaN, NEAR, false);
        tick(FOE, 5, 3, NEAR, false);
        tick(FOE, 4, 3, NEAR, true);
        CombatOutcome.Tally t = closed();
        assertEquals(3, t.active);
        assertEquals(7, t.wall);
        assertEquals(1, t.dealt, D);
        assertEquals(1, t.drop, D);
        assertEquals(0, t.taken + t.out, D);
    }

    @Test
    public void aDeathWeSawEndsTheWindowAndIsKept() {
        quiet(4);
        tick(FOE, 20, 6, NEAR, false);
        tick(FOE, 20, 0, NEAR, false);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).endsWith("deaths=1 seq=0 end=death"));
        CombatOutcome.Tally t = out.total(CTX, BASE);
        assertEquals(1, t.deaths);
        assertEquals(20, t.taken, D);
        assertEquals(5, t.active);
        // lying dead is not fighting, and the respawn is not healing
        tick(FOE, 20, 0, NEAR, false);
        tick(FOE, 20, 20, NEAR, false);
        assertTrue(out.idle());
        tick(FOE, 20, 20, NEAR, false);
        assertFalse(out.idle());
        t = closed();
        assertEquals(6, t.active);
        assertEquals(1, t.deaths);
        assertEquals(20, t.taken + t.out, D);
    }

    @Test
    public void aDeathNoTickShowedCostsWhatWeHadLeft() {
        quiet(4);
        tick(FOE, 20, 6, NEAR, false);
        out.died(); // respawned at full health before the next tick
        tick(FOE, 20, 20, NEAR, false);
        CombatOutcome.Tally t = out.total(CTX, BASE);
        assertEquals(1, t.deaths);
        assertEquals(20, t.taken, D);
        assertTrue(lines.get(0), lines.get(0).endsWith("end=death"));
        out.died(); // told twice, counted once
        assertEquals(1, out.total(CTX, BASE).deaths);
    }

    @Test
    public void aDeathWeSawIsNotCountedAgainOnRespawn() {
        quiet(4);
        tick(FOE, 20, 0, NEAR, false);
        out.died();
        CombatOutcome.Tally t = out.total(CTX, BASE);
        assertEquals(1, t.deaths);
        assertEquals(20, t.taken, D);
    }

    @Test
    public void theKillingBlowIsCountedThoughNoTickReadsIt() {
        quiet(3);
        tick(FOE, 5, 20, NEAR, true);
        out.foeDied(FOE, NEAR); // dead at the top of the next tick, before the fight body
        CombatOutcome.Tally t = closed();
        assertEquals(1, t.kills);
        assertEquals(20, t.dealt, D);
        assertEquals(20, t.drop, D);
        assertEquals(3, t.active);
    }

    @Test
    public void aKillFarAwayOrLongAfterOurClickIsNotOurs() {
        quiet(3);
        tick(FOE, 5, 20, NEAR, true);
        tick(FOE, 5, 20, NEAR, false);
        tick(FOE, 5, 20, NEAR, false);
        out.foeDied(FOE, NEAR); // three ticks after the click
        tick(FOE + 1, 20, 20, NEAR, false);
        tick(FOE + 1, 5, 20, NEAR, true);
        out.foeDied(FOE + 1, FAR);
        out.foeDied(FOE + 2, NEAR); // not the one we were reading
        CombatOutcome.Tally t = closed();
        assertEquals(1, t.kills);
        assertEquals(15 + 15, t.dealt, D);
        assertEquals(15 + 5 + 15, t.drop, D);
    }

    @Test
    public void aKillOnTheTickAfterAFullWindowIsNotLost() {
        tick(FOE, 20, 20, NEAR, false);
        for (int i = 0; i < CombatOutcome.Rules.WINDOW - 1; i++) tick(FOE, 20, 20, NEAR, false);
        tick(FOE, 4, 20, NEAR, true);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).endsWith("end=full"));
        out.foeDied(FOE, NEAR);
        CombatOutcome.Tally t = closed();
        assertEquals(2, t.windows);
        assertEquals(1, t.kills);
        assertEquals(20, t.dealt, D);
        assertEquals(CombatOutcome.Rules.WINDOW, t.active);
    }

    @Test
    public void aFullWindowClosesAndTheNextOneOpens() {
        quiet(1 + CombatOutcome.Rules.WINDOW * 2 + 50);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("acs ctx=" + CTX + " set=" + BASE + " n=" + CombatOutcome.Rules.WINDOW + " wall=" + CombatOutcome.Rules.WINDOW + " "));
        CombatOutcome.Tally t = closed();
        assertEquals(3, lines.size());
        assertTrue(lines.get(2), lines.get(2).contains(" n=50 ") && lines.get(2).endsWith("end=lost"));
        assertEquals(3, t.windows);
        assertEquals(CombatOutcome.Rules.WINDOW * 2 + 50, t.active);
    }

    @Test
    public void aStoppedFightKeepsItsPartWindowAndStartsOver() {
        quiet(4);
        tick(FOE, 20, 15, NEAR, false);
        out.interrupt("lost");
        out.interrupt("lost"); // every idle tick says so again
        assertEquals(1, lines.size());
        assertTrue(out.idle());
        tick(FOE, 20, 9, NEAR, false); // whatever happened in between was not read
        tick(FOE, 20, 9, NEAR, false);
        CombatOutcome.Tally t = closed();
        assertEquals(2, t.windows);
        assertEquals(5, t.active);
        assertEquals(5, t.taken + t.out, D);
    }

    /** A window says where in its engagement it fell, and the count starts over with the next engagement. */
    @Test
    public void windowsAreNumberedWithinTheirEngagement() {
        assertTrue("before any fight the next window opens one", out.opening());
        quiet(1 + CombatOutcome.Rules.WINDOW - 1);
        assertTrue("the first window is still open", out.opening());
        quiet(1);
        assertFalse("the first window has closed", out.opening());
        quiet(CombatOutcome.Rules.WINDOW + 30);
        out.interrupt("lost");
        assertTrue(out.opening());
        quiet(40);
        assertTrue(out.opening());
        out.interrupt("lost");
        assertEquals(4, lines.size());
        String[] seq = {" seq=0 end=full", " seq=1 end=full", " seq=2 end=lost", " seq=0 end=lost"};
        for (int i = 0; i < 4; i++) assertTrue(lines.get(i), lines.get(i).endsWith(seq[i]));
    }

    /** A death ends the engagement like a stop does: the window after it opens the next one. */
    @Test
    public void aDeathStartsTheCountOver() {
        quiet(1 + CombatOutcome.Rules.WINDOW + 5);
        assertFalse(out.opening());
        tick(FOE, 20, 0, NEAR, false);
        assertTrue(out.opening());
        quiet(10);
        out.interrupt("lost");
        assertTrue(lines.get(1), lines.get(1).endsWith(" seq=1 end=death"));
        assertTrue(lines.get(2), lines.get(2).endsWith(" seq=0 end=lost"));
    }

    @Test
    public void aMovedValueClosesTheWindowUnderTheOldOne() {
        quiet(6);
        out.tick(FOE, true, 20, 0, 10, 20, NEAR, true, null, BASE);
        out.tick(FOE, true, 15, 0, 10, 20, NEAR, false, null, MOVED); // the hit was thrown under the old value
        out.tick(FOE, true, 15, 0, 10, 17, NEAR, false, null, MOVED);
        out.interrupt("lost");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains(" set=" + BASE + " ") && lines.get(0).endsWith("end=value"));
        assertTrue(lines.get(1), lines.get(1).contains(" set=" + MOVED + " "));
        CombatOutcome.Tally base = out.total(CTX, BASE), moved = out.total(CTX, MOVED);
        assertEquals(6, base.active);
        assertEquals(0, base.dealt + base.drop, D);
        assertEquals(1, moved.active);
        assertEquals(0, moved.dealt + moved.drop, D);
        assertEquals(3, moved.taken, D);
        assertEquals(2, out.report().size());
    }

    /** The value is a label on the tally and nothing more: it cannot move which ticks count. */
    @Test
    public void theSameFightCountsTheSameUnderAnyValue() {
        CombatOutcome[] runs = {new CombatOutcome(s -> {}), new CombatOutcome(s -> {})};
        String[] values = {"a:2", "a:20,b:3"};
        java.util.Random rng = new java.util.Random(11);
        float foeHp = 20, myHp = 20;
        int foe = FOE;
        for (int i = 0; i < 3000; i++) {
            if (rng.nextInt(40) == 0) foeHp -= 1 + rng.nextInt(6);
            if (rng.nextInt(50) == 0) myHp -= 1 + rng.nextInt(6);
            if (foeHp <= 0) {
                foe++;
                foeHp = 20;
            }
            if (myHp <= 0) myHp = 20;
            double dist = rng.nextInt(10) == 0 ? FAR : NEAR;
            boolean clicked = rng.nextInt(12) == 0;
            boolean stop = rng.nextInt(400) == 0;
            for (int r = 0; r < 2; r++) {
                if (stop) runs[r].interrupt("lost");
                else runs[r].tick(foe, true, foeHp, 0, 10, myHp, dist, clicked, runs[r].idle() ? KIT : null, values[r]);
            }
        }
        CombatOutcome.Tally a, b;
        runs[0].interrupt("lost");
        runs[1].interrupt("lost");
        assertNotNull(a = runs[0].total(CTX, values[0]));
        assertNotNull(b = runs[1].total(CTX, values[1]));
        assertTrue(a.active > 1000);
        assertEquals(a.active, b.active);
        assertEquals(a.wall, b.wall);
        assertEquals(a.windows, b.windows);
        assertEquals(a.dealt, b.dealt, 0);
        assertEquals(a.taken + a.out, b.taken + b.out, 0);
    }

    @Test
    public void theKitIsTheOneTheEngagementBeganWith() {
        out.tick(FOE, true, 20, 0, 10, 20, NEAR, false, KIT, BASE);
        out.tick(FOE, true, 20, 0, 10, 20, NEAR, false, KIT, BASE);
        // the shield broke: what follows is still the price of the kit we walked in with
        out.tick(FOE, true, 20, 0, 10, 12, NEAR, false, "sword", BASE);
        out.interrupt("lost");
        assertNull(out.total("sword/player", BASE));
        assertEquals(8, out.total(CTX, BASE).taken, D);
        // the next engagement reads it afresh
        out.tick(FOE, true, 20, 0, 10, 12, NEAR, false, "sword", BASE);
        out.tick(FOE, true, 20, 0, 10, 12, NEAR, false, "sword", BASE);
        out.interrupt("lost");
        assertEquals(1, out.total("sword/player", BASE).active);
    }

    @Test
    public void aMobIsKeptApartFromAPlayer() {
        quiet(4);
        out.tick(FOE + 1, false, 20, 0, 10, 20, NEAR, false, null, BASE);
        out.tick(FOE + 1, false, 20, 0, 10, 16, NEAR, false, null, BASE);
        out.interrupt("lost");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).endsWith("end=foe"));
        assertEquals(3, out.total(CTX, BASE).active);
        assertEquals(4, out.total(KIT + "/mob", BASE).taken, D);
    }

    @Test
    public void flashesWithNoDropMarkTheWindowUnsynced() {
        quiet(3);
        for (int i = 0; i < CombatOutcome.Rules.UNSYNCED_FLASHES; i++) {
            out.tick(FOE, true, 20, 10, 10, 20, NEAR, true, null, BASE);
            out.tick(FOE, true, 20, 4, 10, 20, NEAR, false, null, BASE);
        }
        out.interrupt("lost");
        assertTrue(lines.get(0), lines.get(0).endsWith("end=lost unsynced"));
        assertEquals(1, out.total(CTX, BASE).unsynced);
        assertTrue(out.report().get(0), out.report().get(0).endsWith("1 unsynced"));

        lines.clear();
        out.clear();
        quiet(3);
        for (int i = 0; i < CombatOutcome.Rules.UNSYNCED_FLASHES; i++) {
            out.tick(FOE, true, 20 - i, 10, 10, 20, NEAR, true, null, BASE);
            out.tick(FOE, true, 20 - i, 4, 10, 20, NEAR, false, null, BASE);
        }
        out.interrupt("lost");
        assertTrue(lines.get(0), lines.get(0).endsWith("end=lost"));
        assertEquals(0, out.total(CTX, BASE).unsynced);
    }

    /** A fight tick as {@link #tick}, in a lag band. */
    private void far(int lag, float foeHp, boolean clicked) {
        out.tick(FOE, true, foeHp, 0, 10, 20, NEAR, clicked, out.idle() ? KIT : null, BASE, lag);
    }

    @Test
    public void aFarServersAnswerIsOursForAsLongAsItTakes() {
        int lag = 3, late = CombatOutcome.Rules.CLICK_TICKS + lag;
        for (int i = 0; i < 3; i++) far(lag, 20, false);
        far(lag, 20, true);
        for (int i = 1; i < late; i++) far(lag, 20, false);
        far(lag, 16, false); // the round trip later than the nearest server would have shown it
        far(lag, 16, true);
        for (int i = 1; i <= late; i++) far(lag, 16, false);
        far(lag, 13, false); // one tick too late even so
        out.interrupt("lost");
        CombatOutcome.Tally t = out.total(CTX + "@3", BASE);
        assertNotNull("kept under the band it was fought in", t);
        assertNull(out.total(CTX, BASE));
        assertEquals(4, t.dealt, D);
        assertEquals(7, t.drop, D);

        out.clear();
        quiet(3);
        tick(FOE, 20, 20, NEAR, true);
        for (int i = 1; i < late; i++) tick(FOE, 20, 20, NEAR, false);
        tick(FOE, 16, 20, NEAR, false);
        assertEquals("with no lag the same drop is too late to be ours", 0, closed().dealt, D);
    }

    @Test
    public void aWindowTheLagChangesUnderIsClosedAsSuch() {
        for (int i = 0; i < 6; i++) far(0, 20, false);
        for (int i = 0; i < 4; i++) far(2, 20, false);
        out.interrupt("lost");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("acs ctx=" + CTX + " ") && lines.get(0).endsWith("end=lag"));
        assertTrue(lines.get(1), lines.get(1).startsWith("acs ctx=" + CTX + "@2 ") && lines.get(1).endsWith("end=lost"));
        assertEquals("nothing is dropped: every tick is in one of the two", 5 + 4, out.total(CTX, BASE).active + out.total(CTX + "@2", BASE).active);
    }

    @Test
    public void theBandIsAMarkOnTheNameAndNoLagHasNone() {
        assertEquals(CTX, CombatOutcome.context(KIT, true, 0));
        assertEquals(CTX + "@2", CombatOutcome.context(KIT, true, 2));
        assertEquals(CTX, CombatOutcome.plain(CTX + "@2"));
        assertEquals(CTX, CombatOutcome.plain(CTX));
    }

    @Test
    public void clearForgetsTheTotalsOnly() {
        quiet(5);
        out.interrupt("lost");
        assertEquals(1, out.report().size());
        out.clear();
        assertTrue(out.report().isEmpty());
        assertNull(out.total(CTX, BASE));
    }
}
