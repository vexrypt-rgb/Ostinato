package baritone.process;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the spsa mode of the autotune. Unit checks, and (with -Postinato_bench) a race on made-up fights between it and
 * the per-fight learner the coordinate mode uses, on the same landscape of win chances.
 */
public class CombatSpsaTest {
    private static final String CTX = "sword+shield/player";

    private static List<CombatPolicy.Tuned> all(CombatPolicy p) {
        List<CombatPolicy.Tuned> k = new ArrayList<>(p.tuned);
        k.addAll(p.whole);
        return k;
    }

    private static CombatOutcome.Fight fight(String ctx, boolean win, double score, String carried) {
        CombatOutcome.Fight f = new CombatOutcome.Fight();
        f.context = ctx;
        f.stance = CombatPolicy.BASE;
        f.carried = carried;
        f.end = win ? "win" : "death";
        f.wall = f.active = 400;
        f.foes = Math.max(score, 0);
        f.mine = Math.max(-score, 0);
        return f;
    }

    @Test
    public void offIsTheBaselines() {
        CombatPolicy p = new CombatPolicy();
        List<CombatPolicy.Tuned> k = all(p);
        CombatSpsa s = new CombatSpsa(k, l -> {}, new Random(1));
        for (int i = 0; i < k.size(); i++) assertEquals(k.get(i).base, s.value(CTX, i));
    }

    @Test
    public void armsStayOnTheGridInsideBoundsAndDifferOnlyWhereUsed() {
        CombatPolicy p = new CombatPolicy();
        List<CombatPolicy.Tuned> k = all(p);
        CombatSpsa s = new CombatSpsa(k, l -> {}, new Random(2));
        s.on(true);
        boolean anyDiff = false;
        for (int n = 0; n < 200; n++) {
            int[] a = new int[k.size()];
            for (int i = 0; i < k.size(); i++) {
                CombatPolicy.Tuned t = k.get(i);
                a[i] = s.value(CTX, i);
                assertTrue(t.name, a[i] >= t.min && a[i] <= t.max && (a[i] - t.base) % t.step == 0);
                if (t.kit != null && t.kit.equals("mace")) assertEquals("mace numbers have no use with a sword", t.base, a[i]);
            }
            s.fight(fight(CTX, n % 3 == 0, 0, "apple"));
            for (int i = 0; i < k.size(); i++) anyDiff |= s.value(CTX, i) != a[i];
        }
        assertTrue(anyDiff);
    }

    @Test
    public void llrBoundsAreTheWaldOnes() {
        assertEquals(Math.log(0.8 / 0.1), CombatSpsa.upper(), 1e-9);
        assertEquals(Math.log(0.2 / 0.9), CombatSpsa.lower(), 1e-9);
        assertTrue(CombatSpsa.llr(100, 100 * 0.1, 100 * (0.1 * 0.1 + 0.25)) > 0);
        assertTrue(CombatSpsa.llr(100, 0, 100 * 0.25) < 0);
    }

    @Test
    public void saveAndLoadKeepThePoint() {
        CombatPolicy p = new CombatPolicy();
        List<CombatPolicy.Tuned> k = all(p);
        CombatSpsa s = new CombatSpsa(k, l -> {}, new Random(3));
        s.on(true);
        Random r = new Random(4);
        for (int n = 0; n < 50; n++) s.fight(fight(CTX, r.nextBoolean(), r.nextGaussian(), "apple"));
        List<String> saved = s.save();
        CombatSpsa t = new CombatSpsa(k, l -> {}, new Random(3));
        assertEquals(1, t.load(saved));
        assertEquals(saved, t.save());
    }

