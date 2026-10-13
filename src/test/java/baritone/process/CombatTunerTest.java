package baritone.process;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When a value is tried, when it is kept, and above all when it is not. Every fight here is made up. Nothing in the
 * process switches the tuner on: the last test is the only place its value is ever handed to the policy.
 */
public class CombatTunerTest {

    private static final String CTX = "sword+shield/player", OTHER = "axe+shield/player";
    private static final int WINDOW = CombatOutcome.Rules.WINDOW, WARMUP = CombatTuner.Rules.WARMUP, LOOK = CombatTuner.Rules.LOOK;
    /** Windows into a trial, both sides counted, at which a candidate can first be kept. */
    private static final int KEEP = 2 * (LOOK << CombatTuner.Rules.KEEP_LOOK);
    /** Windows a trial lasts when it runs to its last look, both sides counted. */
    private static final int WHOLE = 2 * (LOOK << CombatTuner.Rules.LOOKS - 1);
    /** Windows a trial lasts when the two sides come out dead level at every look: it is given up as soon as level is not enough. */
    private static final int LEVEL = level();

    private static int level() {
        for (int i = 0; i < CombatTuner.Rules.FUTILE.length; i++) {
            if (CombatTuner.Rules.FUTILE[i] >= 0) return 2 * (LOOK << i);
        }
        return WHOLE;
    }

    /** What a value is worth in a made-up world: damage dealt and taken per active tick, and the share of fight ticks that are active. */
    private interface World {
        double[] worth(int value);
    }

    private static final World FLAT = v -> new double[]{0.10, 0.05, 0.95};

    private final CombatPolicy policy = new CombatPolicy();
    private final CombatPolicy.Tuned tuned = policy.shield.dueLate; // 10, inside 2..20, in steps of 4
    private final List<String> lines = new ArrayList<>();
    private final CombatTuner tuner = new CombatTuner(tuned, lines::add);
    private final Map<String, Integer> fought = new HashMap<>();
    /** How far over and under the world's rate of damage dealt the made-up windows go. */
    private double spread = 0.2;

    /** The setting's name with the one number at {@code value}, as the observer hands it over. */
    private String set(int value) {
        return value == tuned.base ? CombatPolicy.BASE : tuned.name + ":" + value;
    }

    private int value(String ctx) {
        return tuner.value(ctx, 0, true);
    }

    private int trusted(String ctx) {
        return tuner.trusted(ctx, 0);
    }

    /**
     * A window whose rates are the world's, a fifth over and a fifth under by turns (three tenths for damage taken),
     * so any even number of them has exactly the world's rate and a scatter that is known.
     */
    private CombatOutcome.Tally window(String ctx, World world, int value, int active) {
        double[] w = world.worth(value);
        int sign = fought.merge(ctx + value, 1, Integer::sum) % 2 == 0 ? -1 : 1;
        CombatOutcome.Tally t = new CombatOutcome.Tally();
        t.active = active;
        t.wall = (int) Math.round(active / w[2]);
        t.dealt = active * w[0] * (1 + spread * sign);
        t.taken = active * w[1] * (1 + 0.3 * sign);
        t.windows = 1;
        return t;
    }

    /** Fights one full window under whatever the tuner names, and says what that was. */
    private int fight(String ctx, World world) {
        int v = value(ctx);
        assertTrue("outside the bounds: " + v, v >= tuned.min && v <= tuned.max);
        tuner.window(ctx, set(v), window(ctx, world, v, WINDOW), "full");
        return v;
    }

    private void fight(String ctx, World world, int windows) {
        for (int i = 0; i < windows; i++) fight(ctx, world);
    }

    private int verdicts(String what) {
        return (int) lines.stream().filter(l -> l.contains("verdict=" + what)).count();
    }

    @Test
    public void offItNamesTheBaselineAndLearnsNothing() {
        assertFalse(tuner.on());
        World better = v -> new double[]{v < 10 ? 0.30 : 0.10, 0.05, 0.95};
        for (int i = 0; i < 2000; i++) {
            assertEquals(tuned.base, value(CTX));
            tuner.window(CTX, set(tuned.base), window(CTX, better, tuned.base, WINDOW), "full");
        }
        assertTrue(tuner.report().isEmpty());
        assertTrue(lines.isEmpty());
        assertEquals(tuned.base, trusted(CTX));
    }

