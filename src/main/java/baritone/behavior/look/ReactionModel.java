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
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Ostinato: the user's surprise reaction time (humanReactionDelay), by energy state.
 * <p>
 * Each energy state is a set of measured reaction times. The {@code tired} set is the user's own measurements
 * ({@link #TIRED_MS}, 200 ms fastest while tired .. 407 ms distracted while tired). The {@code peak} set is also
 * measured ({@link #PEAK_MS}: 165 ms best ever and rare, typically 173-185 fastest, 220 slowest at peak).
 * A set gives a right-skewed (log-normal) fit with hard bounds (tired [190, 420], peak [165, 225]); the bounds
 * make the floor rare, never predicted below.
 * Within a set, an attention state drifts slowly (an Ornstein-Uhlenbeck walk with a few-minute timescale) between
 * focused (around the fastest samples) and distracted (around the slowest), so consecutive surprises are alike and
 * the spread shows up over minutes, not within a fight. Energy {@code auto} drifts from peak toward tired over a
 * long session.
 */
public final class ReactionModel {

    /** Ostinato: user-measured reaction times while tired (2026-10-14), ms. */
    public static final double[] TIRED_MS = {200, 214, 247, 290, 317, 350, 407};
    /** Ostinato: user-measured reaction times at peak (2026-10-14), ms: 165 rare best, 173-185 typical fastest, 220 slowest. */
    public static final double[] PEAK_MS = {165, 173, 177, 181, 185, 190, 198, 220};
    public static final double[] TIRED_BOUNDS = {190, 420}, PEAK_BOUNDS = {165, 225};

    public enum Energy {
        PEAK, TIRED, AUTO;

        public static Energy parse(String s) {
            if (s == null) return TIRED;
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "peak": return PEAK;
                case "auto": return AUTO;
                default: return TIRED;
            }
        }
    }

    /** One energy state's measured (or estimated) reaction times and their fit. */
    public static final class Set {
        public final double[] samplesMs;
        public final boolean estimated;
        public final double logMean, logSd, focusedLog, distractedLog, minMs, maxMs;

        Set(double[] samplesMs, boolean estimated, double[] bounds) {
            double[] s = samplesMs.clone();
            Arrays.sort(s);
            this.samplesMs = s;
            this.estimated = estimated;
            double m = 0;
            for (double v : s) m += Math.log(v);
            m /= s.length;
            double v2 = 0;
            for (double v : s) v2 += (Math.log(v) - m) * (Math.log(v) - m);
            this.logMean = m;
            this.logSd = s.length > 1 ? Math.sqrt(v2 / (s.length - 1)) : 0.15;
            int k = Math.max(1, Math.min(2, s.length / 3));
            double f = 0, d = 0;
            for (int i = 0; i < k; i++) {
                f += Math.log(s[i]);
                d += Math.log(s[s.length - 1 - i]);
            }
            this.focusedLog = f / k;
            this.distractedLog = d / k;
            this.minMs = bounds != null ? bounds[0] : s[0] - 10;
            this.maxMs = bounds != null ? bounds[1] : s[s.length - 1] + 13;
        }

        public double medianMs() {
            return Math.exp(logMean);
        }
    }

    public final Set tired, peak;
    final double timescaleSec, stateLogSd, autoFatigueMinutes;

    private double attention = 0.5; // 0 focused .. 1 distracted
    private double lastSec = Double.NaN;

    ReactionModel(Set tired, Set peak, double timescaleSec, double stateLogSd, double autoFatigueMinutes) {
        this.tired = tired;
        this.peak = peak;
        this.timescaleSec = timescaleSec;
        this.stateLogSd = stateLogSd;
        this.autoFatigueMinutes = autoFatigueMinutes;
    }

    /** The user's measured tired and peak sets. */
    public static ReactionModel defaults() {
        return new ReactionModel(new Set(TIRED_MS, false, TIRED_BOUNDS), new Set(PEAK_MS, false, PEAK_BOUNDS), 180, 0.08, 180);
    }

    /** From a profile's "reaction" block; missing parts fall back to {@link #defaults()}. */
    public static ReactionModel parse(JsonObject o) {
        if (o == null) return defaults();
        JsonObject sets = o.has("sets") && o.get("sets").isJsonObject() ? o.getAsJsonObject("sets") : null;
        double[] tiredMs = samples(sets, "tired"), peakMs = samples(sets, "peak");
        Set tired = tiredMs == null ? new Set(TIRED_MS, false, TIRED_BOUNDS) : new Set(tiredMs, estimatedFlag(sets, "tired", false), bounds(sets, "tired"));
        Set peak = peakMs == null ? new Set(PEAK_MS, false, PEAK_BOUNDS) : new Set(peakMs, estimatedFlag(sets, "peak", false), bounds(sets, "peak"));
        JsonObject att = o.has("attention") && o.get("attention").isJsonObject() ? o.getAsJsonObject("attention") : null;
        return new ReactionModel(tired, peak, num(att, "timescaleSec", 180), num(att, "stateLogSd", 0.08), num(o, "autoFatigueMinutes", 180));
    }

    /** 0 = peak .. 1 = tired, for the energy setting and how long the session has run. */
    public double fatigue(Energy e, double sessionSec) {
        switch (e) {
            case PEAK: return 0;
            case AUTO: return Math.min(1, Math.max(0, sessionSec / 60.0 / autoFatigueMinutes));
            default: return 1;
        }
    }

    public double attention() {
        return attention;
    }

    /** Advance the slow attention walk to {@code nowSec}. */
    void drift(double nowSec, Random rng) {
        if (Double.isNaN(lastSec)) {
            lastSec = nowSec;
            attention = Math.max(0, Math.min(1, 0.5 + 0.25 * rng.nextGaussian()));
            return;
        }
        double dt = Math.max(0, Math.min(3600, nowSec - lastSec));
        lastSec = nowSec;
        double k = Math.exp(-dt / timescaleSec);
        attention = 0.5 + (attention - 0.5) * k + Math.sqrt(1 - k * k) * 0.25 * rng.nextGaussian();
        attention = Math.max(0, Math.min(1, attention));
    }

    /**
     * A surprise reaction time in ms.
     *
     * @param nowSec     a clock in seconds (drives the attention walk)
     * @param sessionSec how long the session has run (drives auto energy)
     */
    public double sample(Energy energy, double nowSec, double sessionSec, Random rng) {
        drift(nowSec, rng);
        double f = fatigue(energy, sessionSec);
        double focused = lerp(peak.focusedLog, tired.focusedLog, f), distracted = lerp(peak.distractedLog, tired.distractedLog, f);
        double lo = lerp(peak.minMs, tired.minMs, f), hi = lerp(peak.maxMs, tired.maxMs, f);
        // within-state jitter scales with the set's spread (stateLogSd is for a tired-sized spread of ~0.6 in log)
        double sd = stateLogSd * Math.max(0.2, (distracted - focused) / 0.6);
        double ms = Math.exp(lerp(focused, distracted, attention) + sd * rng.nextGaussian());
        return Math.max(lo, Math.min(hi, ms));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double[] samples(JsonObject sets, String k) {
        if (sets == null || !sets.has(k) || !sets.get(k).isJsonObject()) return null;
        JsonObject s = sets.getAsJsonObject(k);
        if (!s.has("samplesMs") || !s.get("samplesMs").isJsonArray()) return null;
        JsonArray a = s.getAsJsonArray("samplesMs");
        if (a.size() < 2) return null;
        double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsDouble();
        return r;
    }

    private static double[] bounds(JsonObject sets, String k) {
        JsonObject s = sets.getAsJsonObject(k);
        if (!s.has("boundsMs") || !s.get("boundsMs").isJsonArray() || s.getAsJsonArray("boundsMs").size() != 2) return null;
        JsonArray a = s.getAsJsonArray("boundsMs");
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()};
    }

    private static boolean estimatedFlag(JsonObject sets, String k, boolean d) {
        if (sets == null || !sets.has(k) || !sets.get(k).isJsonObject()) return d;
        JsonObject s = sets.getAsJsonObject(k);
        return s.has("estimated") ? s.get("estimated").getAsBoolean() : d;
    }

    private static double num(JsonObject o, String k, double d) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : d;
    }
}
