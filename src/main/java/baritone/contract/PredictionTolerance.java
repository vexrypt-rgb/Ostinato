/*
 * Ostinato 2.0 Phase 04: canonical one-step prediction uncertainty per movement mode. Not part of upstream Baritone.
 * Off unless the predictionTolerance setting is on; with it off, SimTrace and PhysicsTravel use their old constants.
 */
package baritone.contract;

import baritone.Baritone;
import baritone.pathing.kinematic.PlayerSim;

import java.util.EnumMap;
import java.util.Map;

/**
 * How far one predicted tick may land from the real next state before a consumer treats the model as wrong.
 * The metric is the Euclidean distance in blocks between the real state and the state predicted from the
 * last real state and the controls applied, one tick ahead (SimTrace's "one step").
 * Each bound carries its evidence tier: L from real client traces, S from headless runs only, U from unit arithmetic.
 */
public final class PredictionTolerance {

    public enum Mode { WALK, SPRINT, SPRINT_JUMP, FALL, FLUID, CLIMB }

    public enum Tier { L, S, U }

    public record Bound(Mode mode, double oneStep, Tier tier, String basis) {
    }

    /**
     * p99 one-step error per mode, all six Tier L for the current (D12, 26.3) {@code PlayerSim}: the live 26.3 client
     * traces of 2026-11-08 ({@code ToleranceTraceTest}, BENCHMARKS "Phase 04 live validation"). Each bound is the p99 of
     * the dev session (seed 81001, about 2000 usable ticks per mode); each held on the held-out session (seed 81002),
     * where at most 1.54% of a mode's ticks went over it (the pre-registered limit was 2%). The predictor is the
     * simulator's model for the mode: {@code tickWater} for FLUID, {@code tick} otherwise. Controls were applied as
     * {@code PhysicsTravel} applies them, so the look path's yaw quantisation is included; with the yaw the client
     * moved with, every mode's p99 is 0. Turns were at most 30° a tick, under the aim cap, so yaw lag from a larger
     * commanded turn is not in these numbers. FALL's p99 is under the trace's 1e-6 resolution and is set to it.
     * The D11 values (SPRINT 0.2235, SPRINT_JUMP 0.0983, CLIMB 0.2374) were measured with the 1.16 model and are gone.
     */
    public static final PredictionTolerance MEASURED = new PredictionTolerance(
            new Bound(Mode.WALK, 0.003547, Tier.L, "live 26.3 trace, D12 model, dev n=2069 p99; held-out n=2076, 0.82% over"),
            new Bound(Mode.SPRINT, 0.004708, Tier.L, "live 26.3 trace, D12 model, dev n=2000 p99; held-out n=2017, 1.54% over"),
            new Bound(Mode.SPRINT_JUMP, 0.000452, Tier.L, "live 26.3 trace, D12 model, dev n=2110 p99; held-out n=2078, 1.01% over"),
            new Bound(Mode.FALL, 0.000001, Tier.L, "live 26.3 trace, D12 model, dev n=2000 p99 < 1e-6 (resolution); held-out n=2000, 0% over"),
            new Bound(Mode.FLUID, 0.000346, Tier.L, "live 26.3 trace, D12 model, tickWater, dev n=2072 p99; held-out n=2020, 0.84% over"),
            new Bound(Mode.CLIMB, 0.001103, Tier.L, "live 26.3 trace, D12 model, ladders and vines, dev n=2000 p99; held-out n=2062, 1.07% over"));

    private final Map<Mode, Bound> bounds = new EnumMap<>(Mode.class);

    public PredictionTolerance(Bound... rows) {
        for (Bound b : rows) {
            bounds.put(b.mode(), b);
        }
        for (Mode m : Mode.values()) {
            if (!bounds.containsKey(m)) {
                throw new IllegalArgumentException("no bound for " + m);
            }
        }
    }

    /** {@link #MEASURED} when the predictionTolerance setting is on, else null: the caller keeps its old constant. */
    public static PredictionTolerance active() {
        return Baritone.settings().predictionTolerance.value ? MEASURED : null;
    }

    public Bound bound(Mode mode) {
        return bounds.get(mode);
    }

    public double oneStep(Mode mode) {
        return bounds.get(mode).oneStep();
    }

    public boolean exceeds(Mode mode, double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz) > oneStep(mode);
    }

    /** The mode of the tick about to run from {@code s}, with these controls. */
    public static Mode classify(PlayerSim s, boolean moving, boolean sprint) {
        if (s.inWater()) {
            return Mode.FLUID;
        }
        if (s.climbable()) {
            return Mode.CLIMB;
        }
        if (s.onGround) {
            return sprint && moving ? Mode.SPRINT : Mode.WALK;
        }
        return moving ? Mode.SPRINT_JUMP : Mode.FALL;
    }
}
