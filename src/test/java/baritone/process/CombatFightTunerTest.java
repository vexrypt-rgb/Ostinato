package baritone.process;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The numbers a fight is fought whole under, and the learner that moves them: what it tries and when, what it takes
 * to keep a value and to drop one, and what it leaves alone. The fights here are made up.
 */
public class CombatFightTunerTest {

    private static final String CTX = "sword+shield/player", DOWN = "heal.eatNear:7", BASE = CombatPolicy.BASE;
    private static final int WARMUP = CombatFightTuner.Rules.WARMUP, LOOK = CombatFightTuner.Rules.LOOK;

    private final CombatPolicy policy = new CombatPolicy();
    private final List<String> log = new ArrayList<>();
    private final Map<String, Integer> fought = new HashMap<>();
    private CombatFightTuner tuner = new CombatFightTuner(policy.whole, log::add, new Random(1));

    /** The stance the next fight there is fought under, set as the process sets it. */
    private String stance(String context) {
        for (int i = 0; i < policy.whole.size(); i++) assertTrue(policy.whole.get(i).set(tuner.value(context, i)));
        return policy.stance();
    }

    private static CombatOutcome.Fight fight(String context, String stance, String carried, boolean death, double score, int spent) {
        CombatOutcome.Fight f = new CombatOutcome.Fight();
        f.context = context;
        f.stance = stance;
        f.carried = carried;
        f.end = death ? "death" : "win";
        f.wall = f.active = 400;
        f.foes = Math.max(score, 0);
        f.mine = Math.max(-score, 0);
        f.spent = spent;
        return f;
    }

    /** How many fights have been fought under the stance before this one. */
    private int nth(String stance) {
        return fought.merge(stance, 1, Integer::sum) - 1;
    }

    /** Fights with apples carried, each made by {@code world} from the stance it is fought under, until {@code verdicts} more verdicts are in. */
    private void until(int verdicts, int most, Function<String, CombatOutcome.Fight> world) {
        int want = log.size() + verdicts;
        for (int i = 0; i < most && log.size() < want; i++) tuner.fight(world.apply(stance(CTX)));
        assertEquals("verdicts", want, log.size());
    }

    /** A fight under the stance worth {@code base} for the baseline and {@code moved} for anything else, a tenth either way in turn. */
    private CombatOutcome.Fight worth(String stance, double base, double moved) {
        return fight(CTX, stance, "apple", false, (stance.equals(BASE) ? base : moved) + (nth(stance) % 2 == 0 ? 0.1 : -0.1), 1);
    }

    private void warm() {
        tuner.on(true);
        for (int i = 0; i < WARMUP; i++) tuner.fight(fight(CTX, stance(CTX), "apple", false, 0.5, 0));
    }

    private String last() {
        return log.get(log.size() - 1);
    }

    @Test
    public void theNumbersAFightIsFoughtWholeUnder() {
        String[] names = {"heal.eatNear", "heal.eatFar", "heal.eatSafe", "heal.keep", "pots.healAt", "totems.wantAt", "totems.handAt", "pearls.keep"};
        int[] bases = {9, 12, 11, 0, 9, 8, 4, 0}; // the lines the fights were fought with before they could move
        assertEquals(names.length, policy.whole.size());
        for (int i = 0; i < names.length; i++) {
            CombatPolicy.Tuned t = policy.whole.get(i);
            assertEquals(names[i], t.name);
            assertEquals(names[i], bases[i], t.get());
            assertEquals(bases[i], t.base);
            assertTrue(t.whole && t.min <= t.base && t.max > t.base && (t.base - t.min) % t.step == 0 && (t.max - t.base) % t.step == 0);
            assertFalse("not one of the numbers moved between windows", policy.tuned.contains(t));
            assertTrue(policy.tuned(names[i]) == t);
        }
        assertEquals(BASE, policy.stance());
        policy.heal.eatNear.set(7);
        policy.totems.handAt.set(2);
        assertEquals("heal.eatNear:7,totems.handAt:2", policy.stance());
        assertEquals("a window's setting does not name them", BASE, policy.setting());
    }