    /** Ostinato: a test under way survives a save and load with its pairs, instead of falling back to tuning. */
    @Test
    public void saveAndLoadKeepATestUnderWay() {
        CombatPolicy p = new CombatPolicy();
        List<CombatPolicy.Tuned> k = all(p);
        CombatSpsa s = new CombatSpsa(k, l -> {}, new Random(5));
        s.on(true);
        Random r = new Random(6);
        int n = 0;
        while ((s.report().isEmpty() || !s.report().get(0).contains("testing (")) && n++ < 5000) s.fight(fight(CTX, r.nextBoolean(), r.nextGaussian(), "apple"));
        assertTrue("a test began", s.report().get(0).contains("testing ("));
        for (int i = 0; i < 10; i++) s.fight(fight(CTX, i % 2 == 0, 0, "apple")); // five pairs, too few for a verdict
        List<String> saved = s.save();
        assertTrue(saved.get(1).contains(" test="));
        CombatSpsa t = new CombatSpsa(k, l -> {}, new Random(5));
        assertEquals(1, t.load(saved));
        assertEquals(saved, t.save());
        String before = s.report().get(0), after = t.report().get(0);
        assertEquals(before.substring(0, before.indexOf(')')), after.substring(0, after.indexOf(')')));
    }

    /** Win chance of a fight under the values: a hill whose top is {@code off} steps from the baselines on a few numbers. */
    private static final class World {
        final List<CombatPolicy.Tuned> k;
        final double base, gain;
        final int[] relevant, off;
        final double width;

        World(List<CombatPolicy.Tuned> k, double base, double gain, String[] names, int[] off, double width) {
            this.k = k;
            this.base = base;
            this.gain = gain;
            this.relevant = new int[names.length];
            for (int j = 0; j < names.length; j++) {
                int f = -1;
                for (int i = 0; i < k.size(); i++) if (k.get(i).name.equals(names[j])) f = i;
                if (f < 0) throw new IllegalArgumentException(names[j]);
                relevant[j] = f;
            }
            this.off = off;
            this.width = width;
        }

        /** p at the values: base at the baselines when the top is elsewhere, base + gain at the top. */
        double p(int[] v) {
            double dist = 0, distBase = 0;
            for (int j = 0; j < relevant.length; j++) {
                CombatPolicy.Tuned t = k.get(relevant[j]);
                double x = (double) (v[relevant[j]] - t.base) / t.step - off[j];
                dist += x * x;
                distBase += (double) off[j] * off[j];
            }
            double hill = Math.exp(-dist / (2 * width * width)), hillBase = Math.exp(-distBase / (2 * width * width));
            double top = base + gain * (1 - hillBase) / Math.max(1e-9, 1 - hillBase); // keeps p(base) = base
            return Math.max(0.02, Math.min(0.98, base - gain * hillBase / Math.max(1e-9, 1 - hillBase) * 0 + gain * (hill - hillBase) / Math.max(1e-9, 1 - hillBase) + 0 * top));
        }

        /** Win rates by VexBot style in the 2026-10-12 bench (vexbench.csv, sword against perfect): safe, adaptive, balanced, default, aggressive, defensive. */
        static final double[] CELLS = {31 / 67.0, 27 / 67.0, 56 / 67.0, 38 / 67.0, 45 / 66.0, 32 / 66.0};
        boolean cells;

        CombatOutcome.Fight fight(Random r, int[] v, int cell) {
            double q = cells ? Math.max(0.02, Math.min(0.98, CELLS[cell % CELLS.length] + p(v) - base)) : p(v);
            boolean win = r.nextDouble() < q;
            return CombatSpsaTest.fight(CTX, win, (win ? 0.6 : -0.6) + 0.4 * r.nextGaussian(), "apple");
        }
    }

    private static int[] values(CombatSpsa s, int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = s.value(CTX, i);
        return v;
    }

    private static int[] trusted(CombatSpsa s, int n) {
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = s.trusted(CTX, i);
        return v;
    }

    /** True win chance of what spsa trusts after each checkpoint, for one world and seed. */
    private static double[] runSpsa(World w, long seed, int[] checkpoints) {
        CombatSpsa s = new CombatSpsa(w.k, l -> {}, new Random(seed));
        s.on(true);
        Random r = new Random(seed * 31 + 7);
        double[] out = new double[checkpoints.length];
        int c = 0;
        for (int n = 1; n <= checkpoints[checkpoints.length - 1]; n++) {
            s.fight(w.fight(r, values(s, w.k.size()), (n - 1) / 2)); // a pair meets one cell
            if (n == checkpoints[c]) out[c++] = w.p(trusted(s, w.k.size()));
        }
        return out;
    }

