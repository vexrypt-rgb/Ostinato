package baritone.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Moves the policy's tuned numbers on its own, one number and one step at a time, and keeps a step only when the
 * fights have shown it to be worth more: more damage dealt without more taken, or less taken without less dealt. It
 * is off until switched on ({@code pvpAutotune}), and off it does nothing at all.
 * <p>
 * It works from the windows {@link CombatOutcome} closes and from nothing else, one kit and kind of foe at a time.
 * For each it holds a <em>trusted</em> value of every number the kit has a use for, the baselines to begin with.
 * After {@link Rules#WARMUP} windows there it takes up the first number and tries the neighbour one step down:
 * windows are fought in turn under the trusted value and the candidate, the next window always going to the one
 * that has fought less, so both meet the same foe in the same minutes. The windows that open an engagement are
 * shared out apart from the rest, and compared apart, because the opening of a fight is not like the rest of it.
 * A value only ever changes between two windows, never inside one, and only one number is ever on trial.
 * <p>
 * The two are compared at fixed points, when each has fought {@link Rules#LOOK} windows, then twice that, up to
 * {@link Rules#LOOKS} looks. At a look the candidate is
 * <ul>
 * <li>dropped if it takes more damage per active tick than the trusted value, or deals less, by more than
 * {@link Rules#Z_DROP} standard errors;</li>
 * <li>kept, and becomes the trusted value, if it deals more by {@link Rules#Z_KEEP} standard errors, what it takes
 * leaves the setting no more than {@link Rules#KEEP_GUARD} above what the baselines take, every step from them counted
 * as it was measured, and it deals no less per fight tick, active or not: a value that only thins out the ticks
 * that count has gained nothing;</li>
 * <li>kept as well if it takes less by {@link Rules#Z_KEEP} standard errors and what it deals, counted the same
 * way, is no more than {@link Rules#KEEP_GUARD} under what the baselines deal, per active tick and per fight tick.
 * Nothing is kept before look {@link Rules#KEEP_LOOK}: harm may be acted on early, a gain may not;</li>
 * <li>dropped when it is no longer likely to be shown better (the better of its two leads is under
 * {@link Rules#FUTILE} for the look), and at the last look whatever it shows: not shown better is not better.</li>
 * </ul>
 * After a keep the next step the same way is tried; after a drop the other way; with both ways dropped the next
 * number is taken up. Once every number has been gone through with nothing kept, the trusted setting is fought for
 * {@link Rules#REST} windows and then all are open again, so a value kept once is put to the test again and can
 * lose its place. The baselines are left alone twice as long each time nothing is found against them, up to
 * {@link Rules#REST_DOUBLINGS} doublings; any other setting is tested every rest for as long as it is trusted. A
 * kept value loses its place to a neighbour that is worth more, and it loses it to the neighbour on the baseline's
 * side when it is shown, by {@link Rules#Z_DROP} standard errors, to take more than {@link Rules#GUARD} above what
 * that neighbour takes, or to deal less than {@link Rules#GUARD} under what it deals: that comes before anything
 * else it shows. A window in which the foe's health was not shown ends the trial under way and nothing is concluded
 * from it.
 * <p>
 * Rates are sums over windows (damage over active ticks), and a rate's standard error is that of a ratio of sums, so
 * a window cut short by a death counts for what it holds and none is left out. What was learned can be written out
 * and read back ({@link #save}, {@link #load}), so it outlives the session, and so does the trial under way.
 */
final class CombatTuner {
    /**
     * PROVISIONAL — PENDING IN-GAME CALIBRATION. Every number that decides when a value is tried, kept or dropped.
     * They follow from the spread of damage dealt and taken per tick over the recorded windows of fights against
     * VexBot, replayed through these rules with the candidate's windows scaled by a known amount, not from a fight
     * the tuner itself has fought. None of them can take a value outside a tuned number's own hard bounds.
     */
    static final class Rules {
        /**
         * Windows fought under the trusted setting in a kit before the first trial. It shows the kit is fought at
         * all and that the foe's health is being shown. Lower starts trials on a fight or two, higher delays them.
         * To change it: how many windows a short-lived kit lives for in real play.
         */
        static final int WARMUP = 10;
        /**
         * Windows each value has fought at the first look; every later look needs twice the one before. At 10 a
         * difference has to be about half the rate to be seen, at 320 about a tenth. Lower judges on noise.
         * To change it: the spread of the rate in the kit at hand.
         */
        static final int LOOK = 10;
        /**
         * Looks before a trial is given up: 10, 20, 40, 80, 160 and 320 windows a side, over two hours of fighting
         * at the last. Few trials get that far: {@link #FUTILE} ends the ones that are going nowhere. More looks
         * find smaller gains and hold a candidate that is ahead longer.
         * To change it: how small a gain is worth two hours of trial.
         */
        static final int LOOKS = 6;
        /**
         * Standard errors by which the candidate must deal more, or take less, to be kept. There are two ways to be
         * kept and two looks to be kept at, and each is a chance for a fluke: at 2.75 a candidate that is no
         * different was kept in at most 0.9% of replayed trials on the recorded fights, depending on the kit, and
         * in at most 1.4% on the windows of the live runs. Lower keeps more flukes and, replayed, finds next to
         * nothing more: it is the guards and {@link #FUTILE} that end most trials of a real gain, not this.
         * To change it: the share of kept values that hold up when tried again.
         */
        static final double Z_KEEP = 2.75;
        /**
         * The first look, counted from 0, at which a candidate may be kept: 160 windows a side. Before it the guards
         * say little, since damage taken per tick varies from window to window by about its own mean (1.18 of it
         * in the live sword and shield runs, 0.85 in the older recordings). Replayed over the live windows, a
         * candidate that dealt and took a quarter more was kept in 26% of trials when keeps began at 40 windows a
         * side, in 14% at 80 and in 5% at 160, while a clean 15% more dealt was kept in 78%, 77% and 73%. The price
         * is time: a real gain is kept after some 470 windows of trial where it took 320. Lower learns a large gain
         * sooner and lets more bad trades through, higher leaves one look to keep at.
         * To change it: the replay on the kit at hand, costly candidates kept against clean ones and windows spent.
         */
        static final int KEEP_LOOK = 4;
        /**
         * Standard errors of more damage taken, or less dealt, at which the candidate is dropped. Lower than
         * {@link #Z_KEEP} on purpose: a wrong drop costs a trial, a wrong keep costs fights.
         * To change it: how often a value dropped here is kept when tried again.
         */
        static final double Z_DROP = 2.0;
        /**
         * The limit a kept value is held to for as long as it is trusted: shown, by {@link #Z_DROP} standard errors,
         * to take more than this share above its neighbour on the baseline's side, or to deal less than this share
         * under it, it gives its place back whatever else it shows. Lower takes back gains that cost a little,
         * higher lets one be traded for the other.
         * To change it: what a tenth more damage taken does to fights won, measured.
         */
        static final double GUARD = 0.10;
        /**
         * The share a value may be measured to cost and still be kept: what the setting takes above the baselines'
         * for a step that deals more, what it deals under theirs for a step that takes less, every step from them
         * counted as it was measured. It is half of {@link #GUARD} because the measure is rough: at 160 windows a
         * side damage taken is known to about 13% in the live runs, and a trial is too short to tell a tenth more
         * from a quarter more. Replayed with keeps from 40 windows a side, a candidate that dealt and took a quarter
         * more was kept in 21% of sword and shield trials when a measured tenth was let through and in 13% with
         * this, for six trials in a hundred fewer of a clean 15% more dealt kept; with {@link #KEEP_LOOK} as it is
         * now it is kept in 1% to 7%, and one that takes a tenth more for a quarter more dealt in 4% to 44%. It is
         * a test of what was measured, not of what is true. Lower refuses more of what truly costs nothing, higher
         * lets more through that costs too much.
         * To change it: the replay, costly candidates kept against clean ones refused.
         */
        static final double KEEP_GUARD = 0.05;
        /**
         * After each look but the last, the lead in standard errors (the more of damage dealt above the trusted
         * value's and damage taken below it) at or under which the candidate is given up as not likely to be shown
         * better. Without it every candidate that is no different is fought to the last look, which is most of
         * them: with it such a trial took 145 to 330 replayed windows, where five looks fought to the end took 340
         * to 650, and the windows saved pay for the sixth look. Lower holds on to candidates longer, higher gives up
         * real small gains early.
         * To change it: the replay of recorded fights, gains found against windows spent.
         */
        static final double[] FUTILE = {-0.5, 0.0, 0.5, 1.0, 1.5};
        /**
         * Windows under the trusted setting, once every number has been gone through with nothing kept, before they
         * are tried again. Lower spends more of the fighting on trials, higher is slower to notice that the foes
         * have changed. To change it: how fast a kept value goes stale in real play.
         */
        static final int REST = 400;
        /**
         * Times the rest doubles while the baselines stand and nothing is found against them: 400, 800, up to 6400
         * windows. Lower keeps asking, higher goes quiet sooner and is slower to see that the foes have changed.
         * To change it: how often a value dropped once is kept on a later asking.
         */
        static final int REST_DOUBLINGS = 4;
        /**
         * Windows each value must have fought in the openings of engagements, and in the rest of them, before the
         * two parts are compared apart. Under it the scatter inside a part is not known well enough to weigh by,
         * and all windows are taken together: the turns keep both values' shares of openings alike either way.
         * Lower trusts a scatter read off two or three windows, higher gives up the sharper comparison more often.
         * To change it: the replay's false keeps with engagements of few windows.
         */
        static final int APART = 4;

        private Rules() {}
    }

    /** What one value has fought in one part of the engagements, in the trial under way. */
    static final class Part {
        int windows;
        long active, wall;
        double dealt, taken;
        private double dd, da, aa, tt, ta;

        void add(CombatOutcome.Tally w) {
            double t = w.taken + w.out;
            windows++;
            active += w.active;
            wall += w.wall;
            dealt += w.dealt;
            taken += t;
            dd += w.dealt * w.dealt;
            da += w.dealt * w.active;
            aa += (double) w.active * w.active;
            tt += t * t;
            ta += t * w.active;
        }

        double rate(boolean ofDealt) {
            return active > 0 ? (ofDealt ? dealt : taken) / active : 0;
        }

        /** Variance of a rate that is a ratio of sums: the windows' scatter about the rate, over the ticks squared. */
        double var(boolean ofDealt) {
            if (windows < 2 || active == 0) return Double.POSITIVE_INFINITY;
            double rate = rate(ofDealt), xx = ofDealt ? dd : tt, xa = ofDealt ? da : ta;
            double scatter = Math.max(0, xx - 2 * rate * xa + rate * rate * aa);
            return scatter * windows / (windows - 1) / ((double) active * active);
        }

        void add(Part o) {
            windows += o.windows;
            active += o.active;
            wall += o.wall;
            dealt += o.dealt;
            taken += o.taken;
            dd += o.dd;
            da += o.da;
            aa += o.aa;
            tt += o.tt;
            ta += o.ta;
        }

        /** The sums as they stand, so a trial can be taken up where it stopped. */
        String write() {
            return windows + "," + active + "," + wall + "," + dealt + "," + taken + "," + dd + "," + da + "," + aa + "," + tt + "," + ta;
        }

        /** @throws IllegalArgumentException if these are not sums a part could hold */
        static Part read(String v) {
            String[] f = v.split(",", -1);
            if (f.length != 10) throw new IllegalArgumentException(v);
            Part p = new Part();
            p.windows = Integer.parseInt(f[0]);
            p.active = Long.parseLong(f[1]);
            p.wall = Long.parseLong(f[2]);
            double[] x = new double[7];
            for (int i = 0; i < x.length; i++) {
                x[i] = Double.parseDouble(f[3 + i]);
                if (!(x[i] >= 0) || Double.isInfinite(x[i])) throw new IllegalArgumentException(v);
            }
            if (p.windows < 0 || p.active < 0 || p.wall < 0 || p.windows == 0 && p.active > 0) throw new IllegalArgumentException(v);
            p.dealt = x[0];
            p.taken = x[1];
            p.dd = x[2];
            p.da = x[3];
            p.aa = x[4];
            p.tt = x[5];
            p.ta = x[6];
            return p;
        }
    }

    /** What one value has fought in the trial under way: the windows that opened an engagement, the rest, and all of them. */
    static final class Arm {
        final Part[] part = {new Part(), new Part()};
        final Part all = new Part();

        void add(CombatOutcome.Tally w) {
            part[w.seq == 0 ? 0 : 1].add(w);
            all.add(w);
        }

        void add(int h, Part p) {
            part[h].add(p);
            all.add(p);
        }
    }

    /**
     * The candidate's rate and the trusted value's, with the variance of each. Where both have fought at least
     * {@link Rules#APART} windows of each part, the parts are compared apart and weighed by the ticks fought in
     * them, so the gap between a fight's opening and the rest of it is not counted as scatter. Otherwise all
     * windows are taken together.
     */
    static final class Cmp {
        final double c, t, vc, vt;

        Cmp(Arm cand, Arm trusted, boolean ofDealt) {
            boolean apart = true;
            for (int h = 0; h < 2; h++) {
                if (cand.part[h].windows < Rules.APART || trusted.part[h].windows < Rules.APART) apart = false;
            }
            if (!apart) {
                c = cand.all.rate(ofDealt);
                t = trusted.all.rate(ofDealt);
                vc = cand.all.var(ofDealt);
                vt = trusted.all.var(ofDealt);
                return;
            }
            double total = cand.all.active + trusted.all.active, rc = 0, rt = 0, sc = 0, st = 0;
            for (int h = 0; h < 2; h++) {
                double w = (cand.part[h].active + trusted.part[h].active) / total;
                rc += w * cand.part[h].rate(ofDealt);
                rt += w * trusted.part[h].rate(ofDealt);
                sc += w * w * cand.part[h].var(ofDealt);
                st += w * w * trusted.part[h].var(ofDealt);
            }
            c = rc;
            t = rt;
            vc = sc;
            vt = st;
        }

        /** Standard errors by which the candidate's rate is above the trusted value's. */
        double z() {
            return CombatTuner.z(c, vc, t, vt);
        }

        /** The candidate's rate for each point of the trusted value's. */
        double ratio() {
            return t > 0 ? c / t : c > 0 ? Double.POSITIVE_INFINITY : 1;
        }

        double pct() {
            return t > 0 ? (c / t - 1) * 100 : 0;
        }
    }

    private static final int NONE = Integer.MIN_VALUE;
    static final String VERSION = "acs 1";

    /**
     * One kit and kind of foe at one lag band: the values trusted there, and the trial under way if there is one. A
     * band never fought in starts from the baselines like any other: what was learned at another latency is not
     * evidence for this one.
     */
    private final class Ctx {
        final int[] trusted = new int[knobs.size()];
        final boolean[] downTried = new boolean[knobs.size()], upTried = new boolean[knobs.size()];
        final int[] order; // the numbers this kit has a use for
        int at; // the place in order of the number taken up
        int candidate = NONE;
        final boolean[] turn = new boolean[2]; // the candidate's turn: for a window that opens an engagement, for any other
        int rest = Rules.WARMUP, restOf = Rules.WARMUP, look, kept, dropped, reverted, fruitless, idle;
        double cost = 1, gain = 1; // what the trusted setting takes and deals for each point of the baselines', as the steps to it measured
        Arm t = new Arm(), c = new Arm();
        String tKey = CombatPolicy.BASE, cKey = "", last = "watching";

        Ctx(String name) {
            String context = CombatOutcome.plain(name); // the lag band changes what is learned, not which numbers the kit has a use for
            int slash = context.lastIndexOf('/');
            List<String> kit = new ArrayList<>(java.util.Arrays.asList((slash < 0 ? context : context.substring(0, slash)).split("\\+")));
            if (slash >= 0) kit.add(context.substring(slash)); // the kind of foe, as "/player": a number can be for one kind alone
            List<Integer> use = new ArrayList<>();
            for (int k = 0; k < knobs.size(); k++) {
                trusted[k] = knobs.get(k).base;
                CombatPolicy.Tuned t = knobs.get(k);
                if ((t.kit == null || kit.contains(t.kit)) && (t.not == null || !kit.contains(t.not))) use.add(k);
            }
            order = use.stream().mapToInt(Integer::intValue).toArray();
        }

        int knob() {
            return order.length == 0 ? -1 : order[at];
        }

        boolean atBase() {
            for (int k = 0; k < trusted.length; k++) {
                if (trusted[k] != knobs.get(k).base) return false;
            }
            return true;
        }

        /** The setting's name with the number taken up at {@code v} and the others as trusted. */
        String key(int v) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < trusted.length; k++) {
                int x = k == knob() ? v : trusted[k];
                if (x != knobs.get(k).base) sb.append(sb.length() > 0 ? "," : "").append(knobs.get(k).name).append(':').append(x);
            }
            return sb.length() > 0 ? sb.toString() : CombatPolicy.BASE;
        }
    }

    private final List<CombatPolicy.Tuned> knobs;
    private final Consumer<String> sink;
    private final Map<String, Ctx> contexts = new LinkedHashMap<>();
    private boolean on, dirty, urgent;

    /**
     * @param knobs the numbers to move, in the order they are taken up; baselines, bounds and steps are read from
     *              them, and they are never written here
     * @param sink  where a verdict's line goes
     */
    CombatTuner(List<CombatPolicy.Tuned> knobs, Consumer<String> sink) {
        this.knobs = knobs;
        this.sink = sink;
    }

    /** With one number to move. */
    CombatTuner(CombatPolicy.Tuned knob, Consumer<String> sink) {
        this(List.of(knob), sink);
    }

    boolean on() {
        return on;
    }

    /**
     * Switching it off stops every trial where it stands; what was trusted is remembered for when it is on again,
     * and is watched for a warm-up's worth of windows before anything is tried against it.
     */
    void on(boolean on) {
        this.on = on;
        if (!on) contexts.values().forEach(s -> {
            abandon(s);
            if (s.rest < Rules.WARMUP) rest(s, Rules.WARMUP);
        });
    }

    /** Forgets everything learned: every kit is back at the baselines with no trial under way. */
    void clear() {
        contexts.clear();
        dirty = urgent = true;
    }

    /**
     * The value of one number the next window in this kit and kind of foe is to be fought under. Its baseline
     * while off.
     *
     * @param opening that window is the first of its engagement
     */
    int value(String context, int knob, boolean opening) {
        if (!on) return knobs.get(knob).base;
        Ctx s = ctx(context);
        return s.candidate != NONE && knob == s.knob() && s.turn[opening ? 0 : 1] ? s.candidate : s.trusted[knob];
    }

    /** The value of one number trusted for this kit and kind of foe. */
    int trusted(String context, int knob) {
        Ctx s = contexts.get(context);
        return s == null ? knobs.get(knob).base : s.trusted[knob];
    }

    /** A window has closed. Called by the observer for every one, whatever it holds. */
    void window(String context, String setting, CombatOutcome.Tally w, String end) {
        if (!on) return;
        // cut by a change of latency: fought partly in another band than the one it is booked to, whichever way it went
        if ("lag".equals(end)) return;
        Ctx s = ctx(context);
        dirty = true;
        if (w.unsynced > 0) { // the foe's health was not shown: nothing here can be judged, so nothing is tried
            if (s.candidate != NONE) verdict(context, s, "stop", "health not shown");
            abandon(s);
            rest(s, Rules.REST);
            return;
        }
        if (s.candidate == NONE) {
            if (setting.equals(s.tKey) && --s.rest <= 0) begin(s);
            return;
        }
        if (setting.equals(s.tKey)) s.t.add(w);
        else if (setting.equals(s.cKey)) s.c.add(w);
        else return; // fought under something else, a value set by hand say: neither side's
        judge(context, s);
        if (s.candidate != NONE) turns(s);
    }

    /** One line per kit and kind of foe seen while on. */
    List<String> report() {
        List<String> lines = new ArrayList<>();
        contexts.forEach((k, s) -> {
            String now;
            if (s.candidate == NONE) {
                now = s.order.length == 0 ? "nothing to tune in this kit" : s.rest + " windows to the next trial";
            } else {
                CombatPolicy.Tuned t = knobs.get(s.knob());
                Cmp d = new Cmp(s.c, s.t, true), a = new Cmp(s.c, s.t, false);
                now = String.format(Locale.ROOT, "trying %s %s against %s: %.1f against %.1f windows, dealt %+.1f%% (z %+.2f), taken %+.1f%% (z %+.2f)",
                        t.name, t.show(s.candidate), t.show(s.trusted[s.knob()]), eq(s.c), eq(s.t), d.pct(), d.z(), a.pct(), a.z());
            }
            lines.add(String.format(Locale.ROOT, "%s: trusted %s%s, %d kept, %d dropped, %d taken back; %s; last: %s", k, s.tKey,
                    s.atBase() ? "" : String.format(Locale.ROOT, " (takes %.2f, deals %.2f of the baselines')", s.cost, s.gain), s.kept, s.dropped, s.reverted, now, s.last));
        });
        return lines;
    }

    /**
     * What is going on for one kit and kind of foe, for show. Asking changes nothing: a kit not fought yet reads as
     * the baselines with the warm-up ahead of it, and is not remembered for having been asked about.
     */
    AcsStatus status(String context) {
        AcsStatus st = new AcsStatus();
        Ctx s = context == null ? null : contexts.get(context);
        Ctx like = s != null ? s : context == null ? null : new Ctx(context);
        st.context = context == null ? "" : context;
        st.looks = Rules.LOOKS;
        st.windowSize = CombatOutcome.Rules.WINDOW;
        st.knob = like != null ? like.knob() : knobs.isEmpty() ? -1 : 0;
        st.knobs = like == null ? knobs.size() : like.order.length;
        st.at = like == null ? 1 : like.at + 1;
        if (st.knob >= 0) {
            CombatPolicy.Tuned t = knobs.get(st.knob);
            st.name = t.name;
            st.base = t.base;
            st.min = t.min;
            st.max = t.max;
            st.step = t.step;
            st.per = t.per;
            st.trusted = st.candidate = like == null ? t.base : like.trusted[st.knob];
            st.value = on ? st.trusted : t.base;
        }
        if (s != null) {
            st.kept = s.kept;
            st.dropped = s.dropped;
            st.reverted = s.reverted;
            int colon = s.last.indexOf(':');
            st.last = s.kept + s.dropped + s.reverted == 0 ? "" : colon < 0 ? s.last : s.last.substring(0, colon);
        }
        if (!on) return st;
        st.phase = AcsStatus.Phase.WATCHING;
        st.rest = s == null ? Rules.WARMUP : s.rest;
        st.restOf = s == null ? Rules.WARMUP : s.restOf;
        if (s == null || s.candidate == NONE) return st;
        st.phase = AcsStatus.Phase.TRIAL;
        st.candidate = s.candidate;
        st.look = s.look + 1;
        st.fought = Math.min(eq(s.t), eq(s.c));
        st.needed = Rules.LOOK << s.look;
        Cmp d = new Cmp(s.c, s.t, true), a = new Cmp(s.c, s.t, false);
        st.dealtPct = d.pct();
        st.takenPct = a.pct();
        st.zDealt = d.z();
        st.zTaken = a.z();
        return st;
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
     * What has been learned, a line for each kit and kind of foe: the values trusted, what the steps to them cost
     * and gained, the counts, and how long the rest still is. A trial under way is not in it.
     */
    List<String> save() {
        List<String> lines = new ArrayList<>();
        lines.add(VERSION);
        contexts.forEach((name, s) -> {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "ctx=%s cost=%.4f gain=%.4f kept=%d dropped=%d reverted=%d fruitless=%d idle=%d rest=%d restOf=%d at=%s",
                    name, s.cost, s.gain, s.kept, s.dropped, s.reverted, s.fruitless, s.idle, Math.max(s.rest, 0), s.restOf, s.knob() < 0 ? "-" : knobs.get(s.knob()).name));
            for (int k : s.order) {
                CombatPolicy.Tuned t = knobs.get(k);
                sb.append(" knob=").append(t.name).append(':').append(t.base).append(':').append(s.trusted[k]).append(':')
                        .append(s.downTried[k] ? "d" : "").append(s.upTried[k] ? "u" : "").append('-');
            }
            if (s.candidate != NONE) {
                sb.append(" trial=").append(s.candidate).append(':').append(s.look);
                for (int h = 0; h < 2; h++) sb.append(" t").append(h).append('=').append(s.t.part[h].write()).append(" c").append(h).append('=').append(s.c.part[h].write());
            }
            lines.add(sb.toString());
        });
        return lines;
    }

    /**
     * Reads back what {@link #save} wrote, in place of whatever is held now. A line is taken whole or not at all:
     * one that names a value outside a number's bounds or off its steps, or a number whose baseline has changed
     * since or that is no longer there while trusted away from it, is left out, and its kit starts from the
     * baselines. Every kit read back is watched for a warm-up before anything is tried.
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
                if (!line.trim().equals(VERSION)) return 0;
                versioned = true;
                continue;
            }
            try {
                String name = null, at = null, trial = null;
                String[] sums = new String[4]; // the trial's: trusted and candidate, opening and later
                Ctx s = null;
                boolean ok = true;
                for (String tok : line.trim().split(" ")) {
                    int eq = tok.indexOf('=');
                    if (eq < 0) continue;
                    String key = tok.substring(0, eq), v = tok.substring(eq + 1);
                    if (key.equals("ctx")) {
                        name = v;
                        s = new Ctx(v);
                    } else if (s == null) {
                        ok = false;
                    } else if (key.equals("cost")) s.cost = Double.parseDouble(v);
                    else if (key.equals("gain")) s.gain = Double.parseDouble(v);
                    else if (key.equals("kept")) s.kept = Integer.parseInt(v);
                    else if (key.equals("dropped")) s.dropped = Integer.parseInt(v);
                    else if (key.equals("reverted")) s.reverted = Integer.parseInt(v);
                    else if (key.equals("fruitless")) s.fruitless = Math.max(0, Integer.parseInt(v));
                    else if (key.equals("idle")) s.idle = Math.max(0, Integer.parseInt(v));
                    else if (key.equals("rest")) s.rest = Integer.parseInt(v);
                    else if (key.equals("restOf")) s.restOf = Integer.parseInt(v);
                    else if (key.equals("at")) at = v;
                    else if (key.equals("trial")) trial = v;
                    else if (key.length() == 2 && (key.charAt(0) == 't' || key.charAt(0) == 'c') && (key.charAt(1) == '0' || key.charAt(1) == '1')) {
                        sums[(key.charAt(0) == 't' ? 0 : 2) + key.charAt(1) - '0'] = v;
                    } else if (key.equals("knob")) {
                        String[] f = v.split(":", -1);
                        int k = index(f[0]), base = Integer.parseInt(f[1]), trusted = Integer.parseInt(f[2]);
                        boolean used = false;
                        for (int o : s.order) used |= o == k;
                        if (k < 0 || !used) {
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
                if (!ok || s == null || !(s.cost > 0) || !(s.gain > 0) || Double.isInfinite(s.cost) || Double.isInfinite(s.gain)) continue;
                for (int i = 0; i < s.order.length; i++) {
                    if (knobs.get(s.order[i]).name.equals(at)) s.at = i;
                }
                if (s.atBase()) s.cost = s.gain = 1;
                s.idle = Math.min(s.idle, Math.max(0, s.order.length - 1));
                s.rest = Math.max(s.rest, Rules.WARMUP);
                s.restOf = Math.max(s.restOf, s.rest);
                s.tKey = s.key(s.knob() < 0 ? 0 : s.trusted[s.knob()]);
                s.last = "read back";
                resume(s, trial, sums);
                contexts.put(name, s);
                taken++;
            } catch (RuntimeException e) {
                // a line that cannot be read is a kit that starts from the baselines
            }
        }
        dirty = urgent = false;
        return taken;
    }

    /**
     * Takes up the trial that was under way when the line was written: a trial can need more fighting than a session
     * holds, and one that is thrown away each time is never finished. The sides were level when it stopped, to
     * within a window, and stay so. A trial the rules could not have begun is left out and the line stands without it.
     */
    private void resume(Ctx s, String trial, String[] sums) {
        if (trial == null || s.knob() < 0) return;
        try {
            int colon = trial.indexOf(':'), k = s.knob();
            int candidate = Integer.parseInt(trial.substring(0, colon)), look = Integer.parseInt(trial.substring(colon + 1));
            CombatPolicy.Tuned t = knobs.get(k);
            if (Math.abs(candidate - s.trusted[k]) != t.step || candidate < t.min || candidate > t.max || look < 0 || look >= Rules.LOOKS) return;
            Arm trusted = new Arm(), cand = new Arm();
            for (int h = 0; h < 2; h++) {
                trusted.add(h, Part.read(sums[h]));
                cand.add(h, Part.read(sums[2 + h]));
            }
            s.t = trusted;
            s.c = cand;
            s.candidate = candidate;
            s.look = look;
            s.cKey = s.key(candidate);
            turns(s);
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

    private Ctx ctx(String context) {
        return contexts.computeIfAbsent(context, Ctx::new);
    }

    private static void rest(Ctx s, int windows) {
        s.rest = s.restOf = windows;
    }

    private void abandon(Ctx s) {
        s.candidate = NONE;
        s.turn[0] = s.turn[1] = false;
        s.t = new Arm();
        s.c = new Arm();
        s.look = 0;
        s.tKey = s.key(s.knob() < 0 ? 0 : s.trusted[s.knob()]);
        s.cKey = "";
    }

    /**
     * Opens the trial of the next untried neighbour of the number taken up, down first. With none left the next
     * number is taken up, and once all have been gone through with nothing kept, rests and then opens them again.
     */
    private void begin(Ctx s) {
        abandon(s);
        if (s.order.length == 0) {
            rest(s, Rules.REST);
            return;
        }
        while (true) {
            int k = s.knob();
            CombatPolicy.Tuned t = knobs.get(k);
            int down = s.trusted[k] - t.step, up = s.trusted[k] + t.step;
            if (down < t.min) s.downTried[k] = true;
            if (up > t.max) s.upTried[k] = true;
            if (!s.downTried[k] || !s.upTried[k]) {
                s.candidate = !s.downTried[k] ? down : up;
                s.cKey = s.key(s.candidate);
                turns(s);
                return;
            }
            s.downTried[k] = s.upTried[k] = false;
            s.at = (s.at + 1) % s.order.length;
            s.tKey = s.key(s.trusted[s.knob()]);
            if (++s.idle >= s.order.length) {
                s.idle = 0;
                rest(s, s.atBase() ? Rules.REST << Math.min(s.fruitless++, Rules.REST_DOUBLINGS) : Rules.REST);
                return;
            }
        }
    }

    /** In each part of the engagements the side that has fought less goes next; level, they go trusted, candidate, candidate, trusted. */
    private static void turns(Ctx s) {
        for (int h = 0; h < 2; h++) {
            Part c = s.c.part[h], t = s.t.part[h];
            s.turn[h] = c.active != t.active ? c.active < t.active : (t.windows + c.windows) / 2 % 2 != 0;
        }
    }

    private void judge(String context, Ctx s) {
        if (Math.min(eq(s.t), eq(s.c)) < Rules.LOOK << s.look) return;
        Cmp d = new Cmp(s.c, s.t, true), a = new Cmp(s.c, s.t, false);
        double zd = d.z(), zt = a.z(), takes = a.ratio(), deals = d.ratio();
        CombatPolicy.Tuned t = knobs.get(s.knob());
        boolean back = Math.abs(s.candidate - t.base) < Math.abs(s.trusted[s.knob()] - t.base);
        double over = 1 + Rules.GUARD, under = 1 - Rules.GUARD;
        double most = 1 + Rules.KEEP_GUARD, least = 1 - Rules.KEEP_GUARD;
        boolean late = s.look >= Rules.KEEP_LOOK;
        // the limits a kept value is held to come first: whatever else the two show, it goes back
        if (back && z(a.t, a.vt, over * a.c, over * over * a.vc) >= Rules.Z_DROP) {
            s.reverted++;
            move(context, s, "revert", "trusted takes more", takes, deals);
        } else if (back && z(under * d.c, under * under * d.vc, d.t, d.vt) >= Rules.Z_DROP) {
            s.reverted++;
            move(context, s, "revert", "trusted deals less", takes, deals);
        } else if (zt >= Rules.Z_DROP) drop(context, s, "takes more");
        else if (zd <= -Rules.Z_DROP) drop(context, s, "deals less");
        else if (late && zd >= Rules.Z_KEEP && s.cost * takes <= most && s.c.all.dealt * s.t.all.wall >= s.t.all.dealt * s.c.all.wall) {
            s.kept++;
            move(context, s, "keep", "deals more", takes, deals);
        } else if (late && zt <= -Rules.Z_KEEP && s.gain * deals >= least && s.c.all.dealt * s.t.all.wall >= least * s.t.all.dealt * s.c.all.wall) {
            s.kept++;
            move(context, s, "keep", "takes less", takes, deals);
        } else if (s.look + 1 >= Rules.LOOKS || Math.max(zd, -zt) <= Rules.FUTILE[s.look]) drop(context, s, "not shown better");
        else s.look++;
    }

    /** The candidate becomes the trusted value. The one it replaces is not a candidate again until the number comes round again. */
    private void move(String context, Ctx s, String what, String why, double takes, double deals) {
        verdict(context, s, what, why);
        int k = s.knob();
        boolean down = s.candidate < s.trusted[k];
        s.trusted[k] = s.candidate;
        s.cost = s.atBase() ? 1 : s.cost * takes;
        s.gain = s.atBase() ? 1 : s.gain * deals;
        s.fruitless = 0;
        s.idle = 0;
        s.downTried[k] = !down;
        s.upTried[k] = down;
        begin(s);
    }

    private void drop(String context, Ctx s, String why) {
        verdict(context, s, "drop", why);
        s.dropped++;
        if (s.candidate < s.trusted[s.knob()]) s.downTried[s.knob()] = true;
        else s.upTried[s.knob()] = true;
        begin(s);
    }

    private void verdict(String context, Ctx s, String what, String why) {
        CombatPolicy.Tuned t = knobs.get(s.knob());
        Cmp d = new Cmp(s.c, s.t, true), a = new Cmp(s.c, s.t, false);
        s.last = String.format(Locale.ROOT, "%s %s %s against %s (%s): %.1f and %.1f windows, dealt %+.1f%% (z %+.2f), taken %+.1f%% (z %+.2f)",
                what, t.name.substring(t.name.lastIndexOf('.') + 1), t.show(s.candidate), t.show(s.trusted[s.knob()]), why, eq(s.c), eq(s.t), d.pct(), d.z(), a.pct(), a.z());
        sink.accept(String.format(Locale.ROOT, "acs-tune ctx=%s %s trusted=%d candidate=%d verdict=%s why=%s set=%s look=%d nT=%d nC=%d ticksT=%d ticksC=%d dealtT=%.5f dealtC=%.5f takenT=%.5f takenC=%.5f zDealt=%.2f zTaken=%.2f",
                context, t.name, s.trusted[s.knob()], s.candidate, what, why.replace(' ', '-'), s.tKey, s.look + 1, s.t.all.windows, s.c.all.windows, s.t.all.active, s.c.all.active,
                d.t, d.c, a.t, a.c, d.z(), a.z()));
        urgent = true;
    }

    /** Windows' worth of fighting a side has had: a window cut short counts for the part of it that was fought. */
    private static double eq(Arm a) {
        return (double) a.all.active / CombatOutcome.Rules.WINDOW;
    }

    /** Standard errors between two rates; 0 when either side's scatter is not known yet, which is no evidence either way. */
    private static double z(double a, double va, double b, double vb) {
        double se = Math.sqrt(va + vb);
        return se > 0 && Double.isFinite(se) ? (a - b) / se : 0;
    }
}
