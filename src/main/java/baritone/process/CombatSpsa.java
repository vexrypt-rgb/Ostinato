package baritone.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Ostinato: the {@code spsa} mode of the autotune ({@code pvpAutotuneMode spsa}). It moves every tuned number of a kit
 * at once instead of one at a time, judges whole fights by whether they were won, and only puts a new setting in
 * force after a sequential test on fresh fights shows it wins more than the one in force.
 * <p>
 * Tuning (Spall's simultaneous perturbation, as Stockfish's Fishtest runs it): for each kit and kind of foe it holds a
 * real-valued point {@code phi}, in units of each number's own step. Fights come in pairs. A pair draws a random sign for
 * every number and fights one fight at {@code phi + c} and one at {@code phi - c} (each rounded at random onto the
 * number's steps, inside its bounds), in a random order, back to back, so both meet the same foe in the same minutes.
 * The difference of the two fights' values, over each number's spread, is a gradient estimate for every number at once;
 * {@code phi} moves by {@code a_k} times it. A fight's value is 1 for a win, 0 for a death, 1/2 otherwise, plus
 * {@link Rules#MARGIN} times its score (what the foes lost less what we lost, in bars, held to -1..1) as a tie-break.
 * Every number of a fight is the same from its first tick to its last: nothing changes inside a fight, so one arm's
 * play cannot spill into the other's.
 * <p>
 * Confirming (a GSPRT on paired fights, as Fishtest confirms a tune): every {@link Rules#BATCH} pairs the point,
 * rounded, is the candidate. If it differs from the trusted setting, pairs are fought trusted against candidate (random
 * order) and the log-likelihood ratio of "the candidate is {@link Rules#DELTA} better" against "no better" is summed with
 * the pairs' own variance. The candidate is put in force when it crosses the upper bound and dropped at the lower one,
 * or after {@link Rules#MAX_CONFIRM} pairs. Tuning waits while a candidate is tested, and goes on from the same point
 * after either verdict.
 * <p>
 * A fight in which a number had no use (an apple line with no apples carried) does not move that number. A fight that is
 * judged for nothing ({@link CombatOutcome.Fight#unsynced} or {@code mixed}) is fought again under the same arm.
 * Nothing here reads or moves how the bot aims or clicks: those are not tuned numbers.
 */
final class CombatSpsa {
    /** Every number that decides how fast it moves and how sure it must be. */
    static final class Rules {
        /** Perturbation at the first pair, in steps; it decays by {@link #GAMMA} but never below one step. */
        static final double C0 = prop("c0", 3.0);
        static final double GAMMA = 0.101;
        /** Move of phi per unit of gradient at the first pair, in steps, and its decay (Spall's 0.602 and stability constant). */
        static final double A_FIRST = prop("a", 2.0);
        static final double ALPHA = 0.602;
        static final double A_STAB = 50;
        /** Weight of the fight's score, in bars, beside its result. */
        static final double MARGIN = prop("margin", 0.1);
        /** Pairs of tuning between two confirmations. */
        static final int BATCH = (int) prop("batch", 150);
        /** The candidate's advantage in fight value the test is sized for (0.08 = eight wins more in a hundred, all else even). Chosen with the race in CombatSpsaTest. */
        static final double DELTA = prop("delta", 0.08);
        /** False keep and false drop rates of the test: a wrong keep is tried again at the next confirmation, so a tenth is affordable. */
        static final double ALPHA_ERR = prop("alpha", 0.10), BETA_ERR = prop("beta", 0.20);
        /** Pairs before the test may stop, and after which it gives up. */
        static final int MIN_CONFIRM = 20, MAX_CONFIRM = (int) prop("maxConfirm", 500);

        /** A bench run may move any of these with -Dostinato.spsa.NAME=value; play leaves them as written here. */
        private static double prop(String name, double def) {
            String v = System.getProperty("ostinato.spsa." + name);
            try {
                return v == null ? def : Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private Rules() {}
    }

    enum Phase { TUNE, CONFIRM }

    private final List<CombatPolicy.Tuned> knobs;
    private final Consumer<String> sink;
    private final Random rng;
    private final Map<String, Ctx> contexts = new LinkedHashMap<>();
    private boolean on, dirty, urgent;

    /** One kit and kind of foe at one lag band. */
    final class Ctx {
        final double[] phi = new double[knobs.size()];
        final int[] trusted = new int[knobs.size()], candidate = new int[knobs.size()];
        final int[] plus = new int[knobs.size()], minus = new int[knobs.size()];
        final int[] sign = new int[knobs.size()];
        final boolean[] use;
        Phase phase = Phase.TUNE;
        int k, sincePair; // pairs of tuning done, pairs since the last confirmation
        int armFirst; // which arm the pair's first fight is: +1 plus (or candidate), -1 minus (or trusted)
        int done; // fights of the pair under way that are in: 0 or 1
        double firstValue;
        String firstCarried = "";
        int cn; // confirmation pairs
        double cs, cs2; // their differences, candidate less trusted, summed and squared
        int kept, dropped, tests;
        String last = "tuning";

        Ctx(String name) {
            String plain = CombatOutcome.plain(name);
            int slash = plain.lastIndexOf('/');
            List<String> kit = new ArrayList<>(java.util.Arrays.asList((slash < 0 ? plain : plain.substring(0, slash)).split("\\+")));
            if (slash >= 0) kit.add(plain.substring(slash));
            use = new boolean[knobs.size()];
            for (int i = 0; i < knobs.size(); i++) {
                CombatPolicy.Tuned t = knobs.get(i);
                trusted[i] = t.base;
                phi[i] = 0; // in steps from the baseline: the values worth telling apart are base + n * step
                // a number fought whole under may name what is carried (apple, heal, totem, pearl): the fight tells whether it was
                use[i] = t.whole ? t.kit == null || kit.contains(t.kit) || CARRIED.contains(t.kit)
                        : (t.kit == null || kit.contains(t.kit)) && (t.not == null || !kit.contains(t.not));
            }
            newPair();
        }

        void newPair() {
            done = 0;
            armFirst = rng.nextBoolean() ? 1 : -1;
            if (phase == Phase.CONFIRM) return;
            double c = Math.max(1.0, Rules.C0 / Math.pow(k + 1, Rules.GAMMA));
            for (int i = 0; i < knobs.size(); i++) {
                if (!use[i]) {
                    plus[i] = minus[i] = trusted[i];
                    continue;
                }
                sign[i] = rng.nextBoolean() ? 1 : -1;
                plus[i] = onGrid(i, phi[i] + c * sign[i]);
                minus[i] = onGrid(i, phi[i] - c * sign[i]);
            }
        }

        /** The arm the next fight is fought under: +1 plus or candidate, -1 minus or trusted. */
        int arm() {
            return done == 0 ? armFirst : -armFirst;
        }

        int value(int i) {
            if (phase == Phase.CONFIRM) return arm() > 0 ? candidate[i] : trusted[i];
            return arm() > 0 ? plus[i] : minus[i];
        }
    }

    /** What a whole-fight number's kit can name that is carried rather than held: these are known only from the fight. */
    private static final List<String> CARRIED = List.of("apple", "heal", "totem", "pearl");

    /**
     * @param knobs every tuned number the policy has, of both kinds; bounds and steps are read from them, never written
     * @param sink  where a verdict's line goes
     */
    CombatSpsa(List<CombatPolicy.Tuned> knobs, Consumer<String> sink, Random rng) {
        this.knobs = knobs;
        this.sink = sink;
        this.rng = rng;
    }

    boolean on() {
        return on;
    }

    void on(boolean on) {
        if (this.on == on) return;
        this.on = on;
        contexts.values().forEach(Ctx::newPair); // a pair cut by switching is started again
    }

    void clear() {
        contexts.clear();
        dirty = urgent = true;
    }

    /** The value of one number for the next fight in this context; its baseline while off. Asked between fights only. */
    int value(String context, int knob) {
        if (!on) return knobs.get(knob).base;
        return ctx(context).value(knob);
    }

    int trusted(String context, int knob) {
        Ctx s = contexts.get(context);
        return s == null ? knobs.get(knob).base : s.trusted[knob];
    }

    Ctx peek(String context) {
        return contexts.get(context);
    }

    /** The value of a fight: its result first, its score as a tie-break. */
    static double value(CombatOutcome.Fight f) {
        double r = "win".equals(f.end) ? 1 : f.death() ? 0 : 0.5;
        return r + Rules.MARGIN * Math.max(-1, Math.min(1, f.score()));
    }

    /** A fight has ended; it was fought under the arm {@link #value} named for its context. */
    void fight(CombatOutcome.Fight f) {
        if (!on || f == null || f.context == null) return;
        if (f.unsynced || f.mixed) return; // judged for nothing: the same arm is fought again
        Ctx s = ctx(f.context);
        dirty = true;
        double y = value(f);
        if (s.done == 0) {
            s.firstValue = y;
            s.firstCarried = f.carried == null ? "" : f.carried;
            s.done = 1;
            return;
        }
        String carried = f.carried == null ? "" : f.carried;
        // the difference of the arm +1 less the arm -1
        double d = s.armFirst > 0 ? s.firstValue - y : y - s.firstValue;
        if (s.phase == Phase.TUNE) step(f.context, s, d, carried);
        else confirm(f.context, s, d);
        s.newPair();
    }

    private void step(String context, Ctx s, double d, String carried) {
        double a = Rules.A_FIRST * Math.pow(Rules.A_STAB + 1, Rules.ALPHA) / Math.pow(Rules.A_STAB + s.k + 1, Rules.ALPHA);
        for (int i = 0; i < knobs.size(); i++) {
            if (!s.use[i]) continue;
            CombatPolicy.Tuned t = knobs.get(i);
            if (t.whole && t.kit != null && CARRIED.contains(t.kit) && !(carried.contains(t.kit) && s.firstCarried.contains(t.kit))) continue; // no use in both fights
            int spread = (s.plus[i] - s.minus[i]) / t.step; // in steps; 0 when both arms rounded or clipped alike
            if (spread == 0) continue;
            double g = d / spread; // per step, in fight value
            s.phi[i] = clamp(i, s.phi[i] + a * g);
        }
        s.k++;
        if (++s.sincePair >= Rules.BATCH) {
            s.sincePair = 0;
            boolean differs = false;
            for (int i = 0; i < knobs.size(); i++) {
                s.candidate[i] = s.use[i] ? knobs.get(i).base + (int) Math.round(s.phi[i]) * knobs.get(i).step : s.trusted[i];
                differs |= s.candidate[i] != s.trusted[i];
            }
            if (differs) {
                s.phase = Phase.CONFIRM;
                s.cn = 0;
                s.cs = s.cs2 = 0;
                s.tests++;
                s.last = "testing " + describe(s.candidate, s.trusted);
                urgent = true;
            }
        }
    }

    /** Log-likelihood ratio of "better by DELTA" against "no better", on paired differences with their own variance. */
    static double llr(int n, double sum, double sum2) {
        if (n < 2) return 0;
        double mean = sum / n, var = Math.max(1e-6, (sum2 - n * mean * mean) / (n - 1));
        return Rules.DELTA / var * (sum - n * Rules.DELTA / 2);
    }

    static double upper() {
        return Math.log((1 - Rules.BETA_ERR) / Rules.ALPHA_ERR);
    }

    static double lower() {
        return Math.log(Rules.BETA_ERR / (1 - Rules.ALPHA_ERR));
    }

    private void confirm(String context, Ctx s, double d) {
        s.cn++;
        s.cs += d;
        s.cs2 += d * d;
        if (s.cn < Rules.MIN_CONFIRM) return;
        double l = llr(s.cn, s.cs, s.cs2);
        String verdict = l >= upper() ? "keep" : l <= lower() ? "drop" : s.cn >= Rules.MAX_CONFIRM ? "drop" : null;
        if (verdict == null) return;
        String what = describe(s.candidate, s.trusted);
        if (verdict.equals("keep")) {
            System.arraycopy(s.candidate, 0, s.trusted, 0, s.trusted.length);
            s.kept++;
        } else {
            s.dropped++;
        }
        s.last = String.format(Locale.ROOT, "%s %s: %d pairs, %+.3f a pair, llr %.2f", verdict, what, s.cn, s.cs / s.cn, l);
        sink.accept(String.format(Locale.ROOT, "acs-spsa ctx=%s verdict=%s pairs=%d mean=%.4f llr=%.3f k=%d set=%s", context, verdict, s.cn,
                s.cs / s.cn, l, s.k, what.replace(' ', '_')));
        s.phase = Phase.TUNE;
        urgent = true;
    }

    private String describe(int[] v, int[] against) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (v[i] == against[i]) continue;
            CombatPolicy.Tuned t = knobs.get(i);
            sb.append(sb.length() > 0 ? "," : "").append(t.name).append(':').append(t.show(against[i])).append('>').append(t.show(v[i]));
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    /** Onto the number's steps, rounding at random so that the expected value is the point itself; inside its bounds. */
    private int onGrid(int i, double phiValue) {
        CombatPolicy.Tuned t = knobs.get(i);
        double x = clamp(i, phiValue);
        double fl = Math.floor(x);
        int steps = (int) (rng.nextDouble() < x - fl ? fl + 1 : fl);
        return t.base + steps * t.step;
    }

    private double clamp(int i, double phiValue) {
        CombatPolicy.Tuned t = knobs.get(i);
        // the grid points base + n * step that lie inside the bounds
        double lo = -Math.floor((double) (t.base - t.min) / t.step), hi = Math.floor((double) (t.max - t.base) / t.step);
        return Math.max(lo, Math.min(hi, phiValue));
    }

    private Ctx ctx(String context) {
        return contexts.computeIfAbsent(context, Ctx::new);
    }

    boolean dirty() {
        return dirty;
    }

    boolean urgent() {
        return urgent;
    }

    void saved() {
        dirty = urgent = false;
    }

    List<String> report() {
        List<String> lines = new ArrayList<>();
        contexts.forEach((name, s) -> lines.add(String.format(Locale.ROOT, "%s: spsa %s, %d pairs, %d tests, %d kept, %d dropped; trusted %s; last: %s",
                name, s.phase == Phase.TUNE ? "tuning" : "testing (" + s.cn + " pairs, llr " + String.format(Locale.ROOT, "%.2f", llr(s.cn, s.cs, s.cs2)) + ")",
                s.k, s.tests, s.kept, s.dropped, describe(s.trusted, baselines()), s.last)));
        return lines;
    }

    private int[] baselines() {
        int[] b = new int[knobs.size()];
        for (int i = 0; i < b.length; i++) b[i] = knobs.get(i).base;
        return b;
    }

    static final String VERSION = "spsa 1";

    /**
     * What is learned: point, trusted values, counts, and a test under way with its pairs so far (Ostinato: a restart
     * used to drop it back to tuning, and the next test came 150 pairs later; a 117-pair mace test was lost so).
     */
    List<String> save() {
        List<String> lines = new ArrayList<>();
        lines.add(VERSION);
        contexts.forEach((name, s) -> {
            StringBuilder sb = new StringBuilder("sctx=").append(name).append(" k=").append(s.k).append(" since=").append(s.sincePair)
                    .append(" kept=").append(s.kept).append(" dropped=").append(s.dropped).append(" tests=").append(s.tests);
            if (s.phase == Phase.CONFIRM) sb.append(" test=").append(s.cn).append(':').append(String.format(Locale.ROOT, "%.6f:%.6f", s.cs, s.cs2));
            for (int i = 0; i < knobs.size(); i++) {
                CombatPolicy.Tuned t = knobs.get(i);
                sb.append(" sk=").append(t.name).append(':').append(t.base).append(':').append(s.trusted[i]).append(':')
                        .append(String.format(Locale.ROOT, "%.4f", s.phi[i])).append(':').append(s.candidate[i]);
            }
            lines.add(sb.toString());
        });
        return lines;
    }

    /** Reads what {@link #save} wrote among other lines; a context line that does not fit the numbers as they are now is left out. */
    int load(Iterable<String> lines) {
        contexts.clear();
        int taken = 0;
        boolean in = false;
        for (String line : lines) {
            String l = line.trim();
            if (l.equals(VERSION)) {
                in = true;
                continue;
            }
            if (!in || !l.startsWith("sctx=")) continue;
            try {
                Ctx s = null;
                String name = null;
                boolean ok = true;
                for (String tok : l.split(" ")) {
                    int eq = tok.indexOf('=');
                    if (eq < 0) continue;
                    String key = tok.substring(0, eq), v = tok.substring(eq + 1);
                    switch (key) {
                        case "sctx" -> {
                            name = v;
                            s = new Ctx(v);
                        }
                        case "k" -> s.k = Integer.parseInt(v);
                        case "since" -> s.sincePair = Integer.parseInt(v);
                        case "kept" -> s.kept = Integer.parseInt(v);
                        case "dropped" -> s.dropped = Integer.parseInt(v);
                        case "tests" -> s.tests = Integer.parseInt(v);
                        case "test" -> { // Ostinato: a GSPRT test under way when the file was saved
                            String[] f = v.split(":");
                            s.phase = Phase.CONFIRM;
                            s.cn = Integer.parseInt(f[0]);
                            s.cs = Double.parseDouble(f[1]);
                            s.cs2 = Double.parseDouble(f[2]);
                        }
                        case "sk" -> {
                            String[] f = v.split(":");
                            int i = index(f[0]);
                            if (i < 0) continue;
                            CombatPolicy.Tuned t = knobs.get(i);
                            int base = Integer.parseInt(f[1]), tr = Integer.parseInt(f[2]);
                            double p = Double.parseDouble(f[3]);
                            if (base != t.base || tr < t.min || tr > t.max || (tr - t.base) % t.step != 0 || !Double.isFinite(p)) ok = false;
                            else {
                                s.trusted[i] = tr;
                                s.phi[i] = clamp(i, p);
                                s.candidate[i] = f.length > 4 ? Integer.parseInt(f[4]) : tr; // Ostinato: older files carry no candidate
                            }
                        }
                        default -> {}
                    }
                }
                if (!ok || s == null) continue;
                if (s.phase == Phase.CONFIRM) {
                    boolean differs = false;
                    for (int i = 0; i < knobs.size(); i++) {
                        CombatPolicy.Tuned t = knobs.get(i);
                        if (s.candidate[i] < t.min || s.candidate[i] > t.max) ok = false;
                        differs |= s.candidate[i] != s.trusted[i];
                    }
                    if (!ok || !differs) { // a test that does not fit the numbers as they are now starts from tuning
                        s.phase = Phase.TUNE;
                        s.cn = 0;
                        s.cs = s.cs2 = 0;
                        ok = true;
                    } else s.last = "testing " + describe(s.candidate, s.trusted) + " (resumed)";
                }
                s.newPair();
                contexts.put(name, s);
                taken++;
            } catch (RuntimeException e) {
                // a line that cannot be read is a context that starts from the baselines
            }
        }
        dirty = urgent = false;
        return taken;
    }

    private int index(String name) {
        for (int i = 0; i < knobs.size(); i++) if (knobs.get(i).name.equals(name)) return i;
        return -1;
    }
}
