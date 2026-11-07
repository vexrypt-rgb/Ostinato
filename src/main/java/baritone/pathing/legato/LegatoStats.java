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

package baritone.pathing.legato;

import java.util.concurrent.atomic.LongAdder;

/**
 * Ostinato Legato: counters for the diagnostics line and the live A/B harness. Cheap enough to stay on always.
 */
public final class LegatoStats {

    private static final LongAdder[] VERDICTS = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};
    private static final LongAdder EVAL_NANOS = new LongAdder();
    private static final LongAdder SHORTCUTS = new LongAdder();
    private static final LongAdder MOVES_REMOVED = new LongAdder();
    private static final LongAdder SMOOTH_NANOS = new LongAdder();
    private static final LongAdder THETA_CHECKS = new LongAdder();
    private static final LongAdder THETA_ACCEPTS = new LongAdder();
    private static final LongAdder STRAIGHT_FAILS = new LongAdder();
    private static final LongAdder STRAIGHTS_DONE = new LongAdder();

    private LegatoStats() {}

    static void evaluated(int verdict, long nanos) {
        VERDICTS[verdict].increment();
        EVAL_NANOS.add(nanos);
    }

    public static void smoothed(int shortcuts, int movesRemoved, long nanos) {
        SHORTCUTS.add(shortcuts);
        MOVES_REMOVED.add(movesRemoved);
        SMOOTH_NANOS.add(nanos);
    }

    public static void thetaCheck(boolean accepted) {
        THETA_CHECKS.increment();
        if (accepted) {
            THETA_ACCEPTS.increment();
        }
    }

    static void straightFailed() {
        STRAIGHT_FAILS.increment();
    }

    public static void straightDone() {
        STRAIGHTS_DONE.increment();
    }

    public static long evaluations() {
        return VERDICTS[0].sum() + VERDICTS[1].sum() + VERDICTS[2].sum() + VERDICTS[3].sum();
    }

    public static long verdicts(int verdict) {
        return VERDICTS[verdict].sum();
    }

    public static long evalNanos() {
        return EVAL_NANOS.sum();
    }

    public static long shortcuts() {
        return SHORTCUTS.sum();
    }

    public static long movesRemoved() {
        return MOVES_REMOVED.sum();
    }

    public static long smoothNanos() {
        return SMOOTH_NANOS.sum();
    }

    public static long thetaChecks() {
        return THETA_CHECKS.sum();
    }

    public static long thetaAccepts() {
        return THETA_ACCEPTS.sum();
    }

    public static long straightFails() {
        return STRAIGHT_FAILS.sum();
    }

    public static long straightsDone() {
        return STRAIGHTS_DONE.sum();
    }

    public static void reset() {
        for (LongAdder a : VERDICTS) {
            a.reset();
        }
        EVAL_NANOS.reset();
        SHORTCUTS.reset();
        MOVES_REMOVED.reset();
        SMOOTH_NANOS.reset();
        THETA_CHECKS.reset();
        THETA_ACCEPTS.reset();
        STRAIGHT_FAILS.reset();
        STRAIGHTS_DONE.reset();
    }

    public static String line() {
        return String.format(java.util.Locale.ROOT,
                "LEGATO evals=%d feasible=%d infeasible=%d unsupported=%d unknown=%d evalMs=%.2f shortcuts=%d removed=%d smoothMs=%.2f theta=%d/%d straights=%d fails=%d",
                evaluations(), verdicts(0), verdicts(1), verdicts(2), verdicts(3), evalNanos() / 1e6,
                shortcuts(), movesRemoved(), smoothNanos() / 1e6, thetaAccepts(), thetaChecks(), straightsDone(), straightFails());
    }
}