    /** The same for the coordinate mode's per-fight learner, which moves only the numbers a fight is fought whole under. */
    private static double[] runCoordinate(World w, CombatPolicy p, long seed, int[] checkpoints) {
        CombatFightTuner t = new CombatFightTuner(p.whole, l -> {}, new Random(seed));
        t.on(true);
        Random r = new Random(seed * 31 + 7);
        int nt = p.tuned.size();
        double[] out = new double[checkpoints.length];
        int c = 0;
        int[] v = new int[w.k.size()];
        for (int i = 0; i < nt; i++) v[i] = p.tuned.get(i).base;
        for (int n = 1; n <= checkpoints[checkpoints.length - 1]; n++) {
            for (int i = 0; i < p.whole.size(); i++) {
                p.whole.get(i).set(t.value(CTX, i));
                v[nt + i] = p.whole.get(i).get();
            }
            CombatOutcome.Fight f = w.fight(r, v, n - 1); // the bench's own order: a new cell every round
            f.stance = p.stance();
            t.fight(f);
            if (n == checkpoints[c]) {
                int[] tr = v.clone();
                for (int i = 0; i < p.whole.size(); i++) tr[nt + i] = t.trusted(CTX, i);
                out[c++] = w.p(tr);
            }
        }
        p.whole.forEach(CombatPolicy.Tuned::reset);
        return out;
    }

    @Test
    public void raceOnMadeUpFights() {
        Assume.assumeTrue(Boolean.getBoolean("ostinato.bench"));
        int[] cps = {1000, 2000, 4000, 8000};
        int seeds = Integer.getInteger("ostinato.race.seeds", 40);
        CombatPolicy p = new CombatPolicy();
        List<CombatPolicy.Tuned> k = all(p);
        String[][] scenes = {
                // a gain of ten points of win chance two steps off on three whole-fight numbers (both learners can reach it)
                {"whole3", "heal.eatNear,heal.eatFar,heal.eatSafe", "2,-2,2"},
                // the same on two per-window numbers and one whole-fight number (only spsa moves per-window numbers per fight)
                {"mixed3", "shield.holdCharge,melee.spaceCharge,heal.eatNear", "2,-2,2"},
                // nothing to find: the baselines are the top (any keep is a loss or a fluke)
                {"null", "heal.eatNear,heal.eatFar,shield.holdCharge", "0,0,0"},
        };
        for (String[] sc0 : scenes) for (boolean cells : new boolean[]{false, true}) {
            String[] sc = sc0;
            String[] names = sc[1].split(",");
            int[] off = java.util.Arrays.stream(sc[2].split(",")).mapToInt(Integer::parseInt).toArray();
            World w = new World(k, 0.5, 0.1, names, off, Double.parseDouble(System.getProperty("ostinato.race.width", "1.5")));
            w.cells = cells;
            double[] sp = new double[cps.length], co = new double[cps.length];
            int spWorse = 0, coWorse = 0;
            for (long seed = 1; seed <= seeds; seed++) {
                double[] a = runSpsa(w, seed, cps), b = runCoordinate(w, p, seed, cps);
                for (int c = 0; c < cps.length; c++) {
                    sp[c] += a[c] / seeds;
                    co[c] += b[c] / seeds;
                }
                if (a[cps.length - 1] < 0.5 - 1e-9) spWorse++;
                if (b[cps.length - 1] < 0.5 - 1e-9) coWorse++;
            }
            StringBuilder sb = new StringBuilder("SPSA-RACE " + sc[0] + (cells ? "+cells" : "") + " seeds=" + seeds);
            for (int c = 0; c < cps.length; c++)
                sb.append(String.format(Locale.ROOT, " | %d fights (%.1f h at 112/h): spsa %.3f coord %.3f", cps[c], cps[c] / 112.0, sp[c], co[c]));
            sb.append(" | ended below baseline: spsa ").append(spWorse).append('/').append(seeds).append(", coord ").append(coWorse).append('/').append(seeds);
            System.out.println(sb);
        }
    }
}
