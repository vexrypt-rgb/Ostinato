/*
 * Ostinato 2.0 Phase 04: the canonical prediction tolerance and its two consumers, SimTrace and PhysicsTravel.
 * Flag off must be the old checks bit for bit; flag on applies the per-mode bounds.
 */
package baritone.pathing.physics;

import baritone.Baritone;
import baritone.contract.PredictionTolerance;
import baritone.contract.PredictionTolerance.Mode;
import baritone.pathing.calc.PathBench;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.kinematic.SimTrace;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PredictionToleranceTest {

    /** Stone below y 0; water in x 10..19; a ladder column at x -5. */
    static PlayerSim.World world(float slip) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                out.add(new double[]{-100, -1, -100, 100, 0, 100});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return slip;
            }

            @Override
            public boolean water(int x, int y, int z) {
                return x >= 10 && x < 20 && y >= 0 && y < 3;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return x == -5 && y >= 0 && y < 20;
            }
        };
    }

    /**
     * The consumer tests' own table: the D11 numbers, kept here so these tests check how SimTrace and PhysicsTravel
     * apply a per-mode bound, whatever {@link PredictionTolerance#MEASURED} holds after a live re-measurement.
     */
    static final PredictionTolerance TABLE = new PredictionTolerance(
            new PredictionTolerance.Bound(Mode.SPRINT, 0.2235, PredictionTolerance.Tier.U, "test fixture"),
            new PredictionTolerance.Bound(Mode.SPRINT_JUMP, 0.0983, PredictionTolerance.Tier.U, "test fixture"),
            new PredictionTolerance.Bound(Mode.CLIMB, 0.2374, PredictionTolerance.Tier.U, "test fixture"),
            new PredictionTolerance.Bound(Mode.WALK, 0.2235, PredictionTolerance.Tier.U, "test fixture"),
            new PredictionTolerance.Bound(Mode.FALL, 0.0983, PredictionTolerance.Tier.U, "test fixture"),
            new PredictionTolerance.Bound(Mode.FLUID, 0.05, PredictionTolerance.Tier.U, "test fixture"));

    static PlayerSim at(PlayerSim.World w, double x, double y, boolean ground) {
        PlayerSim s = new PlayerSim(w);
        s.x = x + 0.5;
        s.y = y;
        s.z = 0.5;
        s.onGround = ground;
        return s;
    }

    /** A sprint-jump on {@code truth}, traced with {@code model}: the same driver as SimTraceTest. */
    static List<SimTrace.Row> fly(SimTrace trace, PlayerSim.World truth) {
        PlayerSim real = at(truth, 0, 0, true);
        List<SimTrace.Row> rows = new ArrayList<>();
        for (int t = 0; t < 30; t++) {
            boolean jump = t == 3 || t == 15;
            SimTrace.Row r = trace.observe(real, 3);
            if (r != null) rows.add(r);
            trace.commit(real, 0, 1, true, jump);
            real.tick(0, 1, true, jump);
        }
        rows.add(trace.observe(real, 0));
        return rows;
    }

    static double[] fields(SimTrace.Row r) {
        return new double[]{r.tick, r.dx, r.dy, r.dz, r.dvx, r.dvy, r.dvz, r.openDx, r.openDy, r.openDz, r.yaw,
                r.groundPredicted ? 1 : 0, r.groundActual ? 1 : 0};
    }

    @Test
    public void settingDefaultsOffSoNoConsumerSeesATolerance() {
        PathBench.boot();
        assertFalse(Baritone.settings().predictionTolerance.value);
        assertNull(PredictionTolerance.active());
    }

    @Test
    public void flagOffSimTraceIsTheOldFixedThreshold() {
        // wrong friction, so the steps are non-zero and some cross 0.05
        for (float slip : new float[]{0.6f, 0.8f, 0.98f}) {
            SimTrace before = new SimTrace("off", world(slip));
            SimTrace off = new SimTrace("off", world(slip), null);
            List<SimTrace.Row> a = fly(before, world(0.6f));
            List<SimTrace.Row> b = fly(off, world(0.6f));
            assertEquals(a.size(), b.size());
            int legacyFirst = -1;
            for (int i = 0; i < a.size(); i++) {
                assertTrue(Arrays.equals(fields(a.get(i)), fields(b.get(i))));
                if (legacyFirst < 0 && a.get(i).step() > SimTrace.DIVERGED) legacyFirst = a.get(i).tick;
            }
            assertEquals(legacyFirst, before.firstDiverged());
            assertEquals(before.firstDiverged(), off.firstDiverged());
            assertEquals(before.held(), off.held());
            assertEquals(before.summary(true), off.summary(true));
            assertTrue(off.summary(true).contains("firstOver0.05="));
        }
    }

    @Test
    public void flagOffDriftIsTheOldSummedAxisCheck() {
        Random random = new Random(0x04_0D_21F7L);
        PlayerSim.World w = world(0.6f);
        PlayerSim p = new PlayerSim(w);
        PlayerSim r = new PlayerSim(w);
        for (int i = 0; i < 100_000; i++) {
            p.x = random.nextDouble() * 4;
            p.y = random.nextDouble() * 4;
            p.z = random.nextDouble() * 4;
            double scale = random.nextDouble() * 0.2;
            r.x = p.x + (random.nextDouble() - 0.5) * scale;
            r.y = p.y + (random.nextDouble() - 0.5) * scale;
            r.z = p.z + (random.nextDouble() - 0.5) * scale;
            boolean legacy = Math.abs(p.x - r.x) + Math.abs(p.y - r.y) + Math.abs(p.z - r.z) > 0.1;
            for (Mode m : Mode.values()) {
                assertEquals(legacy, PhysicsTravel.drifted(p, r, null, m));
            }
            assertEquals(legacy, PhysicsTravel.drifted(p, r, PredictionTolerance.MEASURED, null));
        }
    }

    @Test
    public void flagOnJudgesEachTickByItsMode() {
        PlayerSim.World w = world(0.6f);
        // ground sprint, 0.08 off: over the old 0.05, inside the SPRINT bound
        SimTrace off = new SimTrace("ground", w);
        SimTrace on = new SimTrace("ground", w, TABLE);
        PlayerSim real = at(w, 0, 0, true);
        for (SimTrace t : List.of(off, on)) {
            t.observe(real, 0);
            t.commit(real, 0, 1, true, false);
        }
        PlayerSim next = new PlayerSim(w).copyFrom(real);
        next.tick(0, 1, true, false);
        next.x += 0.08;
        off.observe(next, 0);
        on.observe(next, 0);
        assertFalse(off.held());
        assertTrue(on.held());
        assertTrue(on.summary(true).contains("firstOverMode="));

        // airborne sprint-jump, 0.12 off: over the SPRINT_JUMP bound as well
        SimTrace air = new SimTrace("air", w, TABLE);
        PlayerSim flying = at(w, 0, 0.8, false);
        flying.vy = 0.2;
        air.observe(flying, 0);
        air.commit(flying, 0, 1, true, false);
        PlayerSim landed = new PlayerSim(w).copyFrom(flying);
        landed.tick(0, 1, true, false);
        landed.z += 0.12;
        air.observe(landed, 0);
        assertFalse(air.held());

        // PhysicsTravel: 0.06 on each axis is 0.18 summed (old: drift), 0.104 Euclidean
        PlayerSim p = at(w, 0, 0, true);
        PlayerSim r = new PlayerSim(w).copyFrom(p);
        r.x += 0.06;
        r.y += 0.06;
        r.z += 0.06;
        assertTrue(PhysicsTravel.drifted(p, r, null, Mode.SPRINT));
        assertFalse(PhysicsTravel.drifted(p, r, TABLE, Mode.SPRINT));
        assertTrue(PhysicsTravel.drifted(p, r, TABLE, Mode.SPRINT_JUMP));
    }

    @Test
    public void classifyCoversEveryMode() {
        PlayerSim.World w = world(0.6f);
        assertEquals(Mode.WALK, PredictionTolerance.classify(at(w, 0, 0, true), true, false));
        assertEquals(Mode.WALK, PredictionTolerance.classify(at(w, 0, 0, true), false, true));
        assertEquals(Mode.SPRINT, PredictionTolerance.classify(at(w, 0, 0, true), true, true));
        assertEquals(Mode.SPRINT_JUMP, PredictionTolerance.classify(at(w, 0, 1, false), true, true));
        assertEquals(Mode.FALL, PredictionTolerance.classify(at(w, 0, 5, false), false, false));
        assertEquals(Mode.FLUID, PredictionTolerance.classify(at(w, 12, 0, true), true, true));
        assertEquals(Mode.CLIMB, PredictionTolerance.classify(at(w, -5, 2, false), false, false));
    }

    @Test
    public void everyModeHasABoundTierAndBasis() {
        for (Mode m : Mode.values()) {
            PredictionTolerance.Bound b = PredictionTolerance.MEASURED.bound(m);
            assertNotNull(m.toString(), b);
            assertTrue(b.oneStep() > 0);
            assertFalse(b.basis().isEmpty());
        }
        // Phase 04 validation: all six re-measured on the live 26.3 client for the D12 model; none is a 1.16-model number
        for (Mode m : Mode.values()) {
            PredictionTolerance.Bound b = PredictionTolerance.MEASURED.bound(m);
            assertEquals(m.toString(), PredictionTolerance.Tier.L, b.tier());
            assertTrue(m + ": " + b.basis(), b.basis().contains("D12 model"));
        }
    }

    /**
     * OSTINATO_BENCH=phase04: per-tick cost of each consumer path, flag off and on, interleaved ABAB.
     * Batches of 200 ticks; one sample is a batch's mean ns per tick. Writes to build/ unless OSTINATO_WRITE_DOCS=1.
     */
    @Test
    public void overheadBench() throws Exception {
        Assume.assumeTrue("phase04".equals(System.getenv("OSTINATO_BENCH")));
        int batches = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_P04_BATCHES", "3000"));
        String[] paths = {"simtrace-off", "simtrace-on", "travel-off", "travel-on"};
        List<List<Double>> samples = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) samples.add(new ArrayList<>());
        StringBuilder csv = new StringBuilder("path,batch,nsPerTick\n");
        long sink = 0;
        for (int b = -500; b < batches; b++) {
            for (int i = 0; i < paths.length; i++) {
                int k = (b & 1) == 0 ? i : paths.length - 1 - i;
                long t0 = System.nanoTime();
                sink += runBatch(k);
                double ns = (System.nanoTime() - t0) / 200.0;
                if (b >= 0) {
                    samples.get(k).add(ns);
                    csv.append(String.format(Locale.ROOT, "%s,%d,%.1f%n", paths[k], b, ns));
                }
            }
        }
        StringBuilder summary = new StringBuilder(String.format(Locale.ROOT,
                "phase04 consumer overhead, ns per tick, %d batches of 200 after 500 warmup, ABAB interleaved (sink %d)%n", batches, sink & 1));
        for (int i = 0; i < paths.length; i++) {
            List<Double> s = samples.get(i);
            summary.append(String.format(Locale.ROOT, "%s p50 %.1f p95 %.1f p99 %.1f%n", paths[i],
                    baritone.lattice.LatticeStats.percentile(s, 0.5), baritone.lattice.LatticeStats.percentile(s, 0.95),
                    baritone.lattice.LatticeStats.percentile(s, 0.99)));
        }
        Path dir = "1".equals(System.getenv("OSTINATO_WRITE_DOCS"))
                ? project("docs/ostinato2/raw/review")
                : Paths.get("build", "ostinato2", "raw", "review");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("phase04-overhead.csv"), csv.toString(), StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve("phase04-overhead-summary.txt"), summary.toString(), StandardCharsets.US_ASCII);
        System.out.print(summary);
    }

    /** 200 ticks of one consumer path: a sprint with a jump every 12 ticks on a slightly wrong model. */
    private static long runBatch(int path) {
        PlayerSim.World truth = world(0.6f);
        PlayerSim.World model = world(0.61f);
        PlayerSim real = at(truth, 0, 0, true);
        long hits = 0;
        if (path < 2) {
            SimTrace trace = new SimTrace("bench", model, path == 1 ? PredictionTolerance.MEASURED : null);
            for (int t = 0; t < 200; t++) {
                boolean jump = t % 12 == 0;
                trace.observe(real, 0);
                trace.commit(real, 0, 1, true, jump);
                real.tick(0, 1, true, jump);
            }
            return trace.firstDiverged();
        }
        PredictionTolerance tol = path == 3 ? PredictionTolerance.MEASURED : null;
        PlayerSim predicted = new PlayerSim(model);
        Mode mode = null;
        for (int t = 0; t < 200; t++) {
            boolean jump = t % 12 == 0;
            if (t > 0 && PhysicsTravel.drifted(predicted, real, tol, mode)) hits++;
            predicted.copyFrom(real);
            mode = tol == null ? null : PredictionTolerance.classify(real, true, true);
            predicted.tick(0, true, true, jump);
            real.tick(0, 1, true, jump);
        }
        return hits;
    }

    private static Path project(String rel) {
        Path path = Paths.get(rel);
        return Files.exists(path.getParent()) ? path : Paths.get("..").resolve(rel);
    }
}