    @Test
    public void offItDoesNothing() {
        for (int i = 0; i < 200; i++) tuner.fight(fight(CTX, stance(CTX), "apple", i % 2 == 0, -1, 3));
        assertEquals(BASE, stance(CTX));
        assertTrue(log.isEmpty() && tuner.save().isEmpty() && tuner.report().isEmpty());
        assertFalse(tuner.dirty());
    }

    @Test
    public void theFirstTrialWaitsForTheWarmUpAndStepsDown() {
        tuner.on(true);
        for (int i = 0; i < WARMUP; i++) {
            assertEquals("fight " + i, BASE, stance(CTX));
            tuner.fight(fight(CTX, BASE, "apple", false, 0.5, 0));
        }
        boolean tried = false;
        for (int i = 0; i < 8; i++) {
            String st = stance(CTX);
            assertEquals("asking changes nothing", st, stance(CTX));
            assertTrue(st, st.equals(BASE) || st.equals(DOWN));
            tried |= st.equals(DOWN);
            tuner.fight(fight(CTX, st, "apple", false, 0.5, 0));
        }
        assertTrue(tried);
        assertEquals(9, tuner.trusted(CTX, 0));
    }

    @Test
    public void theTurnsAreLevelAndInNoFixedOrder() {
        warm();
        int base = 0, down = 0;
        boolean twice = false;
        String before = "";
        for (int i = 0; i < 2 * LOOK - 2; i++) {
            String st = stance(CTX);
            if (st.equals(BASE)) base++;
            else down++;
            assertTrue(Math.abs(base - down) <= 1);
            twice |= st.equals(before);
            before = st;
            tuner.fight(fight(CTX, st, "apple", false, 0.5, 0));
        }
        assertTrue("a strict alternation could fall into step with the order the foes come in", twice);
        assertTrue(log.isEmpty());
    }

    @Test
    public void aNumberIsOnlyTriedWithWhatItIsAboutCarried() {
        tuner.on(true);
        for (int i = 0; i < 60; i++) {
            assertEquals(BASE, stance(CTX));
            tuner.fight(fight(CTX, BASE, "", false, 0.5, 0));
        }
        tuner.fight(fight(CTX, BASE, "totem", false, 0.5, 0)); // the trial was due: it opens with the first number there is a use for
        boolean tried = false;
        for (int i = 0; i < 8; i++) {
            String st = stance(CTX);
            assertTrue(st, st.equals(BASE) || st.equals("totems.wantAt:6"));
            tried |= !st.equals(BASE);
            tuner.fight(fight(CTX, st, "totem", false, 0.5, 0));
        }
        assertTrue(tried);
    }

    @Test
    public void aValueThatScoresMoreIsKeptButNotEarly() {
        warm();
        until(1, 1000, st -> worth(st, 0.3, 0.6));
        assertTrue(last(), last().startsWith("acs-whole ctx=" + CTX + " heal.eatNear trusted=9 candidate=7 verdict=keep why=scores-more stance=base "));
        int side = LOOK << CombatFightTuner.Rules.KEEP_LOOK;
        assertTrue(last(), last().contains(" nT=" + side + " nC=" + side + " "));
        assertEquals(7, tuner.trusted(CTX, 0));
        assertTrue(tuner.urgent());
        boolean on = false; // and the next step the same way is tried
        for (int i = 0; i < 8; i++) {
            String st = stance(CTX);
            assertTrue(st, st.equals(DOWN) || st.equals("heal.eatNear:5"));
            on |= st.equals("heal.eatNear:5");
            tuner.fight(fight(CTX, st, "apple", false, 0.5, 0));
        }
        assertTrue(on);
    }

