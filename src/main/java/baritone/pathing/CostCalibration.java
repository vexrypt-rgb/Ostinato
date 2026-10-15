package baritone.pathing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compares a movement's planned cost with the ticks it actually took.
 * The scale stays 1 until there are enough samples, and it never drops below 1: a cheaper edge than the
 * heuristic expects would make A* drop a shorter path. Slow walks inflate later costs, capped.
 */
public final class CostCalibration {

    public static final int MIN_SAMPLES = 20;
    private static final double CAP = 1.5;

    private static final ConcurrentHashMap<String, Stat> STATS = new ConcurrentHashMap<>();

    private CostCalibration() {}

    public static void reset() {
        STATS.clear();
    }

    public static void record(String key, double expectedTicks, int actualTicks) {
        if (key == null || expectedTicks <= 0 || actualTicks <= 0) {
            return;
        }
        STATS.computeIfAbsent(key, k -> new Stat()).add(actualTicks / expectedTicks);
    }

    /**
     * Changes only when a family's scale leaves 1. Order of the map does not matter.
     * A route remembered under one stamp is not replayed after the costs move.
     */
    public static long scaleStamp() {
        long h = 0;
        for (Map.Entry<String, Stat> e : STATS.entrySet()) {
            double s = scale(e.getKey());
            if (s == 1.0) {
                continue;
            }
            long bits = Double.doubleToLongBits(s);
            h ^= e.getKey().hashCode() * 0x9E3779B97F4A7C15L ^ bits;
        }
        return h;
    }

    /** 1 until {@link #MIN_SAMPLES}. Then the average ratio, clamped to {@code [1, 1.5]}. */
    public static double scale(String key) {
        Stat s = STATS.get(key);
        if (s == null) {
            return 1;
        }
        double raw = s.average();
        if (raw <= 1 || Double.isNaN(raw)) {
            return 1;
        }
        return Math.min(raw, CAP);
    }

    private static final class Stat {
        private double sum;
        private int n;

        synchronized void add(double ratio) {
            if (ratio <= 0 || ratio > 20 || Double.isNaN(ratio)) {
                return;
            }
            sum += ratio;
            n++;
        }

        synchronized double average() {
            if (n < MIN_SAMPLES) {
                return 1;
            }
            return sum / n;
        }
    }
}
