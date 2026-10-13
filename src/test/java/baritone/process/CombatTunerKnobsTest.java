package baritone.process;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * More than one number to move, fights that open differently from how they go on, and what is learned written out
 * and read back. Every fight here is made up, as in {@link CombatTunerTest}, which holds the rules for one number.
 */
public class CombatTunerKnobsTest {

    private static final String CTX = "sword+shield/player", ALL = "sword+mace+shield/player", BARE = "sword/player";
    private static final int WINDOW = CombatOutcome.Rules.WINDOW, WARMUP = CombatTuner.Rules.WARMUP, LOOK = CombatTuner.Rules.LOOK;
    private static final int REST = CombatTuner.Rules.REST;
    private static final int KEEP = 2 * (LOOK << CombatTuner.Rules.KEEP_LOOK);
    /** Windows a trial lasts when the two sides come out dead level at every look. */
    private static final int LEVEL = level();

    private static int level() {
        for (int i = 0; i < CombatTuner.Rules.FUTILE.length; i++) {
            if (CombatTuner.Rules.FUTILE[i] >= 0) return 2 * (LOOK << i);
        }
        return 2 * (LOOK << CombatTuner.Rules.LOOKS - 1);
    }

    /** What a setting is worth in a made-up world: damage dealt and taken per active tick. */
    private interface World {
        double[] worth(int[] values, boolean opening);
    }

    private static final World FLAT = (v, o) -> new double[]{0.10, 0.05};

    private final CombatPolicy.Tuned a = new CombatPolicy.Tuned("t.a", "shield", 10, 2, 20, 4, 1);
    private final CombatPolicy.Tuned b = new CombatPolicy.Tuned("t.b", "shield", 50, 20, 80, 10, 100);
    private final CombatPolicy.Tuned c = new CombatPolicy.Tuned("t.c", "mace", 5, 0, 10, 5, 1);
    private final List<CombatPolicy.Tuned> knobs = List.of(a, b, c);
    private final List<String> lines = new ArrayList<>();
    private CombatTuner tuner = new CombatTuner(knobs, lines::add);
    private final Map<String, Integer> fought = new HashMap<>();