    @Test
    public void aValueThatDiesLessIsKept() {
        warm();
        // the same score either way: only the deaths tell them apart
        until(1, 1000, st -> fight(CTX, st, "apple", st.equals(BASE) && nth(st) % 3 == 0, 0.5, 1));
        assertTrue(last(), last().contains(" verdict=keep why=dies-less "));
        assertEquals(7, tuner.trusted(CTX, 0));
    }

    @Test
    public void aValueThatDiesMoreIsDroppedAtTheFirstLook() {
        warm();
        until(1, 1000, st -> fight(CTX, st, "apple", !st.equals(BASE) && nth(st) % 3 == 0, 0.5, 1));
        assertTrue(last(), last().contains(" candidate=7 verdict=drop why=dies-more ") && last().contains(" look=1 nT=" + LOOK + " nC=" + LOOK + " "));
        assertEquals(9, tuner.trusted(CTX, 0));
        until(1, 1000, st -> fight(CTX, st, "apple", !st.equals(BASE) && nth(st) % 3 == 0, 0.5, 1)); // then the other way
        assertTrue(last(), last().contains(" heal.eatNear trusted=9 candidate=11 verdict=drop "));
    }

    @Test
    public void aValueThatScoresLessIsDroppedAtTheFirstLook() {
        warm();
        until(1, 1000, st -> worth(st, 0.6, 0.3));
        assertTrue(last(), last().contains(" verdict=drop why=scores-less ") && last().contains(" look=1 "));
        assertEquals(9, tuner.trusted(CTX, 0));
    }

    @Test
    public void aValueThatScoresMoreAndDiesMoreIsNotKept() {
        warm();
        until(1, 3000, st -> fight(CTX, st, "apple", !st.equals(BASE) && nth(st) % 3 == 0, (st.equals(BASE) ? 0.3 : 0.6) + (nth(st + "#") % 2 == 0 ? 0.1 : -0.1), 1));
        assertTrue(last(), last().contains(" verdict=drop why=dies-more "));
    }

    @Test
    public void aValueNoDifferentIsGivenUp() {
        warm();
        until(1, 1000, st -> worth(st, 0.5, 0.49));
        assertTrue(last(), last().contains(" verdict=drop why=not-shown-better ") && last().contains(" look=2 "));
        assertEquals(9, tuner.trusted(CTX, 0));
    }

    @Test
    public void aValueThatSpendsFarMoreIsNotKept() {
        warm();
        until(1, 5000, st -> fight(CTX, st, "apple", false, (st.equals(BASE) ? 0.3 : 0.6) + (nth(st) % 2 == 0 ? 0.1 : -0.1), st.equals(BASE) ? 1 : 2));
        assertTrue(last(), last().contains(" verdict=drop why=spends-more ") && last().contains(" look=" + CombatFightTuner.Rules.LOOKS + " "));
        assertEquals(9, tuner.trusted(CTX, 0));
    }

    @Test
    public void aKeptValueGivesItsPlaceBackWhenTheOldOneIsAhead() {
        List<String> file = new ArrayList<>();
        file.add(CombatTuner.VERSION);
        file.add("fight=" + CTX + " kept=1 dropped=0 reverted=0 fruitless=0 idle=0 unused=0 rest=1 restOf=10 at=heal.eatNear knob=heal.eatNear:9:7:d-");
        assertEquals(1, tuner.load(file));
        tuner.on(true);
        assertEquals(DOWN, stance(CTX));
        until(1, 1000, st -> worth(st, 0.6, 0.3));
        assertTrue(last(), last().contains(" heal.eatNear trusted=7 candidate=9 verdict=revert why=trusted-scores-less stance=" + DOWN + " look=1 "));
        assertEquals(9, tuner.trusted(CTX, 0));
    }

