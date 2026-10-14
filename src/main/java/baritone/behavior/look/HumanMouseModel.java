/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior.look;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ostinato: a generative model of one person's mouse movements, fitted from mouse-capture recordings by
 * tools/human-mouse/HumanMouseFit.java (the humanMouseProfile setting points at the file).
 * <p>
 * A movement is conditioned on its vector (in raw mouse counts), the target width (counts) and urgency (0 calm,
 * 1 hurried): the duration follows the user's Fitts' law fit, the shape (velocity profile, submovements,
 * overshoot and correction, curvature) is warped from one of the nearest recorded movements, AR(1) tremor
 * fitted from the recordings rides on top. Movements start at once; the reaction-time distribution (fitted only from
 * the capture game's surprise-target task) is used separately, by {@link ReactionGate}, for unexpected targets. The landing is kept inside the target: a correction is blended in over the final part when the warped
 * template plus noise would end outside 40% of the width.
 */
public final class HumanMouseModel {

    /** One recorded movement, normalized: along/perp are fractions of the distance over normalized time. */
    static final class Template {
        final double d, w, u;
        final double[] along, perp;

        Template(double d, double w, double u, double[] along, double[] perp) {
            this.d = d; this.w = w; this.u = u; this.along = along; this.perp = perp;
        }
    }

    final double rtLogMean, rtLogSd, rtMin, rtMax;
    final double durA, durB, durLogSd, urgencySlope, durMin, durMax;
    final double noiseSd, noiseRho, noiseDt;
    final List<Template> templates;
    final boolean synthetic;

    HumanMouseModel(double rtLogMean, double rtLogSd, double rtMin, double rtMax, double durA, double durB, double durLogSd,
                    double urgencySlope, double durMin, double durMax, double noiseSd, double noiseRho, double noiseDt,
                    List<Template> templates, boolean synthetic) {
        this.rtLogMean = rtLogMean; this.rtLogSd = rtLogSd; this.rtMin = rtMin; this.rtMax = rtMax;
        this.durA = durA; this.durB = durB; this.durLogSd = durLogSd; this.urgencySlope = urgencySlope; this.durMin = durMin; this.durMax = durMax;
        this.noiseSd = noiseSd; this.noiseRho = noiseRho; this.noiseDt = noiseDt > 0 ? noiseDt : 5;
        this.templates = templates; this.synthetic = synthetic;
    }

    public static HumanMouseModel load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(r);
        }
    }

    public static HumanMouseModel load(Reader r) {
        return parse(JsonParser.parseReader(r).getAsJsonObject());
    }

    public static HumanMouseModel parse(JsonObject o) {
        String fmt = str(o, "format");
        if (fmt == null || !fmt.startsWith("ostinato-human-mouse-profile/")) throw new IllegalArgumentException("not a human mouse profile: " + fmt);
        JsonObject rt = o.getAsJsonObject("reaction"), du = o.getAsJsonObject("duration"), no = o.getAsJsonObject("noise");
        List<Template> ts = new ArrayList<>();
        JsonArray arr = o.getAsJsonArray("templates");
        if (arr != null) for (JsonElement e : arr) {
            JsonObject t = e.getAsJsonObject();
            double[] al = doubles(t.getAsJsonArray("along")), pe = doubles(t.getAsJsonArray("perp"));
            if (al.length < 3 || al.length != pe.length) continue;
            ts.add(new Template(num(t, "d", 100), Math.max(1, num(t, "w", 10)), num(t, "u", 0.5), al, pe));
        }
        return new HumanMouseModel(num(rt, "logMean", DEFAULT_RT_LOG_MEAN), num(rt, "logSd", DEFAULT_RT_LOG_SD), num(rt, "minMs", 120), num(rt, "maxMs", 900),
                num(du, "a", 110), num(du, "b", 105), num(du, "logSd", 0.15), num(du, "urgencySlope", -0.35), num(du, "minMs", 40), num(du, "maxMs", 3000),
                num(no, "sd", 0.5), num(no, "rho", 0.8), num(no, "dtMs", 5), ts, o.has("synthetic") && o.get("synthetic").getAsBoolean());
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public int templateCount() {
        return templates.size();
    }

    /** Built-in surprise reaction time when no profile (or no reaction fit) is in use: median 230 ms. */
    public static final double DEFAULT_RT_LOG_MEAN = Math.log(230), DEFAULT_RT_LOG_SD = 0.22;

    /** A reaction time (ms) for a surprise: the user's fitted log-normal, clamped to [minMs, maxMs]. */
    public double sampleReactionMs(Random rng) {
        return Math.max(rtMin, Math.min(rtMax, Math.exp(rtLogMean + rtLogSd * rng.nextGaussian())));
    }

    public double medianReactionMs() {
        return Math.max(rtMin, Math.min(rtMax, Math.exp(rtLogMean)));
    }

    /** The built-in reaction time, for when no profile is set. */
    public static double defaultReactionMs(Random rng) {
        return Math.max(120, Math.min(900, Math.exp(DEFAULT_RT_LOG_MEAN + DEFAULT_RT_LOG_SD * rng.nextGaussian())));
    }

    /** Median movement time (ms, without reaction) the fit predicts for a move of d counts onto a target w counts wide. */
    public double medianDurationMs(double d, double w, double urgency) {
        double t = (durA + durB * log2(d / Math.max(1, w) + 1)) * Math.exp(urgencySlope * (urgency - 0.5));
        return Math.max(durMin, Math.min(durMax, t));
    }

    /** A generated movement: cumulative mouse counts on a fixed time grid, starting at (0, 0) at time 0. */
    public static final class Movement {
        public final double dtMs;
        public final double[] x, y;

        Movement(double dtMs, double[] x, double[] y) {
            this.dtMs = dtMs; this.x = x; this.y = y;
        }

        /** Movement time in ms; it starts moving at time 0. */
        public double durationMs() {
            return (x.length - 1) * dtMs;
        }

        public double endX() {
            return x[x.length - 1];
        }

        public double endY() {
            return y[y.length - 1];
        }

        /** Position (counts) at time t (ms), linearly interpolated; holds the end after the movement. */
        public double[] at(double t) {
            double f = t / dtMs;
            if (f <= 0) return new double[]{x[0], y[0]};
            int i = (int) f;
            if (i >= x.length - 1) return new double[]{endX(), endY()};
            double a = f - i;
            return new double[]{x[i] + (x[i + 1] - x[i]) * a, y[i] + (y[i + 1] - y[i]) * a};
        }
    }

    /**
     * Generates one movement.
     *
     * @param dx       horizontal counts to travel (positive turns right)
     * @param dy       vertical counts (positive looks down)
     * @param width    target width in counts
     * @param urgency  0 calm .. 1 hurried
     */
    public Movement generate(double dx, double dy, double width, double urgency, Random rng) {
        double dt = noiseDt, d = Math.hypot(dx, dy), w = Math.max(1, width), u = Math.max(0, Math.min(1, urgency));
        if (d < 0.5) {
            return new Movement(dt, new double[2], new double[2]);
        }
        double dur = medianDurationMs(d, w, u) * Math.exp(durLogSd * clampG(rng.nextGaussian()));
        dur = Math.max(durMin, Math.min(durMax, dur));
        int n = Math.max(2, (int) Math.round(dur / dt));
        double ux = dx / d, uy = dy / d;
        double[] along = new double[n + 1], perp = new double[n + 1];
        Template t = pick(d, w, u, rng);
        if (t != null) {
            // a template recorded for a target of width t.w lands within ~t.w/2: rescale its landing error to this width
            double endA = t.along[t.along.length - 1], endP = t.perp[t.perp.length - 1];
            double errScale = Math.min(1.0, (w / d) / Math.max(1e-6, t.w / t.d));
            double mirror = rng.nextBoolean() ? 1 : -1; // curvature side is not tied to direction in the data we keep
            for (int k = 0; k <= n; k++) {
                double f = (double) k / n;
                double a = sample(t.along, f), p = sample(t.perp, f) * mirror;
                // shrink the end error but keep the shape (overshoot, correction) of the path
                a = a - (endA - 1) * (1 - errScale) * smooth(f);
                p = p - endP * mirror * (1 - errScale) * smooth(f);
                along[k] = a * d;
                perp[k] = p * d;
            }
        } else {
            for (int k = 0; k <= n; k++) along[k] = minJerk((double) k / n) * d;
        }
        // tremor: AR(1) in counts, along and across
        double na = 0, np = 0, s = noiseSd * Math.sqrt(Math.max(0, 1 - noiseRho * noiseRho));
        for (int k = 1; k <= n; k++) {
            na = noiseRho * na + s * rng.nextGaussian();
            np = noiseRho * np + s * rng.nextGaussian();
            along[k] += na;
            perp[k] += np;
        }
        // keep the landing inside the target: blend a correction over the last 30%
        double ea = along[n] - d, ep = perp[n], e = Math.hypot(ea, ep), allow = 0.4 * w;
        if (e > allow) {
            double k0 = e - allow * (0.5 + 0.5 * rng.nextDouble());
            double ca = ea / e * k0, cp = ep / e * k0;
            for (int k = 0; k <= n; k++) {
                double f = (double) k / n, g = f < 0.7 ? 0 : smooth((f - 0.7) / 0.3);
                along[k] -= ca * g;
                perp[k] -= cp * g;
            }
        }
        along[0] = 0;
        perp[0] = 0;
        double[] x = new double[n + 1], y = new double[n + 1];
        for (int k = 0; k <= n; k++) {
            x[k] = along[k] * ux - perp[k] * uy;
            y[k] = along[k] * uy + perp[k] * ux;
        }
        return new Movement(dt, x, y);
    }

    private Template pick(double d, double w, double u, Random rng) {
        if (templates.isEmpty()) return null;
        int k = Math.min(6, templates.size());
        Template[] best = new Template[k];
        double[] score = new double[k];
        java.util.Arrays.fill(score, Double.MAX_VALUE);
        double ld = Math.log(d), lw = Math.log(w);
        for (Template t : templates) {
            double s = sq(Math.log(t.d) - ld) + 0.5 * sq(Math.log(t.w) - lw) + 0.8 * sq(t.u - u);
            for (int i = 0; i < k; i++) {
                if (s < score[i]) {
                    System.arraycopy(score, i, score, i + 1, k - i - 1);
                    System.arraycopy(best, i, best, i + 1, k - i - 1);
                    score[i] = s;
                    best[i] = t;
                    break;
                }
            }
        }
        double tot = 0;
        for (int i = 0; i < k; i++) tot += 1.0 / (1 + i);
        double r = rng.nextDouble() * tot;
        for (int i = 0; i < k; i++) {
            r -= 1.0 / (1 + i);
            if (r <= 0) return best[i];
        }
        return best[0];
    }

    private static double sample(double[] a, double f) {
        double x = f * (a.length - 1);
        int i = (int) x;
        if (i >= a.length - 1) return a[a.length - 1];
        return a[i] + (a[i + 1] - a[i]) * (x - i);
    }

    private static double smooth(double f) {
        f = Math.max(0, Math.min(1, f));
        return f * f * (3 - 2 * f);
    }

    private static double minJerk(double t) {
        return t * t * t * (10 - 15 * t + 6 * t * t);
    }

    private static double clampG(double g) {
        return Math.max(-2.5, Math.min(2.5, g));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static double num(JsonObject o, String k, double d) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : d;
    }

    private static double[] doubles(JsonArray a) {
        if (a == null) return new double[0];
        double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble();
        return r;
    }
}
