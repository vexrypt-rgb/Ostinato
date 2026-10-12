package baritone.process;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** An engagement written down whole: how it ended, what each side lost, and which ones are judged for nothing. */
public class CombatFightScoreTest {

    private static final String KIT = "sword+shield", CTX = KIT + "/player", BASE = CombatPolicy.BASE;
    private static final int FOE = 7, OTHER = 8;
    private static final double NEAR = 2.5, D = 1.0e-6;

    private final List<String> lines = new ArrayList<>();
    private final List<CombatOutcome.Fight> fights = new ArrayList<>();
    private final CombatOutcome out = new CombatOutcome(lines::add, new CombatOutcome.Listener() {
        public void window(String context, String setting, CombatOutcome.Tally window, String end) {}

        public void fight(CombatOutcome.Fight fight) {
            fights.add(fight);
        }
    });

    private void tick(int foe, float foeHp, float myHp) {
        out.tick(foe, true, foeHp, 0, 10, myHp, NEAR, false, out.idle() ? KIT : null, BASE);
    }

    private CombatOutcome.Fight only() {
        assertEquals(1, fights.size());
        return fights.get(0);
    }

    private String line() {
        for (String l : lines) {
            if (l.startsWith("acs-fight ")) return l;
        }
        return "";
    }

    @Test
    public void aFightWonIsWorthWhatTheFoeLostLessWhatWeDid() {
        assertTrue(out.between());
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        assertFalse(out.between());
        tick(FOE, 8, 15);
        out.foeDied(FOE, NEAR);
        out.interrupt("lost");
        assertTrue(out.between());
        CombatOutcome.Fight f = only();
        assertEquals("win", f.end);
        assertFalse(f.death());
        assertEquals(1, f.foes, D);
        assertEquals(0.25, f.mine, D);
        assertEquals(0.75, f.score(), D);
        assertEquals(0.75, f.least, D);
        assertEquals(1, f.kills);
        assertEquals(2, f.wall); // from the first active tick
        assertEquals(CTX, f.context);
        assertEquals(BASE, f.stance);
        assertEquals("with nothing told, nothing is carried", "", f.carried);
        assertEquals(0, f.spent);
        assertFalse(f.unsynced || f.mixed);
        assertEquals("acs-fight ctx=sword+shield/player stance=base has=- end=win wall=2 n=2 score=0.750 foes=1.000 mine=0.250 least=0.75 kills=1 spent=0", line());
    }