    @Test
    public void aFightWithoutAUseForTheNumberIsNotCountedAndTheTrialIsPutAway() {
        warm();
        for (int i = 0; i < 6; i++) tuner.fight(worth(stance(CTX), 0.5, 0.5));
        AcsStatus st = new AcsStatus();
        tuner.status(CTX, st);
        assertTrue(st.wholeTrial);
        int had = st.wholeFought;
        for (int i = 0; i < CombatFightTuner.Rules.SHELVE - 1; i++) tuner.fight(fight(CTX, stance(CTX), "", true, -1, 0)); // the apples are gone
        tuner.status(CTX, st);
        assertEquals(had, st.wholeFought);
        assertTrue(log.isEmpty());
        tuner.fight(fight(CTX, stance(CTX), "", true, -1, 0));
        assertTrue(last(), last().contains(" verdict=stop why=not-carried "));
        assertEquals(BASE, stance(CTX));
        assertEquals(9, tuner.trusted(CTX, 0));
    }

    @Test
    public void aFightThatCannotBeReadOrWasFoughtUnderSomethingElseIsNotCounted() {
        warm();
        AcsStatus before = new AcsStatus(), after = new AcsStatus();
        for (int i = 0; i < 6; i++) tuner.fight(worth(stance(CTX), 0.5, 0.5));
        tuner.status(CTX, before);
        for (int i = 0; i < 50; i++) {
            CombatOutcome.Fight f = fight(CTX, stance(CTX), "apple", true, -1, 0);
            f.unsynced = i % 2 == 0;
            f.mixed = i % 2 == 1;
            tuner.fight(f);
            tuner.fight(fight(CTX, "heal.eatNear:13", "apple", true, -1, 0)); // set by hand, say
        }
        tuner.status(CTX, after);
        assertEquals(before.wholeFought, after.wholeFought);
        assertTrue(log.isEmpty());
    }

    @Test
    public void everyKitFoeAndLagBandIsLearnedApart() {
        warm();
        until(1, 1000, st -> worth(st, 0.3, 0.6));
        assertEquals(7, tuner.trusted(CTX, 0));
        for (String other : new String[]{CTX + "@2", "sword+shield/mob", "axe/player"}) {
            assertEquals(other, 9, tuner.trusted(other, 0));
            assertEquals(other, BASE, stance(other));
        }
        String far = CTX + "@2";
        for (int i = 0; i < WARMUP; i++) tuner.fight(fight(far, stance(far), "apple", false, 0.5, 0));
        assertTrue(stance(CTX).contains("heal.eatNear:")); // and a fight there does not disturb the trial here
        assertEquals(2, tuner.save().size());
    }

    @Test
    public void theCardIsToldWhatIsOnTrial() {
        tuner.on(true);
        AcsStatus st = new AcsStatus();
        st.phase = AcsStatus.Phase.WATCHING;
        tuner.status(CTX, st);
        assertEquals("per fight: next trial in " + WARMUP + " fights", st.whole());
        warm();
        for (int i = 0; i < 6; i++) tuner.fight(worth(stance(CTX), 0.5, 0.5));
        st = new AcsStatus();
        st.phase = AcsStatus.Phase.TRIAL;
        tuner.status(CTX, st);
        assertEquals("per fight: eatNear 7 vs 9  3/" + LOOK, st.whole());
        st.phase = AcsStatus.Phase.OFF;
        assertEquals("", st.whole());
        assertTrue(tuner.report().get(0), tuner.report().get(0).startsWith(CTX + ": per fight trusted base, 0 kept, 0 dropped, 0 taken back; trying heal.eatNear 7 against 9: "));
    }

