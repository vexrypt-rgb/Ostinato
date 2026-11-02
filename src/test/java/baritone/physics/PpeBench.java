/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PhysicsTelemetry;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionRequest.Input;
import baritone.api.physics.PredictionRequest.Priority;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionTicket;
import org.junit.Assume;
import org.junit.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ostinato: the engine's cost without the game (simulation, scheduling, caching; world capture is measured live).
 * Off by default: {@code gradlew test -Postinato_bench --tests "*PpeBench"}. Each line: latency avg/p50/p95/p99/max
 * in microseconds, bytes allocated and CPU per operation on the measuring thread, and collections during the run.
 */
public class PpeBench {
    private static final com.sun.management.ThreadMXBean TM = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final Input SPRINT = new Input(1, 0, true, true, false, 0, 0);

    /** Rolling ground 60..64 with hay and obsidian, and a buried patch of TNT at x -2..2, z 3..5. */
    private static final Terrain ROUGH = PpeWorlds.build(-2, 1, 2, 6, -2, 1, (x, y, z) -> {
        int h = 60 + Math.floorMod(x * 7 + z * 13, 5);
        if (y >= h) return y == h && Math.floorMod(x * 31 + z * 17, 23) == 0 ? PpeWorlds.HAY : BlockInfo.AIR_INFO;
        if (x >= -2 && x <= 2 && z >= 3 && z <= 5 && y >= h - 3) return PpeWorlds.TNT;
        return Math.floorMod(x + y + z, 9) == 0 ? PpeWorlds.OBSIDIAN : PpeWorlds.STONE;
    });

