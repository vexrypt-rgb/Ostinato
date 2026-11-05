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
     * p99 one-step error per mode. The Tier-L rows come from the 26.3 client SIMTRACE runs of 2026-10-14
     * (MovementJump, MovementChainJump, MovementClimbJump; PlayerSim unchanged since); yaw lag is included,
     * because the consumers see it too. The Tier-S rows have no live data: PlayerSim and PPE agree to 1e-5
     * on them (CrossModelModesTest), which bounds model disagreement, not error against the game.
     * They borrow the nearest live bound, or keep the old SimTrace constant where none is near.
     */
    public static final PredictionTolerance MEASURED = new PredictionTolerance(
            new Bound(Mode.SPRINT, 0.2235, Tier.L, "live 26.3 jump/chain ground ticks, n=4008, p99"),
            new Bound(Mode.SPRINT_JUMP, 0.0983, Tier.L, "live 26.3 jump/chain airborne ticks, n=9477, p99"),
            new Bound(Mode.CLIMB, 0.2374, Tier.L, "live 26.3 climb-jump runs, all ticks, n=739, p99; ladder climbing proper not traced"),
            new Bound(Mode.WALK, 0.2235, Tier.S, "no live walk trace; SPRINT live bound borrowed; cross-model max 1e-6"),
            new Bound(Mode.FALL, 0.0983, Tier.S, "no live fall trace; SPRINT_JUMP live bound borrowed; cross-model max 7e-6"),
            new Bound(Mode.FLUID, 0.05, Tier.S, "no live fluid trace; old SimTrace.DIVERGED kept; cross-model max 0"));

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