    @Test
    public void whatWasLearnedIsReadBackWithTheTrialUnderWay() {
        warm();
        until(1, 1000, st -> worth(st, 0.3, 0.6));
        for (int i = 0; i < 9; i++) tuner.fight(worth(stance(CTX), 0.3, 0.6));
        List<String> file = new ArrayList<>();
        file.add(CombatTuner.VERSION);
        file.add("ctx=sword+shield/player cost=1.0 gain=1.0 kept=0 dropped=0 reverted=0 fruitless=0 idle=0 rest=10 restOf=10 at=shield.dueLate");
        List<String> saved = tuner.save();
        file.addAll(saved);
        assertTrue(saved.get(0), saved.get(0).startsWith("fight=" + CTX + " kept=1 ") && saved.get(0).contains(" knob=heal.eatNear:9:7:") && saved.get(0).contains(" trial=5:0 t="));

        CombatFightTuner again = new CombatFightTuner(policy.whole, log::add, new Random(2));
        assertEquals(1, again.load(file));
        assertEquals(saved, again.save());
        assertEquals(7, again.trusted(CTX, 0));
        assertFalse(again.dirty());
        assertEquals("nothing read is in force while it is off", 9, again.value(CTX, 0));
        // the other learner reads the same file and takes none of these lines for its own
        CombatTuner windows = new CombatTuner(policy.tuned, s -> {});
        windows.load(file);
        for (String line : windows.save()) assertFalse(line, line.startsWith("fight="));

        tuner = again;
        tuner.on(true);
        until(1, 1000, st -> worth(st, 0.3, 0.6));
        assertTrue(last(), last().contains(" heal.eatNear trusted=7 candidate=5 "));
    }

    @Test
    public void aLineThatCannotStandIsLeftOut() {
        String head = "fight=" + CTX + " kept=1 dropped=0 reverted=0 fruitless=0 idle=0 unused=0 rest=1 restOf=10 at=heal.eatNear ";
        String[] bad = {
                head + "knob=heal.eatNear:9:3:-", // under the bounds
                head + "knob=heal.eatNear:9:8:-", // off the steps
                head + "knob=heal.eatNear:10:8:-", // the baseline has changed since
                head + "knob=heal.gone:9:7:-", // trusted away from the baseline of a number that is gone
                head + "knob=heal.eatNear:9:x:-",
        };
        for (String line : bad) {
            List<String> file = new ArrayList<>();
            file.add(CombatTuner.VERSION);
            file.add(line);
            assertEquals(line, 0, tuner.load(file));
            assertEquals(9, tuner.trusted(CTX, 0));
        }
        List<String> file = new ArrayList<>();
        file.add("acs 0");
        file.add(head + "knob=heal.eatNear:9:7:-");
        assertEquals("another version's file is not read", 0, tuner.load(file));
        file.set(0, CombatTuner.VERSION);
        file.set(1, head + "knob=heal.eatNear:9:7:- knob=heal.gone:9:9:- trial=1:0 t=0,0,0,0,0,0,0 c=0,0,0,0,0,0,0");
        assertEquals("a trial the rules could not have begun is left out, the line stands", 1, tuner.load(file));
        assertEquals(7, tuner.trusted(CTX, 0));
        assertFalse(tuner.save().get(0).contains("trial="));
    }

    @Test
    public void resetForgetsEverything() {
        warm();
        until(1, 1000, st -> worth(st, 0.3, 0.6));
        tuner.clear();
        assertEquals(9, tuner.trusted(CTX, 0));
        assertEquals(BASE, stance(CTX));
        assertTrue(tuner.save().isEmpty() && tuner.urgent());
        tuner.saved();
        assertFalse(tuner.dirty() || tuner.urgent());
    }

    @Test
    public void theDeathShareIsComparedAsTwoProportions() {
        CombatFightTuner.Arm c = new CombatFightTuner.Arm(), t = new CombatFightTuner.Arm();
        assertEquals(0, CombatFightTuner.zDies(c, t), 0);
        for (int i = 0; i < 100; i++) {
            c.add(fight(CTX, BASE, "", i < 10, 0, 0));
            t.add(fight(CTX, BASE, "", i < 30, 0, 0));
        }
        assertEquals(-0.2 / Math.sqrt(0.2 * 0.8 * 0.02), CombatFightTuner.zDies(c, t), 1e-9);
        assertEquals(0, CombatFightTuner.zDies(t, t), 0);
        CombatFightTuner.Arm none = new CombatFightTuner.Arm();
        none.add(fight(CTX, BASE, "", false, 0, 0));
        assertEquals("no death on either side says nothing", 0, CombatFightTuner.zDies(none, none), 0);
        assertEquals(c.write(), CombatFightTuner.Arm.read(c.write()).write());
    }

