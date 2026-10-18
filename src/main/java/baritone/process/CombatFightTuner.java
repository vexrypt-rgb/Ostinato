package baritone.process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Moves the numbers a fight is fought whole under ({@link CombatPolicy#whole}): the health at which an apple is eaten,
 * a potion thrown, a totem brought out. {@link CombatTuner} cannot weigh those. It counts damage per tick, and healing
 * sooner neither deals more nor takes less: it gives up ticks of attack now so as not to die a minute later, in some
 * other window, under some other value. So these are judged by whole engagements ({@link CombatOutcome.Fight}), each
 * fought from its first tick to its last under one value. Like the other it is off until switched on
 * ({@code pvpAutotune}), and off it does nothing at all.
 * <p>
 * It follows the same course. For each kit, kind of foe and lag band it holds a <em>trusted</em> value of every
 * number, the baselines to begin with. After {@link Rules#WARMUP} fights there it takes up a number the fight just
 * ended had a use for (an apple line, with apples carried) and tries the neighbour one step down. Fights are fought
 * under the trusted value and the candidate, the next going to the one that has fought fewer and to the toss of a
 * coin when they are level, so that no order the foes come in can fall into step with the turns. A value only ever
 * changes between two fights, and only one number is on trial.
 * <p>
 * Two things are read off a fight: its score (what the foes lost less what we lost, in full health bars) and whether
 * it ended in our death. The two values are compared when each has fought {@link Rules#LOOK} fights, then twice that,
 * up to {@link Rules#LOOKS} looks. At a look the candidate is
 * <ul>
 * <li>dropped if it dies in a larger share of its fights, or scores less, by {@link Rules#Z_DROP} standard errors;</li>
 * <li>kept, and becomes the trusted value, if it scores more by {@link Rules#Z_KEEP} standard errors and has not died
 * in a larger share, or dies in a smaller share by as much and has not scored less. Nothing is kept before look
 * {@link Rules#KEEP_LOOK}, nor a value that spends more of what we carry than {@link Rules#SPEND} allows;</li>
 * <li>dropped when it is no longer likely to be shown better (the better of its two leads is under
 * {@link Rules#FUTILE} for the look), and at the last look whatever it shows: not shown better is not better.</li>
 * </ul>
 * After a keep the next step the same way is tried, after a drop the other way, with both dropped the next number.
 * Once all have been gone through with nothing kept the trusted setting is fought for {@link Rules#REST} fights, twice
 * as long each time the baselines stand, and then all are open again: a kept value is put to the test against its
 * neighbour on the baseline's side like any other, and gives its place back as soon as that neighbour is ahead by
 * {@link Rules#Z_DROP} standard errors on either count. A fight in which the foe's health was not shown, or which the
 * kind of foe, the lag band or the stance changed under, is judged for neither side, and so is one in which the number
 * on trial had no use; none is left out for how it went.
 * <p>
 * This runs beside {@link CombatTuner}, not in turn with it. A trial of that one changes value every window, inside
 * every fight, so both values here meet both of its values; a value here is the same for every window of a fight, so
 * both of its values meet it alike.
 */
final class CombatFightTuner {
    /**
     * PROVISIONAL — PENDING IN-GAME CALIBRATION. Every number that decides when a value is tried, kept or dropped. No
     * fight has been judged by them yet. What stands behind them is the scatter of the score over recorded bench
     * rounds, worked out afterwards from their fight logs (0.43 of a health bar from round to round over 363 rounds
     * of sword and shield against VexBot at medium, where nine in ten were won, and 0.74 over 33 rounds against it
     * at expert and perfect, which were even), and the trials of made-up fights in CombatFightTunerTest. None of
     * them can take a value outside a tuned number's own hard bounds.
     */
    static final class Rules {
        /**
         * Fights under the trusted setting in a kit before the first trial. It shows the kit is fought at all. Lower
         * starts a trial on the first fight or two, higher delays them.
         * To change it: how many fights a kit that is rarely carried is fought for in real play.
         */
        static final int WARMUP = 10;
        /**
         * Fights each value has fought at the first look; every later look needs twice the one before. At 20 a side
         * and a scatter of 0.43 a difference has to be about a third of a health bar to stand two standard errors
         * apart, which is enough to act on harm with and no more.
         * To change it: the score's scatter in the kit at hand.
         */
        static final int LOOK = 20;
        /**
         * Looks before a trial is given up: 20, 40, 80, 160, 320 and 640 fights a side. At the last a tenth of a
         * health bar is {@link #Z_KEEP} standard errors where the scatter is 0.65. Few trials get that far:
         * {@link #FUTILE} ends the ones going nowhere. More looks find smaller gains and cost twice the fights each.
         * To change it: how small a gain is worth over a thousand fights of trial.
         */
        static final int LOOKS = 6;
        /**
         * Standard errors by which the candidate must score more, or die in a smaller share of its fights, to be
         * kept. There are two ways to be kept and four looks to be kept at; with made-up fights in which the two
         * values are no different, CombatFightTunerTest holds the share of trials that keep one under 3%.
         * To change it: the share of kept values that hold up when tried again.
         */
        static final double Z_KEEP = 2.75;
        /**
         * The first look, counted from 0, at which a candidate may be kept: 80 fights a side. Before it a death or
         * two either way is most of the difference. Harm may be acted on early, a gain may not.
         * To change it: how often a value kept at this look is taken back.
         */
        static final int KEEP_LOOK = 2;
        /**
         * Standard errors of more deaths, or a lower score, at which the candidate is dropped, and at which a kept
         * value gives its place back to its neighbour on the baseline's side. Lower than {@link #Z_KEEP} on purpose:
         * a wrong drop costs a trial, a wrong keep costs fights.
         * To change it: how often a value dropped here is kept when tried again.
         */
        static final double Z_DROP = 2.0;
        /**
         * After each look but the last, the lead in standard errors (the more of the score above the trusted value's
         * and the share of deaths below it) at or under which the candidate is given up as not likely to be shown
         * better. Lower holds on to candidates longer, higher gives up real small gains early.
         * To change it: trials replayed over recorded fights, gains found against fights spent.
         */
        static final double[] FUTILE = {-0.5, 0.0, 0.5, 1.0, 1.5};
        /**
         * Fights under the trusted setting, once every number has been gone through with nothing kept, before they
         * are tried again; twice as long each time the baselines stand, up to {@link #REST_DOUBLINGS} doublings.
         * Lower spends more of the fighting on trials, higher is slower to notice that the foes have changed.
         * To change it: how fast a kept value goes stale in real play.
         */
        static final int REST = 100;
        static final int REST_DOUBLINGS = 4;
        /**
         * Fights in a row in which the number on trial had no use (its apples, potions or totems were not carried)
         * before the trial is put away and another number taken up. Lower throws a trial away over one fight without
         * supplies, higher waits on a kit that is not coming back.
         * To change it: how long a player goes without the item in real play.
         */
        static final int SHELVE = 20;
        /**
         * What a kept value may spend above the trusted one, per fight, of what we carry to stay alive with: this
         * share more, or this many pieces more when the trusted value spends less than one. The score does not see
         * what is left for the next fight.
         * To change it: what an apple is worth in fights won, measured.
         */
        static final double SPEND = 0.25;

        private Rules() {}
    }

    /** What one value has fought in the trial under way. */
    static final class Arm {
        int n, deaths, wins;
        long wall;
        double sum, sq, spent;

        void add(CombatOutcome.Fight f) {
            double s = f.score();
            n++;
            if (f.death()) deaths++;
            if ("win".equals(f.end)) wins++;
            wall += f.wall;
            sum += s;
            sq += s * s;
            spent += f.spent;
        }

        double mean() {
            return n > 0 ? sum / n : 0;
        }

        /** Variance of the mean score. */
        double var() {
            if (n < 2) return Double.POSITIVE_INFINITY;
            double m = mean();
            return Math.max(0, sq - n * m * m) / (n - 1) / n;
        }

        /** The share of its fights that ended in our death. */
        double dies() {
            return n > 0 ? (double) deaths / n : 0;
        }

        /** Pieces spent per fight. */
        double spends() {
            return n > 0 ? spent / n : 0;
        }

        /** The sums as they stand, so a trial can be taken up where it stopped. */
        String write() {
            return n + "," + deaths + "," + wins + "," + wall + "," + sum + "," + sq + "," + spent;
        }

        /** @throws IllegalArgumentException if these are not sums an arm could hold */
        static Arm read(String v) {
            String[] f = v.split(",", -1);
            if (f.length != 7) throw new IllegalArgumentException(v);
            Arm a = new Arm();
            a.n = Integer.parseInt(f[0]);
            a.deaths = Integer.parseInt(f[1]);
            a.wins = Integer.parseInt(f[2]);
            a.wall = Long.parseLong(f[3]);
            a.sum = Double.parseDouble(f[4]);
            a.sq = Double.parseDouble(f[5]);
            a.spent = Double.parseDouble(f[6]);
            if (a.n < 0 || a.deaths < 0 || a.wins < 0 || a.deaths + a.wins > a.n || a.wall < 0 || !(a.sq >= 0) || !(a.spent >= 0)
                    || !Double.isFinite(a.sum) || Double.isInfinite(a.sq) || Double.isInfinite(a.spent)) throw new IllegalArgumentException(v);
            return a;
        }
    }

    private static final int NONE = Integer.MIN_VALUE;
    private static final String LINE = "fight=";

    /** One kit and kind of foe at one lag band: the values trusted there, and the trial under way if there is one. */
    private final class Ctx {
        final int[] trusted = new int[knobs.size()];
        final boolean[] downTried = new boolean[knobs.size()], upTried = new boolean[knobs.size()];
        int at; // the number taken up
        int candidate = NONE;
        boolean turn; // the next fight is the candidate's
        int rest = Rules.WARMUP, restOf = Rules.WARMUP, look, kept, dropped, reverted, fruitless, idle, unused;
        Arm t = new Arm(), c = new Arm();
        String tKey = CombatPolicy.BASE, cKey = "", last = "watching";

        Ctx() {
            for (int k = 0; k < trusted.length; k++) trusted[k] = knobs.get(k).base;
        }

        boolean atBase() {
            for (int k = 0; k < trusted.length; k++) {
                if (trusted[k] != knobs.get(k).base) return false;
            }
            return true;
        }

        /** The stance's name with the number taken up at {@code v} and the others as trusted; all as trusted for no value. */
        String key(int v) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < trusted.length; k++) {
                int x = k == at && v != NONE ? v : trusted[k];
                if (x != knobs.get(k).base) sb.append(sb.length() > 0 ? "," : "").append(knobs.get(k).name).append(':').append(x);
            }
            return sb.length() > 0 ? sb.toString() : CombatPolicy.BASE;
        }
    }

    private final List<CombatPolicy.Tuned> knobs;
    private final Consumer<String> sink;
    private final Random coin;
    private final Map<String, Ctx> contexts = new LinkedHashMap<>();
    private boolean on, dirty, urgent;

    /**
     * @param knobs the numbers to move, in the order they are taken up; baselines, bounds and steps are read from
     *              them, and they are never written here
     * @param sink  where a verdict's line goes
     * @param coin  tossed for the next fight when both values have fought as many
     */
    CombatFightTuner(List<CombatPolicy.Tuned> knobs, Consumer<String> sink, Random coin) {
        this.knobs = knobs;
        this.sink = sink;
        this.coin = coin;
    }

    boolean on() {
        return on;
    }

    /** Off, no fight is counted and every number reads as its baseline; a trial under way waits where it stands. */
    void on(boolean on) {
        this.on = on;
    }

    /** Forgets everything learned: every kit is back at the baselines with no trial under way. */
    void clear() {
        contexts.clear();
        dirty = urgent = true;
    }

    /** The value of one number the next fight in this kit and kind of foe is to be fought under. Its baseline while off. */
    int value(String context, int knob) {
        if (!on) return knobs.get(knob).base;
        Ctx s = contexts.get(context);
        if (s == null) return knobs.get(knob).base;
        return s.candidate != NONE && knob == s.at && s.turn ? s.candidate : s.trusted[knob];
    }

    /** The value of one number trusted for this kit and kind of foe. */
    int trusted(String context, int knob) {
        Ctx s = contexts.get(context);
        return s == null ? knobs.get(knob).base : s.trusted[knob];
    }

    /** An engagement has ended. Called by the observer for every one, however it went. */
    void fight(CombatOutcome.Fight f) {
        if (!on || f.unsynced || f.mixed) return;
        Ctx s = contexts.computeIfAbsent(f.context, k -> new Ctx());
        List<String> has = has(f);
        dirty = true;
        if (s.candidate == NONE) {
            if (f.stance.equals(s.tKey) && --s.rest <= 0) begin(s, has);
            return;
        }
        if (!uses(knobs.get(s.at), has)) { // nothing in this fight the number could have changed
            if (++s.unused >= Rules.SHELVE) {
                verdict(f.context, s, "stop", "not carried");
                s.downTried[s.at] = s.upTried[s.at] = true;
                begin(s, has);
            }
            return;
        }
        s.unused = 0;
        if (f.stance.equals(s.tKey)) s.t.add(f);
        else if (f.stance.equals(s.cKey)) s.c.add(f);
        else return; // fought under something else, a value set by hand say: neither side's
        judge(f.context, s, has);
        if (s.candidate != NONE) turn(s);
    }

    /** One line per kit and kind of foe fought while on. */
    List<String> report() {
        List<String> lines = new ArrayList<>();
        contexts.forEach((k, s) -> {
            String now;
            if (s.candidate == NONE) {
                now = Math.max(s.rest, 0) + " fights to the next trial";
            } else {
                CombatPolicy.Tuned t = knobs.get(s.at);
                now = String.format(Locale.ROOT, "trying %s %s against %s: %d against %d fights, score %+.3f against %+.3f (z %+.2f), deaths %d against %d (z %+.2f)",
                        t.name, t.show(s.candidate), t.show(s.trusted[s.at]), s.c.n, s.t.n, s.c.mean(), s.t.mean(), zScore(s), s.c.deaths, s.t.deaths, zDies(s.c, s.t));
            }
            lines.add(String.format(Locale.ROOT, "%s: per fight trusted %s, %d kept, %d dropped, %d taken back; %s; last: %s", k, s.tKey, s.kept, s.dropped, s.reverted, now, s.last));
        });
        return lines;
    }

    /** What is going on for one kit and kind of foe, written into the card's status. Asking changes nothing. */
    void status(String context, AcsStatus st) {
        Ctx s = context == null ? null : contexts.get(context);
        st.wholeRest = s == null ? Rules.WARMUP : Math.max(s.rest, 0);
        if (s == null) return;
        st.wholeKept = s.kept;
        st.wholeDropped = s.dropped;
        st.wholeReverted = s.reverted;
        if (!on || s.candidate == NONE) return;
        CombatPolicy.Tuned t = knobs.get(s.at);
        st.wholeTrial = true;
        st.wholeName = t.name.substring(t.name.lastIndexOf('.') + 1);
        st.wholeTrusted = t.show(s.trusted[s.at]);
        st.wholeCandidate = t.show(s.candidate);
        st.wholeFought = Math.min(s.t.n, s.c.n);
        st.wholeNeeded = Rules.LOOK << s.look;
    }

    /** Something has changed since the last {@link #saved}; {@link #urgent} when it is a verdict. */
    boolean dirty() {
        return dirty;
    }

    boolean urgent() {
        return urgent;
    }

    void saved() {
        dirty = urgent = false;
    }

    /**
     * What has been learned, a line for each kit and kind of foe, the trial under way with it: a trial here needs
     * more fights than a session holds. The lines go into the same file as {@link CombatTuner#save}'s, after them.
     */
    List<String> save() {
        List<String> lines = new ArrayList<>();
        contexts.forEach((name, s) -> {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s%s kept=%d dropped=%d reverted=%d fruitless=%d idle=%d unused=%d rest=%d restOf=%d at=%s",
                    LINE, name, s.kept, s.dropped, s.reverted, s.fruitless, s.idle, s.unused, Math.max(s.rest, 0), s.restOf, knobs.isEmpty() ? "-" : knobs.get(s.at).name));
            for (int k = 0; k < knobs.size(); k++) {
                CombatPolicy.Tuned t = knobs.get(k);
                sb.append(" knob=").append(t.name).append(':').append(t.base).append(':').append(s.trusted[k]).append(':')
                        .append(s.downTried[k] ? "d" : "").append(s.upTried[k] ? "u" : "").append('-');
            }
            if (s.candidate != NONE) {
                sb.append(" trial=").append(s.candidate).append(':').append(s.look).append(" t=").append(s.t.write()).append(" c=").append(s.c.write());
            }
            lines.add(sb.toString());
        });
        return lines;
    }

    /**
     * Reads back what {@link #save} wrote from among the lines of the file, in place of whatever is held now. A line
     * is taken whole or not at all: one that names a value outside a number's bounds or off its steps, or a number
     * whose baseline has changed since or that is no longer there while trusted away from it, is left out, and its
     * kit starts from the baselines.
     *
     * @return the lines taken
     */
    int load(Iterable<String> lines) {
        contexts.clear();
        int taken = 0;
        boolean versioned = false;
        for (String line : lines) {
            if (!versioned) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                if (!line.trim().equals(CombatTuner.VERSION)) return 0;
                versioned = true;
                continue;
            }
            if (!line.startsWith(LINE)) continue;
            try {
                String name = null, at = null, trial = null, sumT = null, sumC = null;
                Ctx s = new Ctx();
                boolean ok = true;
                for (String tok : line.trim().split(" ")) {
                    int eq = tok.indexOf('=');
                    if (eq < 0) continue;
                    String key = tok.substring(0, eq), v = tok.substring(eq + 1);
                    if (key.equals("fight")) name = v;
                    else if (key.equals("kept")) s.kept = Integer.parseInt(v);
                    else if (key.equals("dropped")) s.dropped = Integer.parseInt(v);
                    else if (key.equals("reverted")) s.reverted = Integer.parseInt(v);
                    else if (key.equals("fruitless")) s.fruitless = Math.max(0, Integer.parseInt(v));
                    else if (key.equals("idle")) s.idle = Math.max(0, Integer.parseInt(v));
                    else if (key.equals("unused")) s.unused = Math.max(0, Integer.parseInt(v));
                    else if (key.equals("rest")) s.rest = Integer.parseInt(v);
                    else if (key.equals("restOf")) s.restOf = Integer.parseInt(v);
                    else if (key.equals("at")) at = v;
                    else if (key.equals("trial")) trial = v;
                    else if (key.equals("t")) sumT = v;
                    else if (key.equals("c")) sumC = v;
                    else if (key.equals("knob")) {
                        String[] f = v.split(":", -1);
                        int k = index(f[0]), base = Integer.parseInt(f[1]), trusted = Integer.parseInt(f[2]);
                        if (k < 0) {
                            if (trusted != base) ok = false; // the steps to this setting went through a number that is gone
                            continue;
                        }
                        CombatPolicy.Tuned t = knobs.get(k);
                        if (base != t.base || trusted < t.min || trusted > t.max || (trusted - t.base) % t.step != 0) ok = false;
                        else {
                            s.trusted[k] = trusted;
                            s.downTried[k] = f[3].contains("d");
                            s.upTried[k] = f[3].contains("u");
                        }
                    }
                }
                if (!ok || name == null || name.isEmpty() || knobs.isEmpty()) continue;
                int place = index(at);
                s.at = Math.max(place, 0);
                s.idle = Math.min(s.idle, Math.max(0, knobs.size() - 1));
                s.rest = Math.max(s.rest, 0);
                s.restOf = Math.max(s.restOf, s.rest);
                s.tKey = s.key(NONE);
                s.last = "read back";
                if (place >= 0) resume(s, trial, sumT, sumC);
                contexts.put(name, s);
                taken++;
            } catch (RuntimeException e) {
                // a line that cannot be read is a kit that starts from the baselines
            }
        }
        dirty = urgent = false;
        return taken;
    }

    /** Takes up the trial that was under way when the line was written. One the rules could not have begun is left out and the line stands without it. */
    private void resume(Ctx s, String trial, String sumT, String sumC) {
        if (trial == null || sumT == null || sumC == null) return;
        try {
            int colon = trial.indexOf(':'), k = s.at;
            int candidate = Integer.parseInt(trial.substring(0, colon)), look = Integer.parseInt(trial.substring(colon + 1));
            CombatPolicy.Tuned t = knobs.get(k);
            if (Math.abs(candidate - s.trusted[k]) != t.step || candidate < t.min || candidate > t.max || look < 0 || look >= Rules.LOOKS) return;
            Arm trusted = Arm.read(sumT), cand = Arm.read(sumC);
            s.t = trusted;
            s.c = cand;
            s.candidate = candidate;
            s.look = look;
            s.cKey = s.key(candidate);
            turn(s);
        } catch (RuntimeException e) {
            // sums that cannot be read: the number is tried again from the start
        }
    }

    private int index(String name) {
        for (int k = 0; k < knobs.size(); k++) {
            if (knobs.get(k).name.equals(name)) return k;
        }
        return -1;
    }

    /** What a fight was fought with: its kit, piece by piece, the kind of foe as "/player", and what was carried to stay alive with. */
    private static List<String> has(CombatOutcome.Fight f) {
        String context = CombatOutcome.plain(f.context);
        int slash = context.lastIndexOf('/');
        List<String> has = new ArrayList<>(Arrays.asList((slash < 0 ? context : context.substring(0, slash)).split("\\+")));
        if (slash >= 0) has.add(context.substring(slash));
        if (f.carried != null && !f.carried.isEmpty()) has.addAll(Arrays.asList(f.carried.split("\\+")));
        return has;
    }

    private static boolean uses(CombatPolicy.Tuned t, List<String> has) {
        return (t.kit == null || has.contains(t.kit)) && (t.not == null || !has.contains(t.not));
    }

    private static void rest(Ctx s, int fights) {
        s.rest = s.restOf = fights;
    }

    private void abandon(Ctx s) {
        s.candidate = NONE;
        s.turn = false;
        s.t = new Arm();
        s.c = new Arm();
        s.look = 0;
        s.unused = 0;
        s.tKey = s.key(NONE);
        s.cKey = "";
    }

    /**
     * Opens the trial of the next untried neighbour of the number taken up, down first, if the fight just ended had a
     * use for the number. With none left the next number is taken up, and once all have been gone through with
     * nothing kept, rests and then opens them again. With nothing carried that any number is about, nothing is begun
     * and it is asked again after the next fight.
     */
    private void begin(Ctx s, List<String> has) {
        abandon(s);
        boolean any = false;
        for (CombatPolicy.Tuned t : knobs) any |= uses(t, has);
        if (!any) return;
        while (true) {
            int k = s.at;
            CombatPolicy.Tuned t = knobs.get(k);
            boolean used = uses(t, has);
            int down = s.trusted[k] - t.step, up = s.trusted[k] + t.step;
            if (down < t.min) s.downTried[k] = true;
            if (up > t.max) s.upTried[k] = true;
            if (used && (!s.downTried[k] || !s.upTried[k])) {
                // Ostinato: down first, unless watched fights (spectateLearn) point up and up is still untried
                boolean upFirst = !s.upTried[k] && baritone.spectate.SpectatePriors.get().startSteps(t.name, t.base, t.step) > 0.25;
                s.candidate = upFirst ? up : !s.downTried[k] ? down : up;
                s.cKey = s.key(s.candidate);
                turn(s);
                return;
            }
            if (used) s.downTried[k] = s.upTried[k] = false;
            s.at = (s.at + 1) % knobs.size();
            if (++s.idle >= knobs.size()) {
                s.idle = 0;
                rest(s, s.atBase() ? Rules.REST << Math.min(s.fruitless++, Rules.REST_DOUBLINGS) : Rules.REST);
                return;
            }
        }
    }

    /** The side that has fought fewer goes next; level, a coin is tossed. */
    private void turn(Ctx s) {
        s.turn = s.c.n != s.t.n ? s.c.n < s.t.n : coin.nextBoolean();
    }

    private void judge(String context, Ctx s, List<String> has) {
        if (Math.min(s.t.n, s.c.n) < Rules.LOOK << s.look) return;
        double zs = zScore(s), zd = zDies(s.c, s.t);
        CombatPolicy.Tuned t = knobs.get(s.at);
        boolean back = Math.abs(s.candidate - t.base) < Math.abs(s.trusted[s.at] - t.base);
        boolean late = s.look >= Rules.KEEP_LOOK, last = s.look + 1 >= Rules.LOOKS;
        boolean thrifty = s.c.spends() - s.t.spends() <= Rules.SPEND * Math.max(1, s.t.spends());
        // harm first; then the limit a kept value is held to: its neighbour on the baseline's side ahead on either count, and it goes back
        if (zd >= Rules.Z_DROP) drop(context, s, "dies more", has);
        else if (zs <= -Rules.Z_DROP) drop(context, s, "scores less", has);
        else if (back && zd <= -Rules.Z_DROP) {
            s.reverted++;
            move(context, s, "revert", "trusted dies more", has);
        } else if (back && zs >= Rules.Z_DROP) {
            s.reverted++;
            move(context, s, "revert", "trusted scores less", has);
        } else if (late && thrifty && zs >= Rules.Z_KEEP && s.c.dies() <= s.t.dies()) {
            s.kept++;
            move(context, s, "keep", "scores more", has);
        } else if (late && thrifty && zd <= -Rules.Z_KEEP && s.c.mean() >= s.t.mean()) {
            s.kept++;
            move(context, s, "keep", "dies less", has);
        } else if (last && !thrifty && Math.max(zs, -zd) >= Rules.Z_KEEP) drop(context, s, "spends more", has);
        else if (last || Math.max(zs, -zd) <= Rules.FUTILE[s.look]) drop(context, s, "not shown better", has);
        else s.look++;
    }

    /** The candidate becomes the trusted value. The one it replaces is not a candidate again until the number comes round again. */
    private void move(String context, Ctx s, String what, String why, List<String> has) {
        verdict(context, s, what, why);
        int k = s.at;
        boolean down = s.candidate < s.trusted[k];
        s.trusted[k] = s.candidate;
        s.fruitless = 0;
        s.idle = 0;
        s.downTried[k] = !down;
        s.upTried[k] = down;
        begin(s, has);
    }

    private void drop(String context, Ctx s, String why, List<String> has) {
        verdict(context, s, "drop", why);
        s.dropped++;
        if (s.candidate < s.trusted[s.at]) s.downTried[s.at] = true;
        else s.upTried[s.at] = true;
        begin(s, has);
    }

    private void verdict(String context, Ctx s, String what, String why) {
        CombatPolicy.Tuned t = knobs.get(s.at);
        double zs = zScore(s), zd = zDies(s.c, s.t);
        s.last = String.format(Locale.ROOT, "%s %s %s against %s (%s): %d and %d fights, score %+.3f against %+.3f (z %+.2f), deaths %d against %d (z %+.2f)",
                what, t.name.substring(t.name.lastIndexOf('.') + 1), t.show(s.candidate), t.show(s.trusted[s.at]), why, s.c.n, s.t.n, s.c.mean(), s.t.mean(), zs, s.c.deaths, s.t.deaths, zd);
        sink.accept(String.format(Locale.ROOT, "acs-whole ctx=%s %s trusted=%d candidate=%d verdict=%s why=%s stance=%s look=%d nT=%d nC=%d scoreT=%.4f scoreC=%.4f deathsT=%d deathsC=%d winsT=%d winsC=%d spentT=%.3f spentC=%.3f ticksT=%d ticksC=%d zScore=%.2f zDies=%.2f",
                context, t.name, s.trusted[s.at], s.candidate, what, why.replace(' ', '-'), s.tKey, s.look + 1, s.t.n, s.c.n, s.t.mean(), s.c.mean(), s.t.deaths, s.c.deaths,
                s.t.wins, s.c.wins, s.t.spends(), s.c.spends(), s.t.wall, s.c.wall, zs, zd));
        urgent = true;
    }

    /** Standard errors by which the candidate's mean score is above the trusted value's; 0 while either side's scatter is not known, which is no evidence either way. */
    private static double zScore(Ctx s) {
        double se = Math.sqrt(s.c.var() + s.t.var());
        return se > 0 && Double.isFinite(se) ? (s.c.mean() - s.t.mean()) / se : 0;
    }

    /** Standard errors by which the candidate's share of fights ended in our death is above the trusted value's; 0 with no death on either side, or nothing else. */
    static double zDies(Arm c, Arm t) {
        if (c.n == 0 || t.n == 0) return 0;
        double p = (double) (c.deaths + t.deaths) / (c.n + t.n);
        double se = Math.sqrt(p * (1 - p) * (1.0 / c.n + 1.0 / t.n));
        return se > 0 ? (c.dies() - t.dies()) / se : 0;
    }
}