    @Test
    public void aKillTheTickReadsIsAWinToo() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        tick(FOE, 0, 20);
        out.interrupt("lost");
        assertEquals("win", only().end);
        assertEquals(1, only().kills);
        assertEquals(1, only().score(), D);
    }

    @Test
    public void aFoeDeadFarOffStillEndsTheFightAsWon() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        tick(FOE, 6, 20);
        out.foeDied(FOE, 60); // the window gives no credit for it; the fight is over all the same
        out.interrupt("lost");
        assertEquals("win", only().end);
        assertEquals(1, only().foes, D);
    }

    @Test
    public void ourDeathCostsAllWeBeganWith() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        tick(FOE, 12, 9);
        tick(FOE, 12, 0);
        CombatOutcome.Fight f = only();
        assertTrue(f.death());
        assertEquals(0.4, f.foes, D);
        assertEquals(1, f.mine, D);
        assertEquals(-0.6, f.score(), D);
        assertEquals(0, f.least, D);
        assertTrue(out.between());
    }

    @Test
    public void aDeathNoTickShowedIsADeath() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        tick(FOE, 20, 5);
        out.died();
        assertTrue(only().death());
        assertEquals(1, only().mine, D);
        out.died(); // and is not told twice
        assertEquals(1, fights.size());
    }

    @Test
    public void aFightBegunHurtIsNotHeldAgainstIt() {
        tick(FOE, 20, 10);
        tick(FOE, 20, 10);
        tick(FOE, 20, 0);
        assertEquals(0.5, only().mine, D);
        fights.clear();
        tick(FOE, 20, 10);
        tick(FOE, 20, 10);
        out.foeDied(FOE, NEAR);
        out.interrupt("lost");
        assertEquals(0, only().mine, D);
        assertEquals(1, only().score(), D);
    }

    @Test
    public void whatIsHealedBackIsNotLostButTheLowestIsKept() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        tick(FOE, 20, 6);
        tick(FOE, 20, 18);
        out.interrupt("lost");
        CombatOutcome.Fight f = only();
        assertEquals("draw", f.end);
        assertEquals(0.1, f.mine, D);
        assertEquals(0.3, f.least, D);
        assertEquals(-0.1, f.score(), D);
    }

    @Test
    public void everyFoeCountsForWhatItLostWhileItWasOurs() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        tick(FOE, 5, 20);
        tick(OTHER, 12, 20); // came hurt: what it lost before is not ours
        tick(OTHER, 2, 20);
        tick(FOE, 5, 20); // back to the first, which lost nothing meanwhile
        tick(FOE, 1, 20);
        out.interrupt("lost");
        CombatOutcome.Fight f = only();
        assertEquals("draw", f.end);
        assertEquals(0.75 + 0.5 + 0.2, f.foes, D);
        assertEquals(0, f.kills);
    }

    @Test
    public void aWaveIsWorthAsManyBarsAsWereKilled() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        out.foeDied(FOE, NEAR);
        tick(OTHER, 20, 20);
        tick(OTHER, 20, 20);
        out.foeDied(OTHER, NEAR);
        out.interrupt("lost");
        assertEquals(2, only().kills);
        assertEquals(2, only().foes, D);
        assertEquals("win", only().end);
    }

    @Test
    public void aFightThatStopsWithTheFoeAliveIsADraw() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        out.foeDied(FOE, NEAR);
        tick(OTHER, 20, 20); // the next one was still standing when it stopped
        tick(OTHER, 15, 20);
        out.interrupt("lost");
        assertEquals("draw", only().end);
        assertEquals(1.25, only().foes, D);
    }

    @Test
    public void healthIsCountedInBarsOfWhoeversItIs() {
        out.stakes(40, 100, BASE, "apple+totem", 5);
        tick(FOE, 100, 40);
        out.stakes(40, 100, BASE, "apple+totem", 5);
        tick(FOE, 100, 40);
        out.stakes(40, 100, BASE, "apple+totem", 4);
        tick(FOE, 50, 30);
        out.interrupt("lost");
        CombatOutcome.Fight f = only();
        assertEquals(0.5, f.foes, D);
        assertEquals(0.25, f.mine, D);
        assertEquals("apple+totem", f.carried);
        assertEquals(1, f.spent);
        assertTrue(line(), line().contains(" has=apple+totem ") && line().endsWith(" spent=1"));
    }

    @Test
    public void whatADeathDropsWasNotSpent() {
        out.stakes(20, 20, BASE, "apple", 6);
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        out.stakes(20, 20, BASE, "apple", 4);
        tick(FOE, 20, 8);
        out.stakes(20, 20, BASE, "apple", 0); // dead, and the inventory is on the ground
        tick(FOE, 20, 0);
        assertEquals(2, only().spent);
    }

    @Test
    public void whatIsPickedUpIsNotSpentBackwards() {
        out.stakes(20, 20, BASE, "apple", 2);
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        out.stakes(20, 20, BASE, "apple", 9);
        tick(FOE, 20, 20);
        out.interrupt("lost");
        assertEquals(0, only().spent);
    }

    @Test
    public void aFightTheStanceChangesUnderIsMarked() {
        out.stakes(20, 20, BASE, "apple", 1);
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        out.stakes(20, 20, "heal.eatNear:7", "apple", 1);
        tick(FOE, 20, 20);
        out.interrupt("lost");
        assertTrue(only().mixed);
        assertEquals("the one it began under", BASE, only().stance);
        assertTrue(line(), line().endsWith(" mixed"));
    }

    @Test
    public void aFightTheKindOfFoeOrTheLagChangesUnderIsMarked() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        out.tick(OTHER, false, 20, 0, 10, 20, NEAR, false, null, BASE);
        out.interrupt("lost");
        assertTrue(only().mixed);
        assertEquals(CTX, only().context);

        fights.clear();
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        out.tick(FOE, true, 20, 0, 10, 20, NEAR, false, null, BASE, 2);
        out.interrupt("lost");
        assertTrue(only().mixed);
    }

    @Test
    public void aFightWithAWindowTheServerDidNotShowIsMarked() {
        tick(FOE, 20, 20);
        tick(FOE, 20, 20);
        for (int i = 0; i < CombatOutcome.Rules.UNSYNCED_FLASHES; i++) {
            out.tick(FOE, true, 20, 10, 10, 20, NEAR, true, null, BASE);
            out.tick(FOE, true, 20, 4, 10, 20, NEAR, false, null, BASE);
        }
        out.interrupt("lost");
        assertTrue(only().unsynced);
        assertTrue(line(), line().endsWith(" unsynced"));
    }

    @Test
    public void aFightThatNeverEndsIsWrittenDownAsItStands() {
        tick(FOE, 20, 20);
        for (int i = 0; i < CombatOutcome.Rules.LONGEST; i++) tick(FOE, 20, 20);
        assertEquals("long", only().end);
        assertEquals(CombatOutcome.Rules.LONGEST, only().wall);
        assertEquals(0, only().score(), D);
        assertTrue(out.between());
        tick(FOE, 20, 20); // and the next one begins where it stands
        assertFalse(out.between());
        out.interrupt("lost");
        assertEquals(2, fights.size());
        assertEquals("draw", fights.get(1).end);
    }

    @Test
    public void nothingIsToldBeforeAFightBegan() {
        tick(FOE, 20, 20); // the first tick on a foe is not a fight tick
        out.interrupt("lost");
        out.died();
        out.foeDied(FOE, NEAR);
        assertTrue(fights.isEmpty());
    }
}