    /** What the first trial of a fresh learner comes to, over made-up fights: [kept, dropped, fights fought]. */
    private static int[] trials(int runs, double diesBase, double diesMoved, double gainMoved) {
        int[] out = new int[3];
        for (int run = 0; run < runs; run++) {
            CombatPolicy policy = new CombatPolicy();
            List<String> log = new ArrayList<>();
            Random world = new Random(1000 + run);
            CombatFightTuner tuner = new CombatFightTuner(policy.whole, log::add, new Random(run));
            tuner.on(true);
            while (log.isEmpty()) {
                for (int i = 0; i < policy.whole.size(); i++) policy.whole.get(i).set(tuner.value(CTX, i));
                String st = policy.stance();
                boolean moved = !st.equals(BASE);
                // a fight as the bench's are: lost outright at times, and otherwise won by a margin that scatters
                boolean death = world.nextDouble() < (moved ? diesMoved : diesBase);
                double score = death ? -0.7 + 0.25 * world.nextGaussian() : 0.45 + (moved ? gainMoved : 0) + 0.3 * world.nextGaussian();
                tuner.fight(fight(CTX, st, "apple", death, score, 1));
                out[2]++;
            }
            if (log.get(0).contains(" verdict=keep ")) out[0]++;
            else out[1]++;
        }
        return out;
    }

    @Test
    public void aValueNoBetterIsRarelyKept() {
        int runs = 400;
        int[] easy = trials(runs, 0.1, 0.1, 0), even = trials(runs, 0.3, 0.3, 0);
        assertTrue("kept " + easy[0] + " of " + runs, easy[0] <= runs * 3 / 100);
        assertTrue("kept " + even[0] + " of " + runs, even[0] <= runs * 3 / 100);
    }

    @Test
    public void aValueClearlyBetterIsMostlyKept() {
        int runs = 200;
        int[] safer = trials(runs, 0.3, 0.15, 0), stronger = trials(runs, 0.1, 0.1, 0.15);
        assertTrue("kept " + safer[0] + " of " + runs, safer[0] >= runs * 3 / 4);
        // a gain that is not in the deaths is kept less often: a value that happens to have died once more is not kept on its score
        assertTrue("kept " + stronger[0] + " of " + runs, stronger[0] >= runs / 2);
    }

    @Test
    public void aValueWorseIsNotKept() {
        int runs = 200;
        assertEquals(0, trials(runs, 0.15, 0.3, 0)[0]);
        // one that scores less can still happen to die less often for a while: no more than one trial in a hundred
        int kept = trials(runs, 0.1, 0.1, -0.15)[0];
        assertTrue("kept " + kept + " of " + runs, kept <= runs / 100);
    }

    @Test
    public void theRulesHangTogether() {
        assertEquals(CombatFightTuner.Rules.LOOKS - 1, CombatFightTuner.Rules.FUTILE.length);
        assertTrue(CombatFightTuner.Rules.KEEP_LOOK < CombatFightTuner.Rules.LOOKS);
        assertTrue(CombatFightTuner.Rules.Z_DROP < CombatFightTuner.Rules.Z_KEEP);
        for (double f : CombatFightTuner.Rules.FUTILE) assertTrue(f < CombatFightTuner.Rules.Z_DROP);
        assertNull(policy.tuned("heal.none"));
        assertNotNull(policy.tuned("shield.dueLate"));
    }
}
