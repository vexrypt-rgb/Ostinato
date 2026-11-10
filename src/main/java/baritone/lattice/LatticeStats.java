/*
 * Ostinato: review-fix statistics for the lattice experiment. Not part of upstream Baritone.
 * Pure arithmetic over LatticeBench rows. Production pathing does not call this class.
 */
package baritone.lattice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Wilson intervals, Fisher's exact test, nearest-rank percentiles, and a seeded paired bootstrap. */
public final class LatticeStats {
    static final long BOOTSTRAP_SEED = 0x0A57_1A70L;
    static final int BOOTSTRAP_RESAMPLES = 10_000;
    private static final double Z95 = 1.959963984540054;

    private LatticeStats() {
    }

    /** 95% Wilson score interval for {@code k} successes in {@code n}. */
    public static double[] wilson(int k, int n) {
        if (n == 0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double p = k / (double) n;
        double z2 = Z95 * Z95;
        double denom = 1 + z2 / n;
        double centre = (p + z2 / (2 * n)) / denom;
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    /** Two-sided Fisher exact p for the table [[a, b], [c, d]], summing tables no more likely than the observed one. */
    public static double fisherTwoSided(int a, int b, int c, int d) {
        int row1 = a + b;
        int col1 = a + c;
        int n = a + b + c + d;
        double observed = hypergeometric(a, row1, col1, n);
        double p = 0;
        int lo = Math.max(0, row1 + col1 - n);
        int hi = Math.min(row1, col1);
        for (int x = lo; x <= hi; x++) {
            double px = hypergeometric(x, row1, col1, n);
            if (px <= observed * (1 + 1e-9)) {
                p += px;
            }
        }
        return Math.min(1, p);
    }

    private static double hypergeometric(int x, int row1, int col1, int n) {
        return Math.exp(logChoose(col1, x) + logChoose(n - col1, row1 - x) - logChoose(n, row1));
    }

    private static double logChoose(int n, int k) {
        double s = 0;
        for (int i = 1; i <= k; i++) {
            s += Math.log(n - k + i) - Math.log(i);
        }
        return s;
    }

    /** Nearest-rank percentile, the same rule as PathBench. */
    public static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int i = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** Seeded paired bootstrap 95% interval of median(a) / median(b) over cases. */
    public static double[] pairedMedianRatio(List<Integer> a, List<Integer> b) {
        int n = a.size();
        if (n == 0 || n != b.size()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        Random random = new Random(BOOTSTRAP_SEED);
        double[] ratios = new double[BOOTSTRAP_RESAMPLES];
        List<Double> ra = new ArrayList<>(n);
        List<Double> rb = new ArrayList<>(n);
        for (int r = 0; r < BOOTSTRAP_RESAMPLES; r++) {
            ra.clear();
            rb.clear();
            for (int i = 0; i < n; i++) {
                int j = random.nextInt(n);
                ra.add((double) a.get(j));
                rb.add((double) b.get(j));
            }
            ratios[r] = median(ra) / median(rb);
        }
        java.util.Arrays.sort(ratios);
        return new double[]{ratios[(int) (0.025 * BOOTSTRAP_RESAMPLES)], ratios[(int) (0.975 * BOOTSTRAP_RESAMPLES) - 1]};
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** The statistics block appended to the Phase 03 summary. */
    static String block(List<LatticeBench.Trial> trials) {
        StringBuilder text = new StringBuilder();
        text.append("statistics: Wilson 95% intervals; Fisher exact two-sided; nearest-rank latency percentiles pooled over every trial; ")
                .append("paired bootstrap ").append(BOOTSTRAP_RESAMPLES).append(" resamples, seed 0x")
                .append(Long.toHexString(BOOTSTRAP_SEED)).append('\n');
        for (String split : List.of("dev", "held")) {
            List<String> ids = ids(trials, split);
            if (ids.isEmpty()) {
                continue;
            }
            for (String method : List.of("beam", "weighted", "physics")) {
                int k = 0;
                for (String id : ids) {
                    LatticeBench.Trial t = first(trials, split, id, method);
                    if (t != null && t.success()) {
                        k++;
                    }
                }
                double[] ci = wilson(k, ids.size());
                List<Double> latency = new ArrayList<>();
                for (LatticeBench.Trial t : trials) {
                    if (t.split().equals(split) && t.method().equals(method)) {
                        latency.add(t.latencyMs());
                    }
                }
                text.append(String.format(Locale.ROOT,
                        "stats %s %s success %d/%d wilson95 [%.3f, %.3f] latencyMs n=%d p50 %.3f p95 %.3f p99 %.3f max %.3f%n",
                        split, method, k, ids.size(), ci[0], ci[1], latency.size(), percentile(latency, 0.5),
                        percentile(latency, 0.95), percentile(latency, 0.99), percentile(latency, 1.0)));
            }
            for (String method : List.of("beam", "weighted")) {
                int[] table = table(trials, split, ids, method);
                text.append(String.format(Locale.ROOT, "stats %s %s vs physics fisher-two-sided p=%.4f (%d/%d vs %d/%d)%n",
                        split, method, fisherTwoSided(table[0], table[1], table[2], table[3]),
                        table[0], ids.size(), table[2], ids.size()));
                List<Integer> a = new ArrayList<>();
                List<Integer> b = new ArrayList<>();
                for (String id : ids) {
                    LatticeBench.Trial l = first(trials, split, id, method);
                    LatticeBench.Trial p = first(trials, split, id, "physics");
                    if (l != null && p != null && l.success() && p.success()) {
                        a.add(l.travelTicks());
                        b.add(p.travelTicks());
                    }
                }
                double[] ratio = pairedMedianRatio(a, b);
                text.append(String.format(Locale.ROOT, "stats %s %s/physics median travel-tick ratio on %d shared solves, bootstrap95 [%.3f, %.3f]%n",
                        split, method, a.size(), ratio[0], ratio[1]));
            }
        }
        return text.toString();
    }

    private static int[] table(List<LatticeBench.Trial> trials, String split, List<String> ids, String method) {
        int ls = 0;
        int ps = 0;
        for (String id : ids) {
            LatticeBench.Trial l = first(trials, split, id, method);
            LatticeBench.Trial p = first(trials, split, id, "physics");
            if (l != null && l.success()) {
                ls++;
            }
            if (p != null && p.success()) {
                ps++;
            }
        }
        return new int[]{ls, ids.size() - ls, ps, ids.size() - ps};
    }

    private static List<String> ids(List<LatticeBench.Trial> trials, String split) {
        List<String> ids = new ArrayList<>();
        for (LatticeBench.Trial t : trials) {
            if (t.split().equals(split) && t.trial() == 0 && t.method().equals("physics") && !ids.contains(t.id())) {
                ids.add(t.id());
            }
        }
        return ids;
    }

    private static LatticeBench.Trial first(List<LatticeBench.Trial> trials, String split, String id, String method) {
        for (LatticeBench.Trial t : trials) {
            if (t.split().equals(split) && t.id().equals(id) && t.method().equals(method) && t.trial() == 0) {
                return t;
            }
        }
        return null;
    }
}
