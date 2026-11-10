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

package baritone.pathing.physics;

import baritone.api.utils.input.Input;
import baritone.pathing.physics.UnifiedHarness.Blocks;
import baritone.pathing.physics.UnifiedHarness.Result;
import baritone.pathing.physics.UnifiedHarness.Scenario;
import baritone.pathing.unified.UnifiedExecutor.Counter;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Ostinato 2.0 Phase 06, Tier S: the pre-registered seeded batch, legacy against unified on the same scenarios.
 * <p>
 * Six families of generated scenarios (clean, kick, slip, conflict, cancel, edge), {@link #N} each, from fixed seeds.
 * Every scenario is generated before any is run. Each trial runs both arms through {@link UnifiedHarness}, the order
 * alternating by trial. One CSV row per trial per arm; scripts/unified-batch.py judges it against the criteria in
 * BENCHMARKS.md. This asserts nothing: the criteria were written before it ran.
 * <p>
 * Off by default: {@code gradlew test -Postinato_bench --tests "*UnifiedBatchBench"}. With OSTINATO_WRITE_DOCS=1 the
 * raw files go to docs/ostinato2/raw/phase06, else to build/ostinato2/raw/phase06.
 * <p>
 * Phase 06A: OSTINATO_BATCH_SEED and OSTINATO_BATCH_WARMUP_SEED replace the two seeds, and OSTINATO_BATCH_PHASE the
 * phase06 directory name. Unset, the Phase 06 values stand.
 */
public class UnifiedBatchBench {

    static final long SEED = 6061;
    static final long WARMUP_SEED = 7071;
    static final int N = 300;
    static final int WARMUP = 20;
    static final String[] FAMILIES = {"clean", "kick", "slip", "conflict", "cancel", "edge"};
    private static final Input[] AFTER = {Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.MOVE_BACK};
    private static final Input[] BEFORE = {null, Input.MOVE_LEFT, Input.MOVE_RIGHT};

    /** One generated scenario and the parameters that made it. */
    static final class Trial {
        final String family;
        final int index;
        final Scenario scenario;
        final String params;

        Trial(String family, int index, Scenario scenario, String params) {
            this.family = family;
            this.index = index;
            this.scenario = scenario;
            this.params = params;
        }
    }

    private static Blocks longWorld() {
        return new Blocks().ground(-1, 1, -1, 20);
    }

    private static Blocks gapWorld() {
        Blocks w = new Blocks();
        for (int z = -2; z <= 2; z++) {
            w.add(0, -1, z);
        }
        for (int z = 5; z <= 8; z++) {
            w.add(0, -1, z);
        }
        return w;
    }

    private static Blocks walkway() {
        Blocks w = new Blocks();
        for (int z = -1; z <= 14; z++) {
            w.add(0, -1, z);
        }
        return w;
    }

    private static double uniform(Random r, double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    private static String fmt(String f, Object... args) {
        return String.format(Locale.ROOT, f, args);
    }

    /** Trial {@code i} of {@code family}, drawn from {@code r}. */
    static Trial generate(String family, int i, Random r) {
        String name = family + "-" + i;
        switch (family) {
            case "clean": {
                double sx = r.nextInt(7) - 3 + 0.5;
                double gx = r.nextInt(7) - 3 + 0.5, gz = r.nextInt(10) + 4.5;
                Scenario s = new Scenario(name, new Blocks().ground(-3, 3, -1, 14), sx, 0.5, gx, gz);
                return new Trial(family, i, s, fmt("sx=%.1f;gx=%.1f;gz=%.1f", sx, gx, gz));
            }
            case "kick": {
                int t = 2 + r.nextInt(14);
                double angle = uniform(r, 0, 2 * Math.PI), mag = uniform(r, 0.05, 0.6);
                Scenario s = new Scenario(name, new Blocks().ground(-4, 4, -1, 14), 0.5, 0.5, 0.5, 10.5)
                        .kick(t, mag * Math.cos(angle), mag * Math.sin(angle));
                return new Trial(family, i, s, fmt("kickTick=%d;angle=%.4f;mag=%.4f", t, angle, mag));
            }
            case "slip": {
                float slip = (float) uniform(r, 0.65, 0.98);
                double gx = r.nextInt(7) - 3 + 0.5, gz = r.nextInt(10) + 4.5;
                Scenario s = new Scenario(name, new Blocks().ground(-3, 3, -1, 14), 0.5, 0.5, gx, gz).truthSlip(slip);
                s.maxTicks = 400;
                return new Trial(family, i, s, fmt("slip=%.4f;gx=%.1f;gz=%.1f", slip, gx, gz));
            }
            case "conflict": {
                double gz = r.nextInt(6) + 5.5;
                Input after = AFTER[r.nextInt(AFTER.length)];
                Input before = BEFORE[r.nextInt(BEFORE.length)];
                int from = 1 + r.nextInt(8);
                int to = from + 3 + r.nextInt(18);
                int every = 1 + r.nextInt(3);
                Scenario s = new Scenario(name, new Blocks().ground(-4, 4, -1, 12), 0.5, 0.5, 0.5, gz).presses(before, after, from, to);
                s.pressEvery = every;
                return new Trial(family, i, s, fmt("gz=%.1f;after=%s;before=%s;from=%d;to=%d;every=%d", gz, after, before, from, to, every));
            }
            case "cancel": {
                boolean gap = r.nextBoolean();
                int t = 2 + r.nextInt(24);
                Scenario s = gap
                        ? new Scenario(name, gapWorld(), 0.5, 0.5, 0.5, 6.5)
                        : new Scenario(name, longWorld(), 0.5, 0.5, 0.5, 16.5);
                s.cancelAt(t);
                return new Trial(family, i, s, fmt("world=%s;cancelAt=%d", gap ? "gap" : "long", t));
            }
            case "edge": {
                int t = 2 + r.nextInt(14);
                double vx = (r.nextBoolean() ? 1 : -1) * uniform(r, 0.1, 1.0);
                Scenario s = new Scenario(name, walkway(), 0.5, 0.5, 0.5, 12.5).kick(t, vx, 0);
                return new Trial(family, i, s, fmt("kickTick=%d;vx=%.4f", t, vx));
            }
            default:
                throw new IllegalArgumentException(family);
        }
    }

    /** Every family's trials, from {@code seed} plus the family's index, all made before any runs. */
    static List<Trial> generateAll(long seed, int n) {
        List<Trial> all = new ArrayList<>();
        for (int f = 0; f < FAMILIES.length; f++) {
            Random r = new Random(seed + f);
            for (int i = 0; i < n; i++) {
                all.add(generate(FAMILIES[f], i, r));
            }
        }
        return all;
    }

    private static Path project(String rel) {
        Path path = Paths.get(rel);
        return Files.exists(path.getParent()) ? path : Paths.get("..").resolve(rel);
    }

    private static final String HEAD;

    static {
        StringBuilder b = new StringBuilder("family,trial,arm,order,end,ticks,plans,driftReplans,collided,kickedAt,cancelFrom,cancelRefusals,cancelLatency,"
                + "onGroundAtEnd,endX,endY,endZ,keysDown,leased,state,cause,preLanded,conflictLanded,jumps,sprints,maxSpeed,"
                + "tickN,tickP50Ns,tickP95Ns,tickP99Ns,tickMaxNs,planNs,cpuNs,allocBytes,retryNs,retryNsMax");
        for (Counter c : Counter.values()) {
            b.append(',').append(c);
        }
        HEAD = b.append(",params").toString();
    }

    private static long env(String name, long fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
    }

    private static String phase() {
        String v = System.getenv("OSTINATO_BATCH_PHASE");
        if (v == null || v.isBlank()) {
            return "phase06";
        }
        if (!v.matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("OSTINATO_BATCH_PHASE must be [a-z0-9]+: " + v);
        }
        return v;
    }

    private static String row(Trial t, Result r, int order, long cpu, long alloc) {
        StringBuilder b = new StringBuilder();
        b.append(t.family).append(',').append(t.index).append(',').append(r.unified ? "unified" : "legacy").append(',').append(order)
                .append(',').append(r.end).append(',').append(r.ticks).append(',').append(r.plans).append(',').append(r.driftReplans)
                .append(',').append(r.collidedTicks).append(',').append(r.kickedAt).append(',').append(r.cancelFrom)
                .append(',').append(r.cancelRefusals).append(',').append(r.cancelLatency).append(',').append(r.onGroundAtEnd)
                .append(',').append(fmt("%.4f", r.endX)).append(',').append(fmt("%.4f", r.endY)).append(',').append(fmt("%.4f", r.endZ))
                .append(',').append(r.keys.down.isEmpty() ? "" : r.keys.down.toString().replace(", ", "+").replace("[", "").replace("]", ""))
                .append(',').append(r.ux != null && r.ux.leased())
                .append(',').append(r.ux == null ? "" : r.ux.state()).append(',').append(r.ux == null ? "" : r.ux.lastCause())
                .append(',').append(r.preLanded).append(',').append(r.conflictLanded).append(',').append(r.jumps).append(',').append(r.sprints)
                .append(',').append(fmt("%.4f", r.maxSpeed)).append(',').append(r.tickNanos.size())
                .append(',').append(UnifiedHarness.quantile(r.tickNanos, 0.50)).append(',').append(UnifiedHarness.quantile(r.tickNanos, 0.95))
                .append(',').append(UnifiedHarness.quantile(r.tickNanos, 0.99)).append(',').append(UnifiedHarness.quantile(r.tickNanos, 1.0))
                .append(',').append(r.planNanos).append(',').append(cpu).append(',').append(alloc)
                .append(',').append(r.retryNanos).append(',').append(r.retryNanosMax);
        for (Counter c : Counter.values()) {
            b.append(',').append(r.count(c));
        }
        return b.append(',').append(t.params).toString();
    }

    @Test
    public void batch() throws IOException {
        Assume.assumeTrue("-Postinato_bench runs this", Boolean.getBoolean("ostinato.bench"));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();

        // Warm-up, from its own seed, thrown away.
        long seed = env("OSTINATO_BATCH_SEED", SEED);
        long warmupSeed = env("OSTINATO_BATCH_WARMUP_SEED", WARMUP_SEED);
        String phase = phase();
        List<Trial> warm = generateAll(warmupSeed, WARMUP / FAMILIES.length + 1);
        for (Trial t : warm) {
            UnifiedHarness.run(t.scenario, false);
            UnifiedHarness.run(t.scenario, true);
        }

        List<Trial> trials = generateAll(seed, N);
        Path dir = "1".equals(System.getenv("OSTINATO_WRITE_DOCS")) ? project("docs/ostinato2/raw/" + phase) : Paths.get("build", "ostinato2", "raw", phase);
        Files.createDirectories(dir);
        long started = System.nanoTime();
        try (BufferedWriter out = Files.newBufferedWriter(dir.resolve("headless-batch.csv"), StandardCharsets.UTF_8)) {
            out.write(HEAD);
            out.newLine();
            for (Trial t : trials) {
                // Legacy first on even trials, unified first on odd.
                boolean unifiedFirst = (t.index & 1) == 1;
                for (int k = 0; k < 2; k++) {
                    boolean unified = unifiedFirst == (k == 0);
                    long cpu0 = bean.getCurrentThreadCpuTime();
                    long alloc0 = bean.getThreadAllocatedBytes(thread);
                    Result r = UnifiedHarness.run(t.scenario, unified);
                    long cpu = bean.getCurrentThreadCpuTime() - cpu0;
                    long alloc = bean.getThreadAllocatedBytes(thread) - alloc0;
                    out.write(row(t, r, k, cpu, alloc));
                    out.newLine();
                }
            }
        }
        long took = (System.nanoTime() - started) / 1_000_000;
        String summary = "UNIFIED-BATCH phase=" + phase + " seed=" + seed + " warmupSeed=" + warmupSeed + " n=" + N + " families=" + String.join("/", FAMILIES)
                + " trials=" + trials.size() + " runs=" + trials.size() * 2 + " wallMs=" + took
                + " java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") + " cpus=" + Runtime.getRuntime().availableProcessors();
        System.out.println(summary);
        Files.writeString(dir.resolve("headless-batch-run.txt"), summary + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
