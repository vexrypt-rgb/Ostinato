package baritone.pathing.kinematic;

import baritone.natives.NativeLib;
import baritone.pathing.kinematic.TasBrute.BoxWorld;
import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Ostinato: Java against Rust on the {@link TasBruteTest} courses, each timed after a warm-up; the native time includes
 * copying the snapshot. Off by default: {@code gradlew test -Postinato_bench --tests "*NativeBench"}.
 */
public class NativeBench {

    private record Course(String name, Supplier<TasBrute> brute, PlayerSim start, float yaw, TasBrute.Landing goal) {
    }

    private static PlayerSim standing(PlayerSim.World w, double x, double y, double z, double vz) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = y;
        s.z = z;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        s.vz = vz;
        s.sprinting = vz > 0;
        return s;
    }

    private static TasBrute brute(PlayerSim.World w, int beam, int ticks, float turn, boolean back) {
        TasBrute b = new TasBrute(w);
        b.beam = beam;
        b.maxTicks = ticks;
        b.maxTurn = turn;
        b.allowBack = back;
        return b;
    }

    private static Course[] courses() {
        BoxWorld gap = new BoxWorld().fill(0, -1, -3, 0, -1, 0, BoxWorld.FULL).put(0, -1, 4, BoxWorld.FULL);
        BoxWorld neo = new BoxWorld().fill(0, -1, -3, 0, -1, 0, BoxWorld.FULL).fill(0, 0, 1, 0, 1, 3, BoxWorld.FULL).put(0, -1, 4, BoxWorld.FULL);
        BoxWorld course = new BoxWorld().fill(-1, -1, 0, 1, -1, 2, BoxWorld.FULL).fill(-3, -1, 3, 0, 1, 5, BoxWorld.FULL).put(0, -1, 6, BoxWorld.FULL);
        BoxWorld lip = new BoxWorld().fill(-1, -1, 0, 1, -1, 2, BoxWorld.FULL).put(0, -1, 5, BoxWorld.FULL).put(0, 2, 2, BoxWorld.FULL);
        BoxWorld candle = new BoxWorld().put(0, 0, 0, BoxWorld.CANDLE).put(-3, 0, 0, BoxWorld.CANDLE);
        BoxWorld neo2 = new BoxWorld().fill(0, -1, -3, 0, -1, 0, BoxWorld.FULL).fill(0, 0, 1, 0, 1, 2, BoxWorld.FULL).put(0, -1, 3, BoxWorld.FULL);
        return new Course[]{
                new Course("gap3 (3000 x 45)", () -> brute(gap, 3000, 45, 30, false), standing(gap, 0.5, 0, -2.5, 0), 0, new TasBrute.Landing(0, 0, 4)),
                new Course("neo2 aligned (3000 x 45)", () -> brute(neo2, 3000, 45, 30, false), standing(neo2, 0.5, 0, -2.5, 0), 0, new TasBrute.Landing(0, 0, 3)),
                new Course("neo3 aligned, none (3000 x 45)", () -> brute(neo, 3000, 45, 30, false), standing(neo, 0.5, 0, -2.5, 0), 0, new TasBrute.Landing(0, 0, 4)),
                new Course("neo3 momentum, none (8000 x 36)", () -> brute(neo, 8000, 36, 32, true), standing(neo, 0.5, 0, -0.7, 0.30), 0, new TasBrute.Landing(0, 0, 4)),
                new Course("course neo3 east edge (6000 x 48)", () -> brute(course, 6000, 48, 32, true), standing(course, 1.5, 0, 2.0, 0.28), 0, new TasBrute.Landing(0, 0, 6)),
                new Course("head-hitter lip, executor (2500 x 22)", () -> brute(lip, 2500, 22, 32, true), standing(lip, 0.5, 0, 2.2, 0), 0, new TasBrute.Landing(0, 0, 5)),
                new Course("candle to candle (3000 x 45)", () -> brute(candle, 3000, 45, 30, false), standing(candle, 0.5, 6 / 16.0, 0.5, 0), 90, new TasBrute.Landing(-3, 1, 0)),
        };
    }

    private static double median(long[] ns) {
        long[] s = ns.clone();
        Arrays.sort(s);
        return s[s.length / 2] / 1e6;
    }

    @Test
    public void javaAgainstRust() {
        Assume.assumeTrue("-Postinato_bench runs this", Boolean.getBoolean("ostinato.bench"));
        Assume.assumeTrue("native library: " + NativeLib.status(), NativeLib.get() != null);
        int warm = 2, reps = 5, threads = NativeTas.threads;
        System.out.printf("%-40s %10s %10s %10s %8s  %s%n", "course", "Java ms", "Rust 1t", "Rust " + threads + "t", "speedup", "plan");
        try {
            for (Course c : courses()) {
                long[] java = new long[reps], one = new long[reps], rust = new long[reps];
                TasBrute.Result plan = null;
                for (int i = -warm; i < reps; i++) {
                    TasBrute j = c.brute.get();
                    long t0 = System.nanoTime();
                    TasBrute.Result a = j.searchJava(new PlayerSim(c.start.world()).copyFrom(c.start), c.yaw, c.goal);
                    long t1 = System.nanoTime();
                    NativeTas.threads = 1;
                    TasBrute.Result o = c.brute.get().search(new PlayerSim(c.start.world()).copyFrom(c.start), c.yaw, c.goal);
                    long t2 = System.nanoTime();
                    NativeTas.threads = threads;
                    TasBrute.Result b = c.brute.get().search(new PlayerSim(c.start.world()).copyFrom(c.start), c.yaw, c.goal);
                    long t3 = System.nanoTime();
                    NativeTas.check(o, a);
                    NativeTas.check(b, a);
                    if (i >= 0) {
                        java[i] = t1 - t0;
                        one[i] = t2 - t1;
                        rust[i] = t3 - t2;
                    }
                    plan = b;
                }
                double jm = median(java), om = median(one), rm = median(rust);
                System.out.printf("%-40s %10.1f %10.1f %10.1f %7.1fx  %s%n", c.name, jm, om, rm, jm / rm, plan == null ? "none" : plan.ticks() + " ticks");
            }
        } finally {
            NativeTas.threads = threads;
        }
    }
}