    /** It answers with a value and nothing more: whoever asks decides whether the policy hears of it. */
    @Test
    public void itNeverWritesTheNumberItself() {
        tuner.on(true);
        World better = v -> new double[]{v < 10 ? 0.30 : 0.10, 0.05, 0.95};
        fight(CTX, better, 500);
        assertTrue(trusted(CTX) < tuned.base);
        assertEquals(tuned.base, tuned.get());
    }

    @Test
    public void nothingIsTriedBeforeTheWarmUp() {
        tuner.on(true);
        for (int i = 0; i < WARMUP; i++) assertEquals(tuned.base, fight(CTX, FLAT));
        assertEquals("the first window of a trial is the trusted value's", tuned.base, fight(CTX, FLAT));
        assertEquals(tuned.base - tuned.step, fight(CTX, FLAT));
    }

    @Test
    public void aTrialTakesTurnsSoBothSidesMeetTheSameMinutes() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP);
        int t = tuned.base, c = tuned.base - tuned.step;
        int[] want = {t, c, c, t, t, c, c, t};
        for (int i = 0; i < want.length; i++) assertEquals("window " + i, want[i], fight(CTX, FLAT));
    }

    @Test
    public void aWindowCutShortCountsForWhatItHoldsAndTheOtherSideCatchesUp() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP);
        int t = tuned.base, c = tuned.base - tuned.step;
        assertEquals(t, fight(CTX, FLAT));
        // the candidate's window ends in a death after 50 ticks: it is still 150 behind, and goes again
        assertEquals(c, value(CTX));
        tuner.window(CTX, set(c), window(CTX, FLAT, c, 50), "death");
        assertEquals(c, fight(CTX, FLAT));
        assertEquals("now it is 50 ahead", t, fight(CTX, FLAT));
    }

    @Test
    public void aValueThatDealsMoreAtNoCostIsKeptAndTheNextStepIsTried() {
        tuner.on(true);
        World world = v -> new double[]{v <= 6 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP - 1);
        assertEquals("a gain is not acted on early, however plain", tuned.base, trusted(CTX));
        assertTrue(lines.isEmpty());
        fight(CTX, world);
        assertEquals(6, trusted(CTX));
        assertEquals(1, verdicts("keep"));
        assertEquals(6, fight(CTX, world)); // trusted first
        assertEquals("the same way again", 2, fight(CTX, world));
        // 2 is worth what 6 is: it is not kept, and 6 stays, through the rest and the trials after it
        fight(CTX, world, 1500);
        assertEquals(6, trusted(CTX));
        assertEquals(1, verdicts("keep"));
    }

    @Test
    public void aValueNotShownBetterIsDroppedAndTheTrustedOneStays() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP + LEVEL - 1);
        assertEquals("level is enough at the early looks", 0, verdicts("drop"));
        fight(CTX, FLAT, 1 + LEVEL); // the step down, then the step up
        assertEquals(2, verdicts("drop"));
        assertEquals(0, verdicts("keep"));
        assertEquals(tuned.base, trusted(CTX));
        for (String l : lines) assertTrue(l, l.contains("why=not-shown-better"));
        // both ways dropped: it rests on the trusted value before either is tried again
        for (int i = 0; i < CombatTuner.Rules.REST; i++) assertEquals(tuned.base, fight(CTX, FLAT));
        assertEquals(tuned.base, fight(CTX, FLAT));
        assertEquals(tuned.base - tuned.step, fight(CTX, FLAT));
    }

    /** A candidate a little ahead, never by enough, is held to the last look and dropped there. */
    @Test
    public void aSmallLeadRunsToTheLastLookAndIsNotEnough() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.1036 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP + WHOLE - 1);
        assertTrue(lines.isEmpty());
        fight(CTX, world);
        assertEquals(1, verdicts("drop"));
        assertTrue(lines.get(0), lines.get(0).contains("why=not-shown-better") && lines.get(0).contains(" look=" + CombatTuner.Rules.LOOKS + " "));
        assertEquals(tuned.base, trusted(CTX));
    }

    /** A candidate that is behind both ways, not by enough to be called worse, is not fought to the end either. */
    @Test
    public void aCandidateFallingBehindIsGivenUpEarly() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.095 : 0.10, v < 10 ? 0.053 : 0.05, 0.95};
        fight(CTX, world, WARMUP + 2 * LOOK);
        assertTrue("half a standard error behind at the first look is no reason yet", lines.isEmpty());
        fight(CTX, world, 2 * LOOK);
        assertEquals(1, verdicts("drop"));
        assertTrue(lines.get(0), lines.get(0).contains("candidate=6 verdict=drop why=not-shown-better") && lines.get(0).contains(" look=2 "));
    }

    @Test
    public void aValueThatDealsLessIsDroppedAtTheFirstLook() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.04 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP + 2 * LOOK);
        assertEquals(1, verdicts("drop"));
        assertTrue(lines.get(0), lines.get(0).contains("why=deals-less"));
        assertEquals(tuned.base, trusted(CTX));
    }

    /** The guard: more damage dealt does not buy a place if more is taken for it. */
    @Test
    public void aValueThatDealsMoreButTakesMoreIsRefused() {
        tuner.on(true);
        World world = v -> new double[]{v != 10 ? 0.20 : 0.10, v != 10 ? 0.10 : 0.05, 0.95};
        fight(CTX, world, 1500);
        assertEquals(tuned.base, trusted(CTX));
        assertEquals(0, verdicts("keep"));
        assertTrue(verdicts("drop") >= 2);
        for (String l : lines) assertTrue(l, l.contains("why=takes-more"));
    }

    /** A little more taken, too little to be told from chance when a keep is first allowed, still bars the way once it is over the margin. */
    @Test
    public void overTheMarginItIsNotKeptEvenWhenTheExtraCostIsNotCertain() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.20 : 0.10, v < 10 ? 0.05 * 1.06 : 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP);
        assertTrue("nothing is certain yet", lines.isEmpty());
        assertEquals(tuned.base, trusted(CTX));
        fight(CTX, world, 1500);
        assertEquals(tuned.base, trusted(CTX));
        assertEquals(0, verdicts("keep"));
        assertTrue(lines.get(0), lines.get(0).contains("candidate=6 verdict=drop why=takes-more"));
    }

    @Test
    public void underTheMarginTheSameGainIsKept() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.20 : 0.10, v < 10 ? 0.05 * 1.03 : 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
    }

    /** The same for the other way to be kept: a value that fights half the ticks takes less in each and deals half as much. */
    @Test
    public void takingLessByFightingLessIsNotAGain() {
        tuner.on(true);
        World world = v -> v < 10 ? new double[]{0.10, 0.025, 0.50} : new double[]{0.10, 0.05, 0.95};
        fight(CTX, world, 1500);
        assertEquals(tuned.base, trusted(CTX));
        assertEquals(0, verdicts("keep"));
    }

    /** While it waits, a window fought under some other setting, one set by hand say, is not a window of the trusted one. */
    @Test
    public void whileItWaitsOnlyWindowsUnderTheTrustedSettingCount() {
        tuner.on(true);
        for (int i = 0; i < 3 * WARMUP; i++) tuner.window(CTX, set(14), window(CTX, FLAT, 14, WINDOW), "full");
        assertEquals(AcsStatus.Phase.WATCHING, tuner.status(CTX).phase);
        assertEquals(tuned.base, value(CTX));
        fight(CTX, FLAT, WARMUP);
        assertEquals(AcsStatus.Phase.TRIAL, tuner.status(CTX).phase);
    }

    /** A keep is news: the waits that grew while nothing was found start over. */
    @Test
    public void aKeepEndsTheLongerWaits() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP + 2 * LEVEL);
        assertTrue(tuner.save().get(1), tuner.save().get(1).contains(" fruitless=1 "));
        World better = v -> new double[]{v < 10 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, better, CombatTuner.Rules.REST + KEEP);
        assertEquals(6, trusted(CTX));
        assertTrue(tuner.save().get(1), tuner.save().get(1).contains(" fruitless=0 "));
    }

    /** A value that only thins out the ticks that count has dealt more per active tick and less per tick of fight. */
    @Test
    public void fewerActiveTicksIsNotAGain() {
        tuner.on(true);
        World world = v -> v < 10 ? new double[]{0.15, 0.05, 0.50} : new double[]{0.10, 0.05, 0.95};
        fight(CTX, world, 1500);
        assertEquals(tuned.base, trusted(CTX));
        assertEquals(0, verdicts("keep"));
    }

    @Test
    public void everyValueNamedIsInsideTheBoundsHoweverGoodTheEdgeLooks() {
        tuner.on(true);
        World down = v -> new double[]{0.30 - 0.01 * v, 0.05, 0.95}; // the lower the better, without end
        fight(CTX, down, 3000); // fight() checks every value it is handed
        assertEquals(tuned.min, trusted(CTX));
        World up = v -> new double[]{0.10 + 0.01 * v, 0.05, 0.95};
        fight(OTHER, up, 3000);
        assertEquals("the last whole step under the maximum", 18, trusted(OTHER));
    }

    @Test
    public void whatIsLearnedWithOneKitStaysWithIt() {
        tuner.on(true);
        World good = v -> new double[]{v <= 6 ? 0.16 : 0.10, 0.05, 0.95};
        for (int i = 0; i < WARMUP + KEEP; i++) {
            fight(CTX, good);
            fight(OTHER, FLAT);
        }
        assertEquals(6, trusted(CTX));
        assertEquals(tuned.base, trusted(OTHER));
        assertEquals(2, tuner.report().size());
    }

    @Test
    public void whatIsLearnedAtOneLatencyStaysWithIt() {
        String far = CombatOutcome.context("sword+shield", true, 2);
        tuner.on(true);
        World good = v -> new double[]{v <= 6 ? 0.16 : 0.10, 0.05, 0.95};
        for (int i = 0; i < WARMUP + KEEP; i++) {
            fight(CTX, good);
            fight(far, FLAT);
        }
        assertEquals(6, trusted(CTX));
        assertEquals("a band not shown better stays at the baseline", tuned.base, trusted(far));
        assertEquals(2, tuner.report().size());

        CombatTuner again = new CombatTuner(tuned, lines::add);
        assertEquals("both bands are read back", 2, again.load(tuner.save()));
        assertEquals(6, again.trusted(CTX, 0));
        assertEquals(tuned.base, again.trusted(far, 0));
    }

    @Test
    public void aBandHasTheSameNumbersToTuneAsItsKit() {
        CombatTuner all = new CombatTuner(policy.tuned, lines::add);
        all.on(true);
        for (String kit : new String[]{"sword+shield", "mace", "sword+mace+shield", "bare"}) {
            for (boolean player : new boolean[]{true, false}) {
                assertEquals(kit, all.status(CombatOutcome.context(kit, player)).knobs, all.status(CombatOutcome.context(kit, player, 3)).knobs);
            }
        }
    }

    @Test
    public void aWindowCutByAChangeOfLagIsNotJudgedEitherWay() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.30 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP);
        assertEquals(AcsStatus.Phase.TRIAL, tuner.status(CTX).phase);
        int candidate = tuner.status(CTX).candidate;
        assertTrue(candidate != tuned.base);
        for (int i = 0; i < 4 * WHOLE; i++) { // however good or bad they look
            tuner.window(CTX, set(candidate), window(CTX, world, candidate, WINDOW), "lag");
            tuner.window(CTX, set(tuned.base), window(CTX, v -> new double[]{0.01, 0.50, 0.95}, tuned.base, WINDOW), "lag");
        }
        assertTrue(lines.toString(), lines.isEmpty());
        assertEquals(tuned.base, trusted(CTX));
        assertEquals("the trial is where it was", candidate, tuner.status(CTX).candidate);
        assertEquals(0, tuner.status(CTX).fought, 0);
    }

    @Test
    public void aWindowWithTheFoesHealthNotShownEndsTheTrialAndNothingIsConcluded() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.30 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP + 5);
        CombatOutcome.Tally blind = window(CTX, world, tuned.base, WINDOW);
        blind.unsynced = 1;
        tuner.window(CTX, set(value(CTX)), blind, "full");
        assertEquals(1, verdicts("stop"));
        assertEquals(tuned.base, trusted(CTX));
        for (int i = 0; i < CombatTuner.Rules.REST; i++) assertEquals(tuned.base, fight(CTX, world));
        assertEquals(0, verdicts("keep"));
    }

    @Test
    public void aWindowFoughtUnderSomeOtherValueIsNeitherSides() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.30 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP + 1);
        for (int i = 0; i < 500; i++) tuner.window(CTX, set(13), window(CTX, world, 13, WINDOW), "full"); // set by hand, say
        assertTrue(lines.isEmpty());
        assertEquals(tuned.base, trusted(CTX));
        assertEquals("still the candidate's turn", tuned.base - tuned.step, value(CTX));
    }

    @Test
    public void switchedOffItIsTheBaselineAgainAndSwitchedOnItRemembers() {
        tuner.on(true);
        World world = v -> new double[]{v <= 6 ? 0.16 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
        tuner.on(false);
        assertEquals(tuned.base, value(CTX));
        int before = lines.size();
        tuner.window(CTX, set(tuned.base), window(CTX, world, tuned.base, WINDOW), "full");
        assertEquals(before, lines.size());
        tuner.on(true);
        for (int i = 0; i < WARMUP; i++) assertEquals("watched again before anything is tried", 6, fight(CTX, world));
        tuner.clear();
        assertEquals(tuned.base, value(CTX));
        assertEquals(tuned.base, trusted(CTX));
    }

    /** A kept value is tried again after the rest, and gives its place back when the foes have changed. */
    @Test
    public void aKeptValueCanLoseItsPlace() {
        tuner.on(true);
        World then = v -> new double[]{v <= 6 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, then, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
        World now = v -> new double[]{v >= 10 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, now, 2 * LEVEL + CombatTuner.Rules.REST + KEEP + 10);
        assertEquals(tuned.base, trusted(CTX));
    }

    /** Each step may be under the margin and the two together over it: the limit is the baseline's, not the last value's. */
    @Test
    public void stepsUnderTheMarginAreRefusedOnceTogetherTheyAreOverIt() {
        tuner.on(true);
        World world = v -> new double[]{v <= 2 ? 0.225 : v <= 6 ? 0.15 : 0.10, v <= 2 ? 0.05 * 1.04 * 1.04 : v <= 6 ? 0.05 * 1.04 : 0.05, 0.95};
        fight(CTX, world, 3000);
        assertEquals(6, trusted(CTX));
        assertEquals(1, verdicts("keep"));
    }

    @Test
    public void theSameSecondStepIsKeptWhenTheFirstCostNothing() {
        tuner.on(true);
        World world = v -> new double[]{v <= 2 ? 0.225 : v <= 6 ? 0.15 : 0.10, v <= 2 ? 0.05 * 1.04 : 0.05, 0.95};
        fight(CTX, world, WARMUP + 2 * KEEP);
        assertEquals(2, trusted(CTX));
    }

    /** The limit comes before the aim: a kept value shown to take too much goes back, whatever it deals. */
    @Test
    public void aKeptValueShownToTakeTooMuchGivesItsPlaceBack() {
        tuner.on(true);
        World then = v -> new double[]{v < 10 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, then, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
        World now = v -> new double[]{v < 10 ? 0.15 : 0.10, v < 10 ? 0.075 : 0.05, 0.95};
        fight(CTX, now, 2 * LEVEL + CombatTuner.Rules.REST + 2 * LOOK + 10);
        assertEquals(tuned.base, trusted(CTX));
        assertEquals(1, verdicts("revert"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("trusted=6 candidate=10 verdict=revert why=trusted-takes-more")));
    }

    @Test
    public void aKeptValueInsideTheMarginKeepsItsPlace() {
        tuner.on(true);
        World then = v -> new double[]{v < 10 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, then, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
        // it no longer deals more and takes 4% more than the baseline: inside the margin, and too little for the
        // baseline to be shown the better of the two
        World now = v -> new double[]{0.10, v < 10 ? 0.05 * 1.04 : 0.05, 0.95};
        fight(CTX, now, 3000);
        assertEquals(6, trusted(CTX));
        assertEquals(0, verdicts("revert"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("trusted=6 candidate=10 verdict=drop why=not-shown-better")));
    }

    /** Inside the margin a kept value is not taken back for what it costs; the baseline has to earn its place like any other. */
    @Test
    public void insideTheMarginTheBaselineComesBackOnlyByBeingShownBetter() {
        tuner.on(true);
        World then = v -> new double[]{v < 10 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, then, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
        World now = v -> new double[]{0.10, v < 10 ? 0.05 * 1.08 : 0.05, 0.95};
        fight(CTX, now, 2 * LEVEL + CombatTuner.Rules.REST + WHOLE - 1);
        assertEquals("8% more taken is no reason to take it back", 6, trusted(CTX));
        assertEquals(0, verdicts("revert"));
        fight(CTX, now, 1);
        assertEquals("at the last look the baseline is shown to take less, and deals no less", tuned.base, trusted(CTX));
        assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).contains("trusted=6 candidate=10 verdict=keep why=takes-less"));
    }

    /** A value that takes less and deals no less is worth its place as much as one that deals more. */
    @Test
    public void aValueThatTakesLessAtNoCostIsKept() {
        tuner.on(true);
        World world = v -> new double[]{0.10, v < 10 ? 0.025 : 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP - 1);
        assertTrue("a gain is not acted on early, however plain", lines.isEmpty());
        fight(CTX, world);
        assertEquals(6, trusted(CTX));
        assertTrue(lines.get(0), lines.get(0).contains("verdict=keep why=takes-less"));
        fight(CTX, world, 1500); // 2 takes what 6 does, and 10 takes more
        assertEquals(6, trusted(CTX));
        assertEquals(1, verdicts("keep"));
        assertEquals(0, verdicts("revert"));
    }

    /** The other guard: less damage taken does not buy a place if less is dealt for it. */
    @Test
    public void takingLessIsRefusedWhenItCostsDamageDealt() {
        tuner.on(true);
        spread = 0.4; // wide enough that 8% less dealt is not plain when a keep is first allowed
        World world = v -> new double[]{v < 10 ? 0.092 : 0.10, v < 10 ? 0.025 : 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP);
        assertTrue("less taken is plain by now, less dealt is not: only the margin stands in the way", lines.isEmpty());
        fight(CTX, world, 1500);
        assertEquals(tuned.base, trusted(CTX));
        assertEquals(0, verdicts("keep"));
        assertTrue(lines.get(0), lines.get(0).contains("candidate=6 verdict=drop why=deals-less"));
    }

    @Test
    public void takingLessIsKeptWhenWhatItCostsIsUnderTheMargin() {
        tuner.on(true);
        World world = v -> new double[]{v < 10 ? 0.097 : 0.10, v < 10 ? 0.025 : 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
    }

    /** Each step may give up a little damage dealt and the two together too much: that limit is the baseline's too. */
    @Test
    public void stepsThatEachCostALittleDamageDealtAreRefusedOnceTogetherTheyCostTooMuch() {
        tuner.on(true);
        World world = v -> new double[]{v <= 2 ? 0.10 * 0.97 * 0.97 : v <= 6 ? 0.10 * 0.97 : 0.10, v <= 2 ? 0.0125 : v <= 6 ? 0.025 : 0.05, 0.95};
        fight(CTX, world, 3000);
        assertEquals(6, trusted(CTX));
        assertEquals(1, verdicts("keep"));
    }

    /** A value kept for taking less gives its place back once it is shown to deal too little. */
    @Test
    public void aKeptValueShownToDealTooLittleGivesItsPlaceBack() {
        tuner.on(true);
        World then = v -> new double[]{0.10, v < 10 ? 0.025 : 0.05, 0.95};
        fight(CTX, then, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
        World now = v -> new double[]{v < 10 ? 0.06 : 0.10, v < 10 ? 0.025 : 0.05, 0.95};
        fight(CTX, now, 2 * LEVEL + CombatTuner.Rules.REST + 2 * LOOK + 10);
        assertEquals(tuned.base, trusted(CTX));
        assertTrue(lines.stream().anyMatch(l -> l.contains("trusted=6 candidate=10 verdict=revert why=trusted-deals-less")));
    }

    /** Asking costs fights: the baseline is left alone longer each time nothing is found against it. */
    @Test
    public void theBaselineIsLeftAloneLongerEachTimeNothingIsFound() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP);
        for (int round = 0; round <= CombatTuner.Rules.REST_DOUBLINGS + 1; round++) {
            int rest = CombatTuner.Rules.REST << Math.min(round, CombatTuner.Rules.REST_DOUBLINGS);
            fight(CTX, FLAT, 2 * LEVEL);
            assertEquals(2 * (round + 1), verdicts("drop"));
            for (int i = 0; i < rest; i++) assertEquals("round " + round + " window " + i, tuned.base, fight(CTX, FLAT));
            assertEquals(tuned.base, value(CTX)); // the trial's first window
        }
        fight(CTX, FLAT, 2 * LEVEL); // the rest has stopped growing, so the trials after the last one are over by now
        assertEquals(2 * (CombatTuner.Rules.REST_DOUBLINGS + 3), verdicts("drop"));
    }

    /** A value that is not the baseline is asked about every rest, for as long as it is trusted. */
    @Test
    public void aKeptValueIsTestedAgainEveryRest() {
        tuner.on(true);
        World world = v -> new double[]{v == 6 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, world, WARMUP + KEEP);
        assertEquals(6, trusted(CTX));
        fight(CTX, world, WHOLE); // the step on, to 2, is worth less and is dropped at its last look at the latest
        for (int round = 0; round < 4; round++) {
            int before = lines.size();
            fight(CTX, world, CombatTuner.Rules.REST + 2 * WHOLE);
            assertTrue("round " + round, lines.size() >= before + 2);
        }
        assertEquals(6, trusted(CTX));
    }

    /** The card shows what the tuner holds, and asking for it changes nothing. */
    @Test
    public void theStatusSaysWhatTheTunerIsDoingAndAskingChangesNothing() {
        AcsStatus st = tuner.status(CTX);
        assertEquals(AcsStatus.Phase.OFF, st.phase);
        assertEquals(tuned.base, st.value);
        assertEquals(tuned.name, st.name);
        assertArrayEquals(new int[]{2, 6, 10, 14, 18}, st.stops());
        assertEquals("off: baseline in force", st.caption());
        assertEquals(0f, st.progress(), 0);

        tuner.on(true);
        st = tuner.status(CTX);
        assertEquals(AcsStatus.Phase.WATCHING, st.phase);
        assertEquals(WARMUP, st.rest);
        assertEquals(WARMUP, st.restOf);
        assertEquals("next trial in " + WARMUP + " windows", st.caption());
        assertEquals("", tuner.status(null).context);
        assertTrue("asked about, not fought: nothing to remember", tuner.report().isEmpty());

        World world = v -> new double[]{v == 6 ? 0.15 : 0.10, 0.05, 0.95};
        fight(CTX, world, 4);
        st = tuner.status(CTX);
        assertEquals(WARMUP - 4, st.rest);
        assertEquals(4f / WARMUP, st.progress(), 1e-6);
        assertEquals(tuned.base, st.candidate);

        fight(CTX, world, WARMUP - 4);
        st = tuner.status(CTX);
        assertEquals(AcsStatus.Phase.TRIAL, st.phase);
        assertEquals(tuned.base, st.trusted);
        assertEquals(6, st.candidate);
        assertEquals(value(CTX), st.value);
        assertEquals(1, st.look);
        assertEquals(CombatTuner.Rules.LOOKS, st.looks);
        assertEquals(LOOK, st.needed, 0);
        assertEquals(0, st.fought, 0);
        assertEquals("", st.last);

        fight(CTX, world, 8); // four windows a side
        st = tuner.status(CTX);
        assertEquals(4, st.fought, 1e-9);
        assertEquals(0.4f, st.progress(), 1e-6);
        assertEquals(50, st.dealtPct, 1e-6);
        assertEquals(0, st.takenPct, 1e-6);
        assertTrue(st.zDealt > 0);
        assertEquals("look 1/" + CombatTuner.Rules.LOOKS + "  4.0 of " + LOOK + " windows", st.caption());
        assertEquals("0 kept  0 dropped", st.score());

        fight(CTX, world, KEEP - 8);
        st = tuner.status(CTX);
        assertEquals(6, st.trusted);
        assertEquals(1, st.kept);
        assertEquals("keep dueLate 6 against 10 (deals more)", st.last);
        assertEquals("dueLate", st.title());
        assertEquals(0, st.knob);
        assertEquals("the next step is on trial already", 2, st.candidate);

        tuner.on(false);
        st = tuner.status(CTX);
        assertEquals(AcsStatus.Phase.OFF, st.phase);
        assertEquals("off, the baseline is what is in force", tuned.base, st.value);
        assertEquals("what was learned is still held", 6, st.trusted);
        assertEquals(st.trusted, st.candidate);
    }

    /** Whatever the bounds, the stops are the baseline and whole steps from it. */
    @Test
    public void theStopsAreWholeStepsFromTheBaseline() {
        AcsStatus st = new AcsStatus();
        st.base = 13;
        st.min = 0;
        st.max = 20;
        st.step = 5;
        assertArrayEquals(new int[]{3, 8, 13, 18}, st.stops());
        st.step = 0;
        assertArrayEquals(new int[]{13}, st.stops());
        st.reverted = 2;
        assertEquals("0 kept  0 dropped  2 back", st.score());
        st.phase = AcsStatus.Phase.WATCHING;
        st.rest = 1;
        st.restOf = 400;
        assertEquals("nothing to tune for this kit", st.caption());
        st.knob = 0;
        assertEquals("next trial in 1 window", st.caption());
        st.phase = AcsStatus.Phase.TRIAL;
        st.fought = 30;
        st.needed = 20;
        assertEquals("never past the end", 1f, st.progress(), 0);
    }

    /** Windows as scattered as the recorded ones, drawn with a fixed seed. */
    private static CombatOutcome.Tally drawn(Random rnd, double dealt, double taken) {
        CombatOutcome.Tally t = new CombatOutcome.Tally();
        t.active = WINDOW;
        t.wall = WINDOW + 10;
        t.dealt = Math.max(0, WINDOW * dealt * (1 + 0.46 * rnd.nextGaussian()));
        t.taken = Math.max(0, WINDOW * taken * (1 + 0.85 * rnd.nextGaussian()));
        t.windows = 1;
        return t;
    }

    /** How many of {@code runs} fresh tuners, each given the step down once, kept it. */
    private int kept(Random rnd, int runs, double gain) {
        int kept = 0;
        for (int run = 0; run < runs; run++) {
            CombatTuner t = new CombatTuner(tuned, l -> {});
            t.on(true);
            for (int i = 0; i < WARMUP + WHOLE; i++) {
                int v = t.value(CTX, 0, true);
                if (v > tuned.base) break; // the step down is over
                t.window(CTX, set(v), drawn(rnd, v < tuned.base ? 0.10 * (1 + gain) : 0.10, 0.05), "full");
            }
            if (t.trusted(CTX, 0) < tuned.base) kept++;
        }
        return kept;
    }

    /** With no real difference next to nothing is kept; more than one trial in forty would mean the rule is wrong. */
    @Test
    public void withNoRealDifferenceAlmostNothingIsKept() {
        int runs = 2000, kept = kept(new Random(7), runs, 0);
        assertTrue("kept " + kept + " of " + runs, kept * 40 <= runs);
    }

    /** A quarter more damage dealt is found within a trial more often than not: a rule that keeps nothing is no tuner. */
    @Test
    public void aRealGainIsUsuallyFound() {
        int runs = 500, kept = kept(new Random(11), runs, 0.25);
        assertTrue("kept " + kept + " of " + runs, kept * 2 >= runs);
    }

    /**
     * The whole loop on a made-up fight, the test standing in for the process: the observer closes windows, the tuner
     * hears of them, and its value is handed to the policy the shield decides on. The foe swings every 13 ticks and
     * now and then waits 40; holding the shield through the wait costs clicks, so a shorter window deals more.
     */
    @Test
    public void inTheLoopAValueMovesOnlyBetweenWindowsAndReachesTheShield() {
        List<String> log = new ArrayList<>();
        CombatTuner loop = new CombatTuner(tuned, log::add);
        CombatOutcome out = new CombatOutcome(log::add, loop::window);
        CombatShield shield = new CombatShield(null, null, null, null, null, null, policy.shield, null);
        loop.on(true);
        Random rnd = new Random(3);
        String kit = "sword+shield";
        int since = 0, gap = 13, cool = 0, moves = 0, logged = 0;
        float foeHp = 20, myHp = 20;
        boolean landing = false;
        for (int tick = 0; tick < 400_000; tick++) {
            boolean closed = log.size() > logged;
            logged = log.size();
            int before = tuned.get();
            assertTrue(tuned.set(loop.value(CombatOutcome.context(out.idle() ? kit : out.kit(), true), 0, out.opening())));
            if (tuned.get() != before) {
                assertTrue("moved with a window open, tick " + tick, closed);
                moves++;
            }
            boolean block = shield.swingDue(true, since, 3.0);
            assertEquals(since >= 7 && since < 13 + tuned.get(), block);
            boolean click = !block && cool-- <= 0;
            if (landing && rnd.nextInt(4) > 0) foeHp -= 2;
            landing = click;
            if (click) cool = 4;
            if (since++ >= gap) {
                if (!block) myHp -= 3;
                since = 0;
                gap = rnd.nextInt(10) < 3 ? 40 : 13;
            }
            if (foeHp < 6) foeHp = 20;
            if (myHp < 6) myHp = 20;
            out.tick(7, true, foeHp, 0, 10, myHp, 3.0, click, out.idle() ? kit : null, policy.setting());
        }
        assertTrue(moves > 0);
        assertEquals(tuned.min, loop.trusted(kit + "/player", 0));
        for (String l : log) assertFalse("a window was cut by a move: " + l, l.startsWith("acs ") && l.contains("end=value"));
    }
}