    /** The setting's name as {@link CombatPolicy#setting} writes it. */
    private String name(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < v.length; k++) {
            if (v[k] != knobs.get(k).base) sb.append(sb.length() > 0 ? "," : "").append(knobs.get(k).name).append(':').append(v[k]);
        }
        return sb.length() > 0 ? sb.toString() : CombatPolicy.BASE;
    }

    /** What the tuner names for the next window, checked: inside the bounds, on the steps, and one number at most away from its trusted value. */
    private int[] named(String ctx, boolean opening) {
        int[] v = new int[knobs.size()];
        int away = 0;
        for (int k = 0; k < v.length; k++) {
            CombatPolicy.Tuned t = knobs.get(k);
            v[k] = tuner.value(ctx, k, opening);
            assertTrue(t.name + " outside the bounds: " + v[k], v[k] >= t.min && v[k] <= t.max);
            assertEquals(t.name + " between two steps: " + v[k], 0, (v[k] - t.base) % t.step);
            if (v[k] != tuner.trusted(ctx, k)) {
                assertEquals(t.name + " more than a step from the trusted value", t.step, Math.abs(v[k] - tuner.trusted(ctx, k)));
                away++;
            }
        }
        assertTrue("more than one number on trial", away <= 1);
        return v;
    }

    /** A window with the world's rates, a fifth over and under by turns (three tenths for damage taken). */
    private CombatOutcome.Tally window(String ctx, World world, int[] v, boolean opening) {
        double[] w = world.worth(v, opening);
        int sign = fought.merge(ctx + name(v) + opening, 1, Integer::sum) % 2 == 0 ? -1 : 1;
        CombatOutcome.Tally t = new CombatOutcome.Tally();
        t.active = WINDOW;
        t.wall = WINDOW + 10;
        t.dealt = WINDOW * w[0] * (1 + 0.2 * sign);
        t.taken = WINDOW * w[1] * (1 + 0.3 * sign);
        t.windows = 1;
        t.seq = opening ? 0 : 1;
        return t;
    }

    private int[] fight(String ctx, World world, boolean opening) {
        int[] v = named(ctx, opening);
        tuner.window(ctx, name(v), window(ctx, world, v, opening), "full");
        return v;
    }

    /** Fights that are one window long, so every window opens one: the tuner then has one part to weigh, as with one number. */
    private void fight(String ctx, World world, int windows) {
        for (int i = 0; i < windows; i++) fight(ctx, world, true);
    }

    private int verdicts(String what) {
        return (int) lines.stream().filter(l -> l.contains("verdict=" + what)).count();
    }

    /** Which number each verdict was about, and the candidate, in order. */
    private List<String> tried() {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            String[] f = l.split(" ");
            out.add(f[2] + " " + f[4].substring("candidate=".length()));
        }
        return out;
    }

    @Test
    public void theNumbersAreTakenUpInTurnAndThenItRests() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP + 4 * LEVEL);
        assertEquals(List.of("t.a 6", "t.a 14", "t.b 40", "t.b 60"), tried());
        for (int i = 0; i < REST; i++) assertEquals("resting, window " + i, CombatPolicy.BASE, name(fight(CTX, FLAT, true)));
        fight(CTX, FLAT, LEVEL);
        assertEquals("the round begins again with the first", "t.a 6", tried().get(4));
        assertEquals(0, verdicts("keep"));
    }

    @Test
    public void aNumberTheKitHasNoUseForIsLeftAlone() {
        tuner.on(true);
        fight(ALL, FLAT, WARMUP + 6 * LEVEL);
        assertEquals("with a mace in the kit its number is tried too", List.of("t.a 6", "t.a 14", "t.b 40", "t.b 60", "t.c 0", "t.c 10"), tried());
        lines.clear();
        fight(BARE, FLAT, 3000);
        assertTrue("no shield and no mace: nothing to try", lines.isEmpty());
        AcsStatus st = tuner.status(BARE);
        assertEquals(-1, st.knob);
        assertEquals(AcsStatus.Phase.WATCHING, st.phase);
        assertEquals("nothing to tune for this kit", st.caption());
        assertTrue(tuner.report().stream().anyMatch(l -> l.startsWith(BARE) && l.contains("nothing to tune in this kit")));
    }

    /** A number the kit holds the thing for, and something else that takes its place, is not read in that kit and not moved. */
    @Test
    public void aNumberSomethingElseInTheKitTakesThePlaceOfIsLeftAlone() {
        CombatPolicy.Tuned dive = new CombatPolicy.Tuned("m.dive", "mace", "spear", 60, 30, 90, 10, 100);
        CombatPolicy.Tuned wait = new CombatPolicy.Tuned("m.wait", "mace", null, 14, 2, 30, 4, 1);
        CombatTuner mace = new CombatTuner(List.of(dive, wait), lines::add);
        assertEquals(2, mace.status("mace+shield/player").knobs);
        assertEquals("m.dive", mace.status("mace+shield/player").name);
        assertEquals(1, mace.status("mace+spear+shield/player").knobs);
        assertEquals("m.wait", mace.status("mace+spear+shield/player").name);
        assertEquals(0, mace.status("sword+spear/player").knobs);
        CombatPolicy policy = new CombatPolicy();
        CombatTuner whole = new CombatTuner(policy.tuned, lines::add);
        assertEquals("the shield's six, the mace's five and the exchange's eight", 19, whole.status("sword+mace+shield/player").knobs);
        assertEquals("with a spear the dive's four are not read", 15, whole.status("mace+spear+shield/player").knobs);
        assertEquals("with an axe a raised shield is not waited on", 13, whole.status("sword+axe+shield/player").knobs);
        assertEquals(13, whole.status("mace/player").knobs);
        assertEquals(8, whole.status("sword/player").knobs);
        assertEquals("a mob does not duel: the three numbers of a duel are left alone against one", 8, whole.status("sword+axe+shield/mob").knobs);
        assertEquals(2, whole.status("sword/mob").knobs);
    }

    /** A number can be for one kind of foe alone. */
    @Test
    public void aNumberForOneKindOfFoeIsLeftAloneAgainstTheOther() {
        CombatPolicy.Tuned duel = new CombatPolicy.Tuned("x.duel", "/player", 90, 80, 100, 5, 100);
        CombatPolicy.Tuned any = new CombatPolicy.Tuned("x.any", null, 14, 2, 30, 4, 1);
        CombatTuner t = new CombatTuner(List.of(duel, any), lines::add);
        assertEquals(2, t.status("sword/player").knobs);
        assertEquals("x.duel", t.status("sword/player").name);
        assertEquals(1, t.status("sword/mob").knobs);
        assertEquals("x.any", t.status("sword/mob").name);
        assertEquals("a kit is not a kind of foe", 1, t.status("player/mob").knobs);
    }

    @Test
    public void aStepKeptOnOneNumberStaysWhileTheOthersAreTried() {
        tuner.on(true);
        World world = (v, o) -> new double[]{v[1] >= 60 ? 0.15 : 0.10, 0.05};
        fight(CTX, world, WARMUP + 3 * LEVEL + KEEP);
        assertEquals(List.of("t.a 6", "t.a 14", "t.b 40", "t.b 60"), tried());
        assertEquals(1, verdicts("keep"));
        assertEquals(60, tuner.trusted(CTX, 1));
        assertEquals(a.base, tuner.trusted(CTX, 0));
        // the same way again, then every other number once more before it rests: the ground has moved under them
        fight(CTX, world, 3 * LEVEL);
        assertEquals(List.of("t.b 70", "t.a 6", "t.a 14"), tried().subList(4, 7));
        assertTrue(lines.get(5), lines.get(5).contains(" set=t.b:60 "));
        for (int i = 0; i < REST; i++) assertEquals("t.b:60", name(fight(CTX, world, true)));
        assertEquals(60, tuner.trusted(CTX, 1));
        AcsStatus st = tuner.status(CTX);
        assertEquals("the round stands where it stopped", "t.b", st.name);
        assertEquals("b  2/2", st.title());
        st = tuner.status(ALL);
        assertEquals("a kit not fought yet", 3, st.knobs);
    }

    /** The card shows a number in its own unit, whatever it is counted in. */
    @Test
    public void aValueIsShownInItsOwnUnit() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP + 2 * LEVEL + 1);
        AcsStatus st = tuner.status(CTX);
        assertEquals("t.b", st.name);
        assertEquals(100, st.per);
        assertEquals(40, st.candidate);
        assertEquals("0.40", st.show(st.candidate));
        assertEquals("0.50", b.show(b.base));
        assertEquals("b  2/2", st.title());
        assertEquals("10", a.show(10));
        assertEquals("4.5", new CombatPolicy.Tuned("t.d", null, 45, 30, 60, 5, 10).show(45));
    }

    /** The windows that open a fight are shared out by themselves, and so are the others: each kind goes trusted, candidate, candidate, trusted. */
    @Test
    public void eachPartOfAFightTakesItsOwnTurns() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP);
        boolean[] opening = {true, false, false, true, false, true, true, false, false, false, true, false, true, false, false, true};
        List<Integer> first = new ArrayList<>(), later = new ArrayList<>();
        for (boolean o : opening) (o ? first : later).add(fight(CTX, FLAT, o)[0]);
        int t = a.base, cand = a.base - a.step;
        assertEquals(List.of(t, cand, cand, t, t, cand, cand), first);
        assertEquals(List.of(t, cand, cand, t, t, cand, cand, t, t), later);
    }

    private static CombatOutcome.Tally tally(double dealt, double taken, int seq) {
        CombatOutcome.Tally t = new CombatOutcome.Tally();
        t.active = WINDOW;
        t.wall = WINDOW;
        t.dealt = WINDOW * dealt;
        t.taken = WINDOW * taken;
        t.windows = 1;
        t.seq = seq;
        return t;
    }

    /**
     * A fight's opening is not like the rest of it. Told apart, the gap between the two is no longer counted as
     * scatter, and the same windows say more: here a tenth less damage taken, lost in the gap when they are thrown
     * together.
     */
    @Test
    public void theOpeningOfAFightIsNotCountedAsScatter() {
        CombatTuner.Arm[] apart = {new CombatTuner.Arm(), new CombatTuner.Arm()}, together = {new CombatTuner.Arm(), new CombatTuner.Arm()};
        for (int side = 0; side < 2; side++) {
            double less = side == 0 ? 0.9 : 1.0; // the candidate takes a tenth less in both parts
            for (int i = 0; i < 20; i++) {
                double wobble = i % 2 == 0 ? 1.05 : 0.95;
                boolean opens = i % 5 < 2;
                double taken = (opens ? 0.12 : 0.02) * less * wobble;
                apart[side].add(tally(0.10, taken, opens ? 0 : 1));
                together[side].add(tally(0.10, taken, 0));
            }
        }
        CombatTuner.Cmp one = new CombatTuner.Cmp(apart[0], apart[1], false), all = new CombatTuner.Cmp(together[0], together[1], false);
        assertEquals("the same windows, the same rates", all.pct(), one.pct(), 1e-9);
        assertEquals(-10, one.pct(), 1e-9);
        assertTrue("together " + all.z() + ", apart " + one.z(), all.z() > -1 && one.z() < -2.5);
    }

    /** Each part counts for its share of all the fighting, both values' together, so neither value's mix of openings tilts the two. */
    @Test
    public void thePartsAreWeighedByWhatBothValuesFought() {
        CombatTuner.Arm cand = new CombatTuner.Arm(), trusted = new CombatTuner.Arm();
        for (int i = 0; i < 20; i++) {
            double wobble = i % 2 == 0 ? 1.05 : 0.95;
            cand.add(tally(0.10, (i < 6 ? 0.12 : 0.02) * wobble, i < 6 ? 0 : 1)); // 6 openings to 14
            trusted.add(tally(0.10, (i < 14 ? 0.12 : 0.02) * wobble, i < 14 ? 0 : 1)); // 14 to 6
        }
        CombatTuner.Cmp cmp = new CombatTuner.Cmp(cand, trusted, false);
        assertEquals("neither takes more in either part", 0, cmp.pct(), 1e-9);
        assertEquals("half of all the fighting was openings", 0.07, cmp.c, 1e-9);
    }

    /** With too few windows of one part to know its scatter, all are taken together. */
    @Test
    public void withTooFewOpeningsTheWindowsAreTakenTogether() {
        CombatTuner.Arm[] apart = {new CombatTuner.Arm(), new CombatTuner.Arm()}, together = {new CombatTuner.Arm(), new CombatTuner.Arm()};
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < 20; i++) {
                boolean opens = i < CombatTuner.Rules.APART - 1;
                double dealt = (opens ? 0.05 : 0.10) * (side == 0 ? 1.2 : 1.0) * (i % 2 == 0 ? 1.1 : 0.9);
                apart[side].add(tally(dealt, 0.05, opens ? 0 : 1));
                together[side].add(tally(dealt, 0.05, 0));
            }
        }
        assertEquals(new CombatTuner.Cmp(together[0], together[1], true).z(), new CombatTuner.Cmp(apart[0], apart[1], true).z(), 0);
    }

    /** Fights of several windows whose openings cost far more: a value that takes a fifth less all through is found, and none is kept without one. */
    @Test
    public void aGainIsFoundAcrossFightsThatOpenHard() {
        tuner.on(true);
        World world = (v, o) -> new double[]{0.10, (o ? 0.12 : 0.02) * (v[0] < 10 ? 0.8 : 1.0)};
        for (int i = 0; i < WARMUP + 2 * KEEP && tuner.trusted(CTX, 0) == a.base; i++) fight(CTX, world, i % 3 == 0);
        assertEquals(6, tuner.trusted(CTX, 0));
        assertTrue(lines.get(0), lines.get(0).contains("verdict=keep why=takes-less"));
    }

    // ------------------------------------------------------------------ written out and read back

    @Test
    public void whatWasLearnedIsReadBack() {
        tuner.on(true);
        World world = (v, o) -> new double[]{v[1] >= 60 ? 0.15 : 0.10, 0.05};
        fight(CTX, world, WARMUP + 3 * LEVEL + KEEP + 2 * LEVEL); // t.b kept at 0.60, 0.70 dropped, t.a's step down dropped
        fight(ALL, FLAT, 3);
        List<String> saved = tuner.save();
        assertEquals("a line for the version and one for each kit", 3, saved.size());

        tuner = new CombatTuner(knobs, lines::add);
        assertEquals(2, tuner.load(saved));
        assertFalse("reading is not learning", tuner.dirty());
        List<String> again = tuner.save();
        for (int i = 0; i < saved.size(); i++) {
            assertEquals("written out again it is what was read, but for the wait", saved.get(i).replaceAll(" rest=\\d+ ", " rest=" + WARMUP + " "), again.get(i));
        }
        assertEquals(60, tuner.trusted(CTX, 1));
        assertEquals(a.base, tuner.trusted(CTX, 0));
        assertEquals(b.base, tuner.trusted(ALL, 1));
        assertEquals("off, it is the baselines whatever was read", b.base, tuner.value(CTX, 1, true));
        tuner.on(true);
        AcsStatus st = tuner.status(CTX);
        assertEquals(1, st.kept);
        assertEquals(5, st.dropped);
        int before = lines.size();
        assertEquals("the trial that had just begun is the one under way", AcsStatus.Phase.TRIAL, st.phase);
        assertEquals(14, st.candidate);
        fight(CTX, world, LEVEL);
        assertEquals("it goes on where it was: the step down on t.a was dropped, the step up is next", "t.a 14", tried().get(before));
        fight(CTX, world, 2 * LEVEL);
        assertFalse("and the step down, dropped before the game was closed, is not gone back to in the same round",
                tried().subList(before, tried().size()).contains("t.a 6"));
    }

    /**
     * A trial can need more fighting than a session holds. Stopped and read back part-way, it comes to the verdict
     * it would have come to unbroken, on the same sums to the last digit.
     */
    @Test
    public void aTrialUnderWayIsTakenUpWhereItStopped() {
        World world = (v, o) -> new double[]{v[0] < 10 ? 0.15 : 0.10, 0.05};
        tuner.on(true);
        fight(CTX, world, WARMUP + KEEP);
        List<String> unbroken = new ArrayList<>(lines);
        assertEquals(6, tuner.trusted(CTX, 0));
        assertEquals(1, unbroken.size());

        lines.clear();
        fought.clear();
        tuner = new CombatTuner(knobs, lines::add);
        tuner.on(true);
        fight(CTX, world, WARMUP + 7);
        List<String> saved = tuner.save();
        assertTrue(saved.get(1), saved.get(1).contains(" trial=6:0 t0="));
        tuner = new CombatTuner(knobs, lines::add);
        assertEquals(1, tuner.load(saved));
        assertEquals("off, what was read back changes nothing", a.base, tuner.value(CTX, 0, true));
        tuner.on(true);
        AcsStatus st = tuner.status(CTX);
        assertEquals(AcsStatus.Phase.TRIAL, st.phase);
        assertEquals(6, st.candidate);
        assertEquals(3.0, st.fought, 1e-9);
        fight(CTX, world, KEEP - 7);
        assertEquals(unbroken, lines);
        assertEquals(6, tuner.trusted(CTX, 0));
    }

    /** A trial the rules could not have begun is left out; what was learned before it stands. */
    @Test
    public void aTrialThatDoesNotFitIsLeftOutAndTheLineStands() {
        tuner.on(true);
        World world = (v, o) -> new double[]{v[0] < 10 ? 0.15 : 0.10, 0.05};
        fight(CTX, world, WARMUP + KEEP + 7); // 6 is kept and 2 is on trial against it
        String line = tuner.save().get(1);
        assertTrue(line, line.contains(" trial=2:0 t0="));
        assertEquals("as written", AcsStatus.Phase.TRIAL, phaseAfter(line));
        assertEquals("a candidate between two steps", AcsStatus.Phase.WATCHING, phaseAfter(line.replace(" trial=2:0 ", " trial=3:0 ")));
        assertEquals("a candidate two steps away", AcsStatus.Phase.WATCHING, phaseAfter(line.replace(" trial=2:0 ", " trial=14:0 ")));
        assertEquals("a candidate outside the bounds", AcsStatus.Phase.WATCHING, phaseAfter(line.replace(" trial=2:0 ", " trial=-2:0 ")));
        assertEquals("a look there is none of", AcsStatus.Phase.WATCHING, phaseAfter(line.replace(" trial=2:0 ", " trial=2:" + CombatTuner.Rules.LOOKS + " ")));
        assertEquals("a look before the first", AcsStatus.Phase.WATCHING, phaseAfter(line.replace(" trial=2:0 ", " trial=2:-1 ")));
        assertEquals("no look", AcsStatus.Phase.WATCHING, phaseAfter(line.replace(" trial=2:0 ", " trial=2 ")));
        assertEquals("a side's sums missing", AcsStatus.Phase.WATCHING, phaseAfter(line.substring(0, line.lastIndexOf(" c1="))));
        assertEquals("sums that are no sums", AcsStatus.Phase.WATCHING, phaseAfter(line.substring(0, line.lastIndexOf(" c1=")) + " c1=1,200,210,NaN,1,1,1,1,1,1"));
        assertEquals("sums of less than nothing", AcsStatus.Phase.WATCHING, phaseAfter(line.substring(0, line.lastIndexOf(" c1=")) + " c1=1,200,210,-4,1,1,1,1,1,1"));
        assertEquals("too few sums", AcsStatus.Phase.WATCHING, phaseAfter(line.substring(0, line.lastIndexOf(" c1=")) + " c1=1,200,210"));
        assertTrue(line, line.contains(" knob=t.a:10:6:"));
        tuner = new CombatTuner(knobs, lines::add);
        assertEquals(1, tuner.load(List.of(HEAD, line.replace(" knob=t.a:10:6:", " knob=t.a:10:2:").replace(" trial=2:0 ", " trial=-2:0 "))));
        tuner.on(true);
        assertEquals("a step past the lowest value there is", AcsStatus.Phase.WATCHING, tuner.status(CTX).phase);
        named(CTX, true);
    }

    /** Reads the one line back and says what the tuner is doing in the kit; the kept value must have been read whatever the trial. */
    private AcsStatus.Phase phaseAfter(String line) {
        tuner = new CombatTuner(knobs, lines::add);
        assertEquals(line, 1, tuner.load(List.of(HEAD, line)));
        assertEquals(line, 6, tuner.trusted(CTX, 0));
        tuner.on(true);
        named(CTX, true); // whatever was read, the next window's setting is inside the rules
        return tuner.status(CTX).phase;
    }

    /** Switched off, the trial under way is given up, and a trial given up is not written out. */
    @Test
    public void aTrialGivenUpIsNotWrittenOut() {
        tuner.on(true);
        fight(CTX, FLAT, WARMUP + 5);
        assertTrue(tuner.save().get(1), tuner.save().get(1).contains(" trial="));
        tuner.on(false);
        assertFalse(tuner.save().get(1), tuner.save().get(1).contains(" trial="));
    }

    /** What a step cost is remembered with it: the limit is still the baselines' after a restart. */
    @Test
    public void theCostOfTheStepsTakenIsReadBackToo() {
        tuner.on(true);
        World world = (v, o) -> new double[]{v[0] <= 2 ? 0.225 : v[0] <= 6 ? 0.15 : 0.10, v[0] <= 2 ? 0.05 * 1.04 * 1.04 : v[0] <= 6 ? 0.05 * 1.04 : 0.05};
        fight(CTX, world, WARMUP + KEEP);
        assertEquals(6, tuner.trusted(CTX, 0));
        List<String> saved = tuner.save();
        assertTrue(saved.get(1), saved.get(1).contains(" cost=1.0400 gain=1.5000 "));
        tuner = new CombatTuner(knobs, lines::add);
        tuner.load(saved);
        tuner.on(true);
        fight(CTX, world, 3000);
        assertEquals("4% more on top of 4% more is over the margin", 6, tuner.trusted(CTX, 0));
        assertEquals(1, verdicts("keep"));
    }

    private static final String HEAD = "acs 1";

    private int trustedAfter(String... saved) {
        tuner = new CombatTuner(knobs, lines::add);
        tuner.load(List.of(saved));
        return tuner.trusted(CTX, 0);
    }

    /** At the baselines nothing has been paid: a cost written beside them is not carried into the steps that follow. */
    @Test
    public void atTheBaselinesNoCostIsReadBack() {
        assertEquals(1, tuner.load(List.of(HEAD, "ctx=" + CTX + " cost=1.0900 gain=0.9200 kept=1 dropped=3 reverted=1 fruitless=0 idle=0 rest=3 restOf=10 at=t.a knob=t.a:10:10:- knob=t.b:50:50:-")));
        assertTrue(tuner.save().get(1), tuner.save().get(1).contains(" cost=1.0000 gain=1.0000 kept=1 dropped=3 reverted=1 "));
    }

    /** A line is taken whole or not at all: nothing in the file can put a value where the rules could not have. */
    @Test
    public void aLineThatDoesNotFitIsLeftOut() {
        String rest = " cost=1.0000 gain=1.2000 kept=1 dropped=0 reverted=0 fruitless=0 idle=0 rest=3 restOf=10 at=t.a";
        assertEquals("as written", 6, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:6:u- knob=t.b:50:50:-"));
        assertEquals("the baseline has changed since", 10, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:14:6:u- knob=t.b:50:50:-"));
        assertEquals("under the minimum", 10, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:-2:u- knob=t.b:50:50:-"));
        assertEquals("over the maximum", 10, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:22:u- knob=t.b:50:50:-"));
        assertEquals("between two steps", 10, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:7:u- knob=t.b:50:50:-"));
        assertEquals("one bad number spoils the line", 10, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:6:u- knob=t.b:50:95:-"));
        assertEquals("a number that is gone, at its baseline, is no matter", 6, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:6:u- knob=t.gone:3:3:-"));
        assertEquals("a number that is gone, away from it: the setting cannot be had again", 10,
                trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:6:u- knob=t.gone:3:4:-"));
        assertEquals("a number this kit has no use for", 10, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:6:u- knob=t.c:5:10:-"));
        assertEquals("not a number", 10, trustedAfter(HEAD, "ctx=" + CTX + rest + " knob=t.a:10:six:u-"));
        assertEquals("a cost that is no cost", 10, trustedAfter(HEAD, "ctx=" + CTX + rest.replace("cost=1.0000", "cost=NaN") + " knob=t.a:10:6:u-"));
        assertEquals("another version", 10, trustedAfter("acs 0", "ctx=" + CTX + rest + " knob=t.a:10:6:u-"));
        assertEquals("no version", 10, trustedAfter("ctx=" + CTX + rest + " knob=t.a:10:6:u-"));
        assertEquals("a bad line costs the others nothing", 6,
                trustedAfter(HEAD, "what is this", "ctx=" + ALL + rest + " knob=t.a:10:99:-", "ctx=" + CTX + rest + " knob=t.a:10:6:u-"));
        assertEquals(a.base, tuner.trusted(ALL, 0));
        assertEquals("nothing at all", 10, trustedAfter());
    }

    /** However short the file says the wait is, a kit read back is watched for a warm-up first. */
    @Test
    public void whatIsReadBackIsWatchedBeforeItIsTested() {
        trustedAfter(HEAD, "ctx=" + CTX + " cost=1.0000 gain=1.0000 kept=0 dropped=0 reverted=0 fruitless=0 idle=0 rest=-50 restOf=1 at=t.b knob=t.a:10:10:- knob=t.b:50:50:d-");
        tuner.on(true);
        assertEquals(WARMUP, tuner.status(CTX).rest);
        fight(CTX, FLAT, WARMUP + LEVEL);
        assertEquals("where it was, and the step down on t.b was tried already", List.of("t.b 60"), tried());
    }

    @Test
    public void itSaysWhenThereIsSomethingToWrite() {
        assertFalse(tuner.dirty());
        fight(CTX, FLAT, 5);
        assertFalse("off, nothing is learned", tuner.dirty());
        tuner.on(true);
        fight(CTX, FLAT, 1);
        assertTrue(tuner.dirty());
        assertFalse("a window is no hurry", tuner.urgent());
        tuner.saved();
        assertFalse(tuner.dirty());
        fight(CTX, FLAT, WARMUP + LEVEL);
        assertTrue("a verdict is", tuner.urgent());
        tuner.saved();
        tuner.clear();
        assertTrue(tuner.urgent());
        assertEquals("forgotten: the version and nothing else", List.of(HEAD), tuner.save());
    }

    // ------------------------------------------------------------------ whatever the fights are like

    /** Worlds drawn at random, fights of random length: every value named is one the rules allow, and one number at most is on trial. */
    @Test
    public void whateverTheFightsItStaysInsideTheRules() {
        Random rnd = new Random(5);
        for (int run = 0; run < 20; run++) {
            tuner = new CombatTuner(knobs, lines::add);
            tuner.on(true);
            double[] pull = {rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.001, rnd.nextGaussian() * 0.01};
            World world = (v, o) -> new double[]{Math.max(0.01, 0.10 + pull[0] * (v[0] - 10) + pull[1] * (v[1] - 50)), Math.max(0.005, (o ? 0.10 : 0.03) + pull[2] * 0.1 * (v[2] - 5))};
            boolean opening = true;
            for (int i = 0; i < 4000; i++) {
                String ctx = rnd.nextInt(3) == 0 ? ALL : CTX;
                int[] v = named(ctx, opening); // checks every value
                CombatOutcome.Tally t = window(ctx, world, v, opening);
                t.active = 1 + rnd.nextInt(WINDOW);
                t.dealt *= t.active / (double) WINDOW * (0.5 + rnd.nextDouble());
                t.taken *= t.active / (double) WINDOW * (0.2 + 1.6 * rnd.nextDouble());
                if (rnd.nextInt(500) == 0) t.unsynced = 1;
                tuner.window(ctx, name(v), t, "full");
                opening = rnd.nextInt(3) == 0;
                if (rnd.nextInt(700) == 0) {
                    List<String> saved = tuner.save();
                    tuner = new CombatTuner(knobs, lines::add);
                    assertEquals(saved.size() - 1, tuner.load(saved));
                    tuner.on(true);
                }
            }
        }
    }

    /**
     * The whole loop with every number of the real policy, on a made-up fight that stops and starts: the observer
     * closes windows, the tuner hears of them, its values go to the policy. A setting never moves inside a window,
     * whichever part of a fight the window is.
     */
    @Test
    public void inTheLoopNoWindowIsCutByAMoveWhateverPartOfTheFightItIs() {
        CombatPolicy policy = new CombatPolicy();
        List<String> log = new ArrayList<>();
        CombatTuner loop = new CombatTuner(policy.tuned, log::add);
        CombatOutcome out = new CombatOutcome(log::add, loop::window);
        CombatShield shield = new CombatShield(null, null, null, null, null, null, policy.shield, null);
        loop.on(true);
        Random rnd = new Random(3);
        String kit = "sword+shield";
        int since = 0, gap = 13, cool = 0;
        float foeHp = 20, myHp = 20;
        boolean landing = false;
        for (int tick = 0; tick < 300_000; tick++) {
            if (rnd.nextInt(450) == 0) out.interrupt("lost"); // fights of two windows or so, like the bench's
            String ctx = CombatOutcome.context(out.idle() ? kit : out.kit(), true);
            int away = 0;
            for (int k = 0; k < policy.tuned.size(); k++) {
                CombatPolicy.Tuned t = policy.tuned.get(k);
                assertTrue(t.name, t.set(loop.value(ctx, k, out.opening())));
                if (t.get() != loop.trusted(ctx, k)) away++;
            }
            assertTrue(away <= 1);
            boolean block = shield.swingDue(true, since, 3.0);
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
        int windows = 0, openings = 0;
        for (String l : log) {
            if (!l.startsWith("acs ")) continue;
            windows++;
            if (l.contains(" seq=0 ")) openings++;
            assertFalse("a window was cut by a move: " + l, l.contains("end=value"));
        }
        assertTrue(windows > 1000 && openings > 300 && openings < windows - 300);
        assertTrue("the tuner's names for the settings are the policy's, or no window would have counted", log.stream().anyMatch(l -> l.startsWith("acs-tune ")));
        // blocking later in the swing leaves more ticks to hit in and fewer blocks: the trial of 6 saw the first and
        // was refused for the second, so the value the tuner chose is the one the shield decided by
        String six = log.stream().filter(l -> l.startsWith("acs-tune ") && l.contains(" shield.dueLate trusted=10 candidate=6 ")).findFirst().orElse(null);
        assertNotNull("the number this fight turns on was tried", six);
        assertTrue(six, num(six, "dealtC=") > 1.1 * num(six, "dealtT=") && num(six, "takenC=") > num(six, "takenT="));
        assertTrue(six, six.contains(" verdict=drop why=takes-more "));
        assertEquals("and a value that takes more is not trusted for dealing more", policy.shield.dueLate.base, loop.trusted(kit + "/player", policy.tuned.indexOf(policy.shield.dueLate)));
    }

    private static double num(String line, String key) {
        int i = line.indexOf(" " + key) + 1 + key.length(), j = line.indexOf(' ', i);
        return Double.parseDouble(j < 0 ? line.substring(i) : line.substring(i, j));
    }
}
