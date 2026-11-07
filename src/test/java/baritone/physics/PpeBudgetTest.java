/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionRequest.Input;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionResult.Failure;
import baritone.api.physics.PredictionResult.Status;
import org.junit.Test;

import java.util.Arrays;

import static baritone.physics.PpeWorlds.*;
import static org.junit.Assert.*;

/**
 * Ostinato Presto v4: the prediction budget. The predictor checks its deadline every 8 ticks, so a request costs at
 * most its deadline plus one 8-tick slice; these tests run the worst request the engine accepts (the longest horizon,
 * a body that collides on every tick) and the request Presto's takeoff check makes, and print what they measured.
 * Timings are wall-clock on whatever machine runs the suite, so the asserts carry generous slack; the printed numbers
 * are the measurement.
 */
public class PpeBudgetTest {
    /** Sprint-jumping into the corner of a 3-high pen: a collision on every tick, for as long as the horizon lasts. */
    private static final Terrain PEN = flat((x, y, z) -> y >= 64 && y <= 66 && (Math.abs(x) == 4 || Math.abs(z) == 4) ? STONE : null);
    private static final Input SPRINT_JUMP = new Input(1, 0, true, true, false, 30, 0);

    private static PredictionRequest.Builder worst() {
        return PredictionRequest.trajectory(BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).onGround(true).build(),
                PredictionRequest.MAX_HORIZON).input(SPRINT_JUMP).fidelity(Fidelity.HIGH);
    }

    private static long[] times(int n, java.util.function.LongSupplier one) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = one.getAsLong();
        Arrays.sort(t);
        return t;
    }

    /** The worst request runs its whole horizon in the pen and stays inside it: it is the cost the deadline must cut. */
    @Test
    public void worstCaseRequestRunsItsHorizon() {
        Predictor p = new Predictor();
        PredictionRequest r = worst().build();
        PredictionResult out = p.run(r, PEN, r.fidelity, () -> false);
        assertEquals(Status.OK, out.status);
        assertEquals(PredictionRequest.MAX_HORIZON, out.horizonTicks);
        assertTrue("stays in the pen", Math.abs(out.x) < 4 && Math.abs(out.z) < 4);
    }

    /**
     * Budget exhaustion: with a deadline a quarter of the request's own measured cost, every run stops early with
     * PARTIAL/DEADLINE, and none runs past the deadline by more than one slice plus scheduling noise.
     */
    @Test
    public void deadlineCutsTheWorstCase() {
        Predictor p = new Predictor();
        PredictionRequest full = worst().build();
        for (int i = 0; i < 30; i++) p.run(full, PEN, full.fidelity, () -> false); // warm the JIT
        long[] whole = times(30, () -> {
            long t0 = System.nanoTime();
            p.run(full, PEN, full.fidelity, () -> false);
            return System.nanoTime() - t0;
        });
        long cost = whole[whole.length / 2];
        long slice = cost * 8 / PredictionRequest.MAX_HORIZON;
        long budget = cost / 4;
        long[] over = new long[40];
        int[] horizons = new int[over.length];
        for (int i = 0; i < over.length; i++) {
            PredictionRequest r = worst().deadlineIn(budget).build();
            long t0 = System.nanoTime();
            PredictionResult out = p.run(r, PEN, r.fidelity, () -> false);
            over[i] = System.nanoTime() - t0 - budget;
            horizons[i] = out.horizonTicks;
            assertEquals(Status.PARTIAL, out.status);
            assertEquals(Failure.DEADLINE, out.failure);
            assertTrue(out.horizonTicks < PredictionRequest.MAX_HORIZON);
        }
        Arrays.sort(over);
        Arrays.sort(horizons);
        System.out.printf(java.util.Locale.ROOT, "PPE-BUDGET worst case %d ticks: p50 %d us max %d us; slice %d us; deadline %d us -> horizon p50 %d,"
                        + " overrun p50 %d us max %d us%n", PredictionRequest.MAX_HORIZON, cost / 1000, whole[whole.length - 1] / 1000, slice / 1000,
                budget / 1000, horizons[horizons.length / 2], over[over.length / 2] / 1000, over[over.length - 1] / 1000);
        assertTrue("median overrun within a slice plus 2 ms, " + over[over.length / 2], over[over.length / 2] <= slice + 2_000_000L);
    }

    /** A cancelled job stops at the next slice whatever its deadline. */
    @Test
    public void cancellationStopsTheWorstCase() {
        PredictionRequest r = worst().build();
        PredictionResult out = new Predictor().run(r, PEN, r.fidelity, () -> true);
        assertEquals(Status.CANCELLED, out.status);
    }

    /**
     * The request Presto's takeoff check makes (KinematicController.takeoffCheck): a sprint jump, SCREENING, horizon 40
     * at most, deadline 5 ms. Measured warm; it finishes well inside the deadline, so the deadline only matters cold or
     * on a loaded machine.
     */
    @Test
    public void prestoTakeoffRequestCost() {
        Predictor p = new Predictor();
        PredictionRequest.Builder b = PredictionRequest.trajectory(BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).onGround(true).build(), 40)
                .input(new Input(1, 0, true, true, false, -90, Float.NaN)).fidelity(Fidelity.SCREENING);
        long first = System.nanoTime();
        PredictionResult cold = p.run(b.deadlineIn(5_000_000L).build(), FLAT_STONE, Fidelity.SCREENING, () -> false);
        first = System.nanoTime() - first;
        // cold (class loading, interpreter) the first call may run out of its 5 ms: then it says so, PARTIAL/DEADLINE
        assertTrue(cold.status + "/" + cold.failure, cold.status == Status.OK || cold.status == Status.PARTIAL && cold.failure == Failure.DEADLINE);
        for (int i = 0; i < 200; i++) p.run(b.build(), FLAT_STONE, Fidelity.SCREENING, () -> false);
        long[] t = times(200, () -> {
            long t0 = System.nanoTime();
            PredictionResult out = p.run(b.deadlineIn(5_000_000L).build(), FLAT_STONE, Fidelity.SCREENING, () -> false);
            assertEquals(Status.OK, out.status);
            assertEquals(40, out.horizonTicks);
            return System.nanoTime() - t0;
        });
        System.out.printf(java.util.Locale.ROOT, "PPE-BUDGET presto takeoff request: first %d us (%s/%s horizon %d), warm p50 %d us p95 %d us max %d us%n",
                first / 1000, cold.status, cold.failure, cold.horizonTicks, t[t.length / 2] / 1000, t[t.length * 95 / 100] / 1000, t[t.length - 1] / 1000);
        assertTrue("warm median far inside the 5 ms deadline", t[t.length / 2] < 1_000_000L);
    }

    private static final Terrain FLAT_STONE = flat(STONE);
}