    private interface Job {
        Object run(int i);
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, b.getCollectionCount());
        return n;
    }

    private static long gcMillis() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, b.getCollectionTime());
        return n;
    }

    private static void measure(String name, int n, Job job) {
        int warm = Math.max(50, n / 5);
        for (int i = 0; i < warm; i++) job.run(i);
        long[] ns = new long[n];
        long g0 = gcCount(), gt0 = gcMillis(), a0 = TM.getCurrentThreadAllocatedBytes(), c0 = TM.getCurrentThreadCpuTime();
        Object out = null;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            out = job.run(warm + i);
            ns[i] = System.nanoTime() - t0;
        }
        long alloc = TM.getCurrentThreadAllocatedBytes() - a0, cpu = TM.getCurrentThreadCpuTime() - c0;
        long sum = 0;
        for (long v : ns) sum += v;
        Arrays.sort(ns);
        System.out.printf("BENCH %-40s n %5d | us avg %8.1f p50 %8.1f p95 %8.1f p99 %8.1f max %8.1f | %8.1f KB/op cpu %8.1f us/op | gc %d (%d ms) | %s%n",
                name, n, sum / 1e3 / n, ns[n / 2] / 1e3, ns[(int) (n * 0.95)] / 1e3, ns[(int) (n * 0.99)] / 1e3, ns[n - 1] / 1e3,
                alloc / 1024.0 / n, cpu / 1e3 / n, gcCount() - g0, gcMillis() - gt0, brief(out));
    }

    private static String brief(Object o) {
        if (o instanceof PredictionResult r) return r.status + " " + r.confidence + " h" + r.horizonTicks;
        return String.valueOf(o);
    }

    private static BodyState player(double x, double y, double z) {
        return BodyState.builder(BodyState.Type.PLAYER).pos(x, y, z).build();
    }

    private static PredictionScheduler scheduler(int capacity) {
        return new PredictionScheduler(capacity, new PredictionCache(256), new PhysicsStats());
    }

    private static Thread worker() {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("Ostinato physics")) return t;
        return null;
    }

    private static void line(String name, PhysicsTelemetry t) {
        System.out.printf("BENCH %-40s %s%n", name, t.summary());
    }

    @Test
    public void bench() throws InterruptedException {
        Assume.assumeTrue(Boolean.getBoolean("ostinato.bench"));
        Terrain flat = PpeWorlds.flat(PpeWorlds.STONE);
        List<BodyState> crowd = new ArrayList<>();
        for (int i = 0; i < 50; i++) crowd.add(player(-6 + (i % 10) * 1.3, 62 + i / 10, -6 + (i / 10) * 2.1));

        // baseline: the harness alone (what the engine adds is measured against this)
        measure("baseline (empty op)", 20000, i -> null);

        // one consumer, inline, no cache help: distinct questions
        PredictionScheduler s = scheduler(64);
        measure("fall 30 inline, cold", 5000, i -> s.predict(PredictionRequest.fall(player(0.5, 94 + i * 1e-9, 0.5), 200).build(), flat));
        measure("fall 30 inline, repeated (cache)", 20000, i -> s.predict(PredictionRequest.fall(player(0.5, 94, 0.5), 200).build(), flat));
        for (Fidelity f : new Fidelity[]{Fidelity.SCREENING, Fidelity.STANDARD, Fidelity.HIGH}) {
            measure("sprint-jump 100 ticks " + f, 3000, i -> s.predict(PredictionRequest.trajectory(player(0.5, 64 + i * 1e-9, -20), 100)
                    .input(SPRINT).fidelity(f).build(), ROUGH));
        }
        measure("arrow 60 ticks", 5000, i -> s.predict(PredictionRequest.projectile(BodyState.builder(BodyState.Type.ARROW)
                .pos(-20, 70 + i * 1e-9, 0.5).vel(2.5, 0.3, 0).build(), 60).build(), ROUGH));

        // TNT and explosions
        BodyState tnt = BodyState.builder(BodyState.Type.PRIMED_TNT).pos(0.5, 70, 4.5).fuse(80, 4).build();
        for (Fidelity f : new Fidelity[]{Fidelity.SCREENING, Fidelity.HIGH}) {
            measure("primed TNT 80 + blast " + f, 1000, i -> s.predict(PredictionRequest.trajectory(tnt.toBuilder().pos(0.5, 70 + i * 1e-9, 4.5)
                    .build(), 100).fidelity(f).build(), ROUGH));
            measure("explosion 4 vs 50 targets " + f, 1000, i -> s.predict(PredictionRequest.explosion(0.5, 62 + i * 1e-9, 4.5, 4, true)
                    .targets(crowd).fidelity(f).build(), ROUGH));
        }

        // many entities: 200 collidable boxes (boats) on the path's terrain
        double[] boxes = new double[200 * 6];
        for (int i = 0; i < 200; i++) {
            double x = -30 + (i % 20) * 3, z = -30 + (i / 20) * 6;
            System.arraycopy(new double[]{x, 64, z, x + 1.375, 64.5625, z + 1.375}, 0, boxes, i * 6, 6);
        }
        Terrain.Builder crowded = Terrain.builder(flat.minY, flat.maxY, 0, flat.sectionCount());
        for (int sx = -2; sx <= 1; sx++) for (int sy = 2; sy <= 6; sy++) for (int sz = -2; sz <= 1; sz++) crowded.put(sx, sy, sz, flat.section(sx, sy, sz));
        Terrain boats = crowded.entityBoxes(boxes).build();
        measure("sprint-jump 100 among 200 boats", 3000, i -> s.predict(PredictionRequest.trajectory(player(0.5, 64 + i * 1e-9, -20), 100)
                .input(SPRINT).build(), boats));

        // several consumers asking the same questions each tick, world unchanged vs one section changing a tick
        for (boolean changing : new boolean[]{false, true}) {
            PredictionScheduler m = scheduler(64);
            m.start();
            Terrain t = PpeWorlds.flat(PpeWorlds.STONE);
            long[] submit = new long[200];
            for (int tick = 0; tick < 200; tick++) {
                if (changing) {
                    Terrain.Builder b = Terrain.builder(t.minY, t.maxY, tick, t.sectionCount());
                    for (int sx = -2; sx <= 1; sx++) for (int sy = 2; sy <= 6; sy++) for (int sz = -2; sz <= 1; sz++) b.put(sx, sy, sz, t.section(sx, sy, sz));
                    b.put(0, 2, 0, PpeWorlds.flat(PpeWorlds.STONE).section(0, 2, 0)); // a fresh copy: new id, new key
                    t = b.build();
                }
                long t0 = System.nanoTime();
                List<PredictionTicket> ts = new ArrayList<>();
                for (int c = 0; c < 5; c++) {
                    ts.add(m.submit(PredictionRequest.fall(player(0.5, 94, 0.5), 200).consumer("c" + c).build(), t));
                    ts.add(m.submit(PredictionRequest.trajectory(player(0.5, 64, 0.5), 40).input(SPRINT).consumer("c" + c).build(), t));
                    ts.add(m.submit(PredictionRequest.explosion(3.5, 64, 3.5, 4, true).targets(List.of(player(0.5, 64, 0.5))).consumer("c" + c).build(), t));
                }
                submit[tick] = System.nanoTime() - t0;
                long until = System.nanoTime() + 10_000_000_000L;
                for (PredictionTicket k : ts) while (!k.isDone()) {
                    if (System.nanoTime() > until) throw new AssertionError("worker did not finish within 10 seconds");
                    Thread.sleep(1);
                }
            }
            PhysicsTelemetry sharedTelemetry = m.telemetry();
            m.stop();
            Arrays.sort(submit);
            System.out.printf("BENCH %-40s submit 15/tick us p50 %.1f p99 %.1f max %.1f%n", "5 consumers x 3 " + (changing ? "changing" : "same") + " world",
                    submit[100] / 1e3, submit[198] / 1e3, submit[199] / 1e3);
            line("  telemetry", sharedTelemetry);
        }

        // idle worker: started, nothing asked
        PredictionScheduler idle = scheduler(64);
        idle.start();
        Thread.sleep(200);
        Thread w = worker();
        long c0 = TM.getThreadCpuTime(w.threadId()), a0 = TM.getThreadAllocatedBytes(w.threadId());
        Thread.sleep(2000);
        System.out.printf("BENCH %-40s worker cpu %.3f ms/s, alloc %.1f KB/s%n", "idle worker (2 s)",
                (TM.getThreadCpuTime(w.threadId()) - c0) / 2e6, (TM.getThreadAllocatedBytes(w.threadId()) - a0) / 2048.0);
        idle.stop();

        // sustained load: 20 ticks a second for 10 s, 16 distinct mixed requests a tick, plus one inline emergency
        for (int perTick : new int[]{16, 96}) {
            PredictionScheduler load = scheduler(64);
            load.start();
            Thread lw = null;
            while (lw == null) lw = worker();
            long wc0 = TM.getThreadCpuTime(lw.threadId()), wa0 = TM.getThreadAllocatedBytes(lw.threadId()), g0 = gcCount(), gt0 = gcMillis();
            long[] main = new long[200];
            Priority[] pr = Priority.values();
            long start = System.nanoTime();
            for (int tick = 0; tick < 200; tick++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < perTick; k++) {
                    double d = tick * 1e-6 + k * 1e-9;
                    PredictionRequest.Builder r = switch (k % 4) {
                        case 0 -> PredictionRequest.fall(player(0.5, 90 + d, 0.5), 200);
                        case 1 -> PredictionRequest.trajectory(player(0.5, 64 + d, -20), 60).input(SPRINT);
                        case 2 -> PredictionRequest.projectile(BodyState.builder(BodyState.Type.ARROW).pos(-20, 70 + d, 0.5).vel(2.5, 0.3, 0).build(), 60);
                        default -> PredictionRequest.explosion(0.5, 62 + d, 4.5, 4, true).targets(crowd.subList(0, 8));
                    };
                    load.submit(r.priority(pr[1 + k % (pr.length - 1)]).build(), ROUGH); // COMBAT..PLANNING
                }
                load.predict(PredictionRequest.fall(player(0.5, 80 + tick * 1e-6, 0.5), 100).priority(Priority.EMERGENCY).build(), ROUGH);
                main[tick] = System.nanoTime() - t0;
                long next = start + (tick + 1) * 50_000_000L;
                while (System.nanoTime() < next) Thread.sleep(1);
            }
            double secs = (System.nanoTime() - start) / 1e9;
            long wcpu = TM.getThreadCpuTime(lw.threadId()) - wc0, walloc = TM.getThreadAllocatedBytes(lw.threadId()) - wa0;
            PhysicsTelemetry loadTelemetry = load.telemetry();
            load.stop();
            Arrays.sort(main);
            System.out.printf("BENCH %-40s main us/tick p50 %.1f p95 %.1f p99 %.1f max %.1f | worker cpu %.1f%% alloc %.1f MB/s | gc %d (%d ms)%n",
                    "sustained " + perTick + "/tick + 1 inline", main[100] / 1e3, main[190] / 1e3, main[198] / 1e3, main[199] / 1e3,
                    wcpu / 1e7 / secs, walloc / 1048576.0 / secs, gcCount() - g0, gcMillis() - gt0);
            line("  telemetry", loadTelemetry);
        }
    }
}
