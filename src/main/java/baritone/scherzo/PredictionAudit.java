/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import java.util.Locale;

/**
 * Every prediction against the position that followed it. For each horizon in {@link #HORIZONS} it keeps the model's
 * error, the error of the velocity the fight already uses (the baseline), and how often the truth fell inside the
 * model's {@code spread} and {@code bound}. Game-free and bounded: fixed arrays, nothing kept per prediction past
 * its horizon.
 * <p>
 * This is the measure the model's rules are tuned from, and what OBSERVE mode exists for: it moves no decision.
 */
public final class PredictionAudit {

    /** Ticks ahead that are checked. */
    public static final int[] HORIZONS = {1, 3, 5, 10};
    /** Error histogram: bins this many blocks wide, the last one holding everything past it. */
    static final double BIN = 0.05;
    static final int BINS = 100;
    private static final int RING = 16; // > the longest horizon

    /** One horizon's figures for one predictor. */
    static final class Stat {
        final int[] hist = new int[BINS];
        long n;
        double sum, max;

        void add(double e) {
            n++;
            sum += e;
            max = Math.max(max, e);
            hist[Math.min(BINS - 1, (int) (e / BIN))]++;
        }

        void clear() {
            java.util.Arrays.fill(hist, 0);
            n = 0;
            sum = max = 0;
        }

        double mean() {
            return n == 0 ? Double.NaN : sum / n;
        }

        /** Upper edge of the bin holding the q-quantile; NaN with nothing measured. */
        double quantile(double q) {
            if (n == 0) return Double.NaN;
            long want = (long) Math.ceil(q * n), seen = 0;
            for (int i = 0; i < BINS; i++) {
                seen += hist[i];
                if (seen >= want) return (i + 1) * BIN;
            }
            return BINS * BIN;
        }
    }

    // pending predictions, by horizon then by the tick they are due on, modulo RING
    private final long[][] due = new long[HORIZONS.length][RING];
    private final double[][] mx = new double[HORIZONS.length][RING], my = new double[HORIZONS.length][RING], mz = new double[HORIZONS.length][RING];
    private final double[][] bx = new double[HORIZONS.length][RING], by = new double[HORIZONS.length][RING], bz = new double[HORIZONS.length][RING];
    private final double[][] spread = new double[HORIZONS.length][RING], bound = new double[HORIZONS.length][RING];
    private final double[][] up = new double[HORIZONS.length][RING], down = new double[HORIZONS.length][RING];

    final Stat[] model = new Stat[HORIZONS.length], baseline = new Stat[HORIZONS.length], vertical = new Stat[HORIZONS.length];
    final long[] inSpread = new long[HORIZONS.length], inBound = new long[HORIZONS.length], inVertical = new long[HORIZONS.length];

    public PredictionAudit() {
        for (int i = 0; i < HORIZONS.length; i++) {
            model[i] = new Stat();
            baseline[i] = new Stat();
            vertical[i] = new Stat();
            java.util.Arrays.fill(due[i], Long.MIN_VALUE);
        }
    }

    /** Drop what is pending: the opponent, the world or the history changed, so they would be judged against another body. */
    public void discardPending() {
        for (long[] d : due) java.util.Arrays.fill(d, Long.MIN_VALUE);
    }

    /** Pending and measured both. */
    public void clear() {
        discardPending();
        for (int i = 0; i < HORIZONS.length; i++) {
            model[i].clear();
            baseline[i].clear();
            vertical[i].clear();
            inSpread[i] = inBound[i] = inVertical[i] = 0;
        }
    }

    /**
     * The model's prediction for horizon index {@code i} made on tick {@code now}, and the baseline's point for the
     * same tick. The model's prediction must have been asked for exactly {@code HORIZONS[i]} ticks ahead of {@code now}.
     */
    public void expect(long now, int i, Prediction p, double baseX, double baseY, double baseZ) {
        long at = now + HORIZONS[i];
        int s = (int) Math.floorMod(at, (long) RING);
        due[i][s] = at;
        mx[i][s] = p.x();
        my[i][s] = p.y();
        mz[i][s] = p.z();
        bx[i][s] = baseX;
        by[i][s] = baseY;
        bz[i][s] = baseZ;
        spread[i][s] = p.spread();
        bound[i][s] = p.bound();
        up[i][s] = p.up();
        down[i][s] = p.down();
    }

    /** Where the opponent actually is on tick {@code now}: settles every prediction due now. */
    public void actual(long now, double x, double y, double z) {
        int s = (int) Math.floorMod(now, (long) RING);
        for (int i = 0; i < HORIZONS.length; i++) {
            if (due[i][s] != now) continue;
            due[i][s] = Long.MIN_VALUE;
            double h = Math.hypot(x - mx[i][s], z - mz[i][s]);
            double dyM = y - my[i][s];
            model[i].add(Math.sqrt(h * h + dyM * dyM));
            vertical[i].add(Math.abs(dyM));
            double bh = Math.hypot(x - bx[i][s], z - bz[i][s]), bdy = y - by[i][s];
            baseline[i].add(Math.sqrt(bh * bh + bdy * bdy));
            if (h <= spread[i][s]) inSpread[i]++;
            if (h <= bound[i][s]) inBound[i]++;
            if (dyM <= up[i][s] && -dyM <= down[i][s]) inVertical[i]++;
        }
    }

    public long settled(int i) {
        return model[i].n;
    }

    /** One line per horizon with anything settled; empty when nothing was. */
    public String report(String label) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < HORIZONS.length; i++) {
            Stat m = model[i], e = baseline[i];
            if (m.n == 0) continue;
            if (b.length() > 0) b.append('\n');
            b.append(String.format(Locale.ROOT,
                    "scherzo %s h=%d n=%d model mean=%.3f p50=%.2f p95=%.2f max=%.2f | base mean=%.3f p50=%.2f p95=%.2f max=%.2f"
                            + " | in-spread=%.0f%% in-bound=%.1f%% vert mean=%.3f in-vert=%.0f%%",
                    label, HORIZONS[i], m.n, m.mean(), m.quantile(0.5), m.quantile(0.95), m.max,
                    e.mean(), e.quantile(0.5), e.quantile(0.95), e.max,
                    100.0 * inSpread[i] / m.n, 100.0 * inBound[i] / m.n, vertical[i].mean(), 100.0 * inVertical[i] / m.n));
        }
        return b.toString();
    }
}
