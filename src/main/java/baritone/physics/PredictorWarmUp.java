/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionRequest.Input;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ostinato Presto v4: one background warm-up of the predictor, so the first real inline prediction is not the one that
 * loads and interprets its classes. Measured (PpeBudgetTest): a fresh JVM's first takeoff-shaped request took 28.6 ms
 * against its 5 ms deadline (the deadline is checked every 8 ticks, and the first 7 ticks were the cold part), warm
 * ones 71 us p50. The warm-up flies a body over a terrain built here, never the world, on a low-priority daemon thread
 * of its own: the same predictor code the scheduler's worker already runs off the game thread.
 */
public final class PredictorWarmUp {
    static final int RUNS = 300;
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean DONE = new AtomicBoolean();
    private static final BlockInfo GROUND = new BlockInfo.Builder("stone").boxes(0, 0, 0, 1, 1, 1).resistance(6).build();

    private PredictorWarmUp() {}

    /** Flat ground below y 64 over the nine columns around the origin, air above. Built here: no world is read. */
    static Terrain ground() {
        Terrain.Builder b = Terrain.builder(-64, 320, 0, 27);
        for (int sx = -1; sx <= 1; sx++) {
            for (int sz = -1; sz <= 1; sz++) {
                b.put(sx, 3, sz, Terrain.Section.uniform(GROUND, 0, 0));
                b.put(sx, 4, sz, Terrain.Section.uniform(BlockInfo.AIR_INFO, 0, 0));
                b.put(sx, 5, sz, Terrain.Section.uniform(BlockInfo.AIR_INFO, 0, 0));
            }
        }
        return b.build();
    }

    /**
     * Run {@code runs} takeoff-shaped requests (sprint jump, SCREENING, 40 ticks) and as many fall-shaped ones (Grace
     * Note's: a drop from 12 up, SCREENING, 40 ticks) on this thread.
     */
    static void run(int runs) {
        Terrain t = ground();
        Predictor p = new Predictor();
        BodyState body = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64, 0.5).onGround(true).build();
        BodyState high = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 76, 0.5).vel(0, -0.5, 0).build();
        // Ostinato: Bombard's rod/spear ask for TNT trajectories (HIGH / STANDARD with targets); warm that path too, or the
        // first live TNT prediction is the cold one and misses its 2 ms deadline (seen at fuse 35 in BombardLiveTest).
        BodyState tnt = BodyState.builder(BodyState.Type.PRIMED_TNT).pos(0.5, 68, 4.5).vel(0.02, 0.2, -0.02).size(0.98f, 0.98f, 0.15f)
                .gravity(0.04).fuse(35, 4).flag(BodyState.POWER_ASSUMED, true).build();
        BodyState foe = BodyState.builder(BodyState.Type.LIVING).pos(0.5, 64, 3.5).onGround(true).size(0.6f, 1.95f, 1.74f).build();
        for (int i = 0; i < runs; i++) {
            PredictionRequest r = PredictionRequest.trajectory(body, 40)
                    .input(new Input(1, 0, true, true, false, i % 2 == 0 ? -90 : 0, Float.NaN))
                    .fidelity(Fidelity.SCREENING).build();
            p.run(r, t, r.fidelity, () -> false);
            PredictionRequest f = PredictionRequest.fall(high, 40).fidelity(Fidelity.SCREENING).reach(4.5f).build();
            p.run(f, t, f.fidelity, () -> false);
            PredictionRequest b = PredictionRequest.trajectory(tnt, 40).targets(java.util.List.of(foe)) // Ostinato: TNT shape
                    .fidelity(i % 2 == 0 ? Fidelity.HIGH : Fidelity.STANDARD).build();
            p.run(b, t, b.fidelity, () -> false);
        }
    }

    /** Whether the warm-up ran to its end (or gave up on an error: there is nothing more to wait for). */
    public static boolean done() {
        return DONE.get();
    }

    /** Start the warm-up once per JVM; later calls cost one read. True if this call started it. */
    public static boolean start() {
        if (STARTED.get() || !STARTED.compareAndSet(false, true)) return false;
        Thread th = new Thread(() -> {
            try {
                run(RUNS);
            } finally {
                DONE.set(true);
            }
        }, "ostinato-ppe-warmup");
        th.setDaemon(true);
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
        return true;
    }
}
