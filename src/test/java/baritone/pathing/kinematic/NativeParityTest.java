package baritone.pathing.kinematic;

import baritone.natives.NativeLib;
import baritone.pathing.kinematic.TasBrute.BoxWorld;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ostinato: the Rust library gives the Java code's answers to the bit. Runs every {@link TasBruteTest} course (inherited)
 * with each native search checked against the Java one, then randomized worlds, raw simulator ticks and the trig.
 * Skipped when the library is not loaded, unless {@code -Postinato_native_require} (CI) makes that a failure.
 */
public class NativeParityTest extends TasBruteTest {

    static NativeLib lib;

    @BeforeClass
    public static void load() {
        lib = NativeLib.get();
        if (lib == null && Boolean.getBoolean("ostinato.native.require")) {
            fail("native library required but " + NativeLib.status());
        }
        Assume.assumeTrue("native library: " + NativeLib.status(), lib != null);
        NativeTas.verify = true;
    }

    @AfterClass
    public static void done() {
        NativeTas.verify = false;
        System.out.println("native parity: " + NativeTas.checked.get() + " searches checked against Java");
    }

    /** {@link BoxWorld} plus water and honey, which BoxWorld does not model. */
    static final class FlagWorld implements PlayerSim.World {
        final BoxWorld boxes = new BoxWorld();
        final Set<Long> water = new HashSet<>(), sticky = new HashSet<>();

        static long k(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            boxes.collect(minX, minY, minZ, maxX, maxY, maxZ, out);
        }

        @Override
        public boolean cellBoxes(int x, int y, int z, List<double[]> out) {
            return boxes.cellBoxes(x, y, z, out);
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return boxes.slipperiness(x, y, z);
        }

        @Override
        public boolean bouncy(int x, int y, int z) {
            return boxes.bouncy(x, y, z);
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return boxes.climbable(x, y, z);
        }

        @Override
        public float speedFactor(int x, int y, int z) {
            return boxes.speedFactor(x, y, z);
        }

        @Override
        public float jumpFactor(int x, int y, int z) {
            return boxes.jumpFactor(x, y, z);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return water.contains(k(x, y, z));
        }

        @Override
        public boolean sticky(int x, int y, int z) {
            return sticky.contains(k(x, y, z));
        }
    }

    static final double[][] SOUL = {{0, 0, 0, 1, 14 / 16.0, 1}};
    static final double[][] HONEY = {{1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 15 / 16.0, 15 / 16.0}};
    static final double[][] PLATE_N = {{0, 0, 1 - 3 / 16.0, 1, 1, 1}};

    /** A parkour-ish field: random tops over x -3..3, z -2..9, every kind of block the simulator treats specially. */
    static FlagWorld randomWorld(Random r) {
        FlagWorld w = new FlagWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 9; z++) {
                if (z > 0 && r.nextInt(3) != 0) {
                    continue; // mostly gaps past the take-off
                }
                int y = z <= 0 ? -1 : r.nextInt(3) - 2;
                switch (r.nextInt(10)) {
                    case 0 -> w.boxes.put(x, y, z, BoxWorld.BOTTOM_SLAB);
                    case 1 -> w.boxes.put(x, y, z, BoxWorld.CANDLE);
                    case 2 -> w.boxes.slime(x, y, z);
                    case 3 -> w.boxes.put(x, y, z, BoxWorld.FULL).slippery(x, y, z, 0.98f);
                    case 4 -> w.boxes.put(x, y, z, SOUL).factors(x, y, z, 0.4f, 1);
                    case 5 -> {
                        w.boxes.put(x, y, z, HONEY).factors(x, y, z, 0.4f, 0.5f);
                        w.sticky.add(FlagWorld.k(x, y, z));
                    }
                    case 6 -> w.boxes.ladder(x, y + 1, z, PLATE_N).put(x, y, z, BoxWorld.FULL);
                    case 7 -> w.boxes.pane(x, y, z, r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean());
                    default -> w.boxes.put(x, y, z, BoxWorld.FULL);
                }
                if (r.nextInt(12) == 0) {
                    w.water.add(FlagWorld.k(x, y + 1, z));
                }
                if (r.nextInt(8) == 0) {
                    w.boxes.put(x, y + 3, z, BoxWorld.FULL); // head hitters
                }
            }
        }
        // the take-off column is plain ground
        w.boxes.fill(0, -1, -2, 0, -1, 0, BoxWorld.FULL);
        for (int z = -2; z <= 0; z++) {
            w.boxes.put(0, 2, z, null);
        }
        return w;
    }

    static PlayerSim standing(PlayerSim.World w, double x, double y, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = y;
        s.z = z;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        return s;
    }

    @Test
    public void randomWorldsSearchTheSame() {
        Random r = new Random(0x0571A70);
        int before = NativeTas.checked.get(), found = 0, threads = NativeTas.threads;
        try {
            for (int i = 0; i < 60; i++) {
                NativeTas.threads = 1 + i % 4; // the answer must not depend on the thread count
                FlagWorld w = randomWorld(r);
                TasBrute b = new TasBrute(w);
                b.beam = 300 + r.nextInt(600);
                b.maxTicks = 20 + r.nextInt(16);
                b.maxTurn = r.nextBoolean() ? 30 : 32;
                b.allowBack = r.nextBoolean();
                if (r.nextBoolean()) {
                    b.floorY = -3.5;
                }
                PlayerSim s = standing(w, 0.3 + 0.4 * r.nextDouble(), 0, -1.5 + 1.8 * r.nextDouble());
                if (r.nextBoolean()) {
                    s.sprinting = true;
                    s.vz = 0.1 + 0.25 * r.nextDouble();
                }
                TasBrute.Landing goal = new TasBrute.Landing(r.nextInt(5) - 2, r.nextInt(3) - 1, 2 + r.nextInt(6));
                if (r.nextInt(6) == 0) {
                    goal.grab();
                }
                TasBrute.Result res = b.search(s, (float) (r.nextDouble() * 60 - 30), goal); // checked inside
                if (res != null) {
                    found++;
                }
            }
        } finally {
            NativeTas.threads = threads;
        }
        int checked = NativeTas.checked.get() - before;
        System.out.println("random worlds: " + checked + " of 60 searched natively, " + found + " found a plan");
        assertTrue("most random searches stay inside the snapshot", checked >= 50);
    }

    @Test
    public void simulatorTicksTheSame() throws Throwable {
        Random r = new Random(1234);
        for (int i = 0; i < 40; i++) {
            FlagWorld w = randomWorld(r);
            w.boxes.fill(-30, -8, -30, 30, -8, 40, BoxWorld.FULL); // something to land on
            PlayerSim s = standing(w, 0.5, 0, -1.5);
            s.speedScale = r.nextInt(4) == 0 ? 1.2 : 1;
            s.jumpBoost = r.nextInt(4) == 0 ? 0.1 : 0;
            s.movementEfficiency = r.nextInt(4) == 0 ? 0.5 : 0;
            int n = 80;
            float[] yaw = new float[n];
            int[] input = new int[n], strafe = new int[n];
            byte[] sprint = new byte[n], jump = new byte[n];
            float y0 = (float) (r.nextDouble() * 360 - 180);
            for (int t = 0; t < n; t++) {
                y0 += (float) (r.nextGaussian() * 15);
                yaw[t] = y0;
                input[t] = r.nextInt(3) - 1;
                strafe[t] = r.nextInt(5) == 0 ? r.nextInt(3) - 1 : 0;
                sprint[t] = (byte) r.nextInt(2);
                jump[t] = (byte) (r.nextInt(3) == 0 ? 1 : 0);
            }
            NativeTas.Snapshot snap = NativeTas.Snapshot.take(w, -35, -10, -35, 71, 22, 81);
            double[] got;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment[] seg = snap.segments(arena);
                MemorySegment out = arena.allocate(JAVA_DOUBLE, (long) n * NativeTas.STATE);
                int rc = (int) lib.simRun.invoke(seg[0], seg[1], seg[2], snap.boxCount, seg[3], seg[4], arena.allocateFrom(JAVA_DOUBLE, NativeTas.state(s)),
                        n, arena.allocateFrom(JAVA_FLOAT, yaw), arena.allocateFrom(JAVA_INT, input), arena.allocateFrom(JAVA_INT, strafe),
                        arena.allocateFrom(JAVA_BYTE, sprint), arena.allocateFrom(JAVA_BYTE, jump), out);
                assertEquals("world " + i, n, rc);
                got = out.toArray(JAVA_DOUBLE);
            }
            for (int t = 0; t < n; t++) {
                s.tick(yaw[t], input[t], strafe[t], sprint[t] != 0, jump[t] != 0);
                double[] want = NativeTas.state(s);
                double[] have = Arrays.copyOfRange(got, t * NativeTas.STATE, (t + 1) * NativeTas.STATE);
                if (!Arrays.equals(want, have)) {
                    fail("world " + i + " tick " + t + ": Java " + Arrays.toString(want) + " native " + Arrays.toString(have));
                }
            }
        }
    }

    @Test
    public void strictTrigTheSame() throws Throwable {
        Random r = new Random(99);
        List<Double> xs = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            xs.add(Math.toRadians((float) (r.nextDouble() * 20000 - 10000)));
        }
        for (int k = -2000; k <= 2000; k++) {
            double m = k * Math.PI / 4;
            xs.add(m);
            xs.add(Math.nextUp(m));
            xs.add(Math.nextDown(m));
        }
        double[] special = {0, -0.0, 1e-300, -1e-9, 3e-8, 0.3, 0.78125, 0.785398, 2.356194, 1e5, -8e5, 823549.0, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double v : special) {
            xs.add(v);
        }
        double[] x = xs.stream().mapToDouble(Double::doubleValue).toArray();
        double[] got;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, 2L * x.length);
            assertEquals(x.length, (int) lib.sincos.invoke(arena.allocateFrom(JAVA_DOUBLE, x), x.length, out));
            got = out.toArray(JAVA_DOUBLE);
        }
        double[] want = new double[2 * x.length];
        for (int i = 0; i < x.length; i++) {
            want[2 * i] = StrictMath.sin(x[i]);
            want[2 * i + 1] = StrictMath.cos(x[i]);
        }
        for (int i = 0; i < want.length; i++) {
            if (Double.doubleToRawLongBits(want[i]) != Double.doubleToRawLongBits(got[i]) && !(Double.isNaN(want[i]) && Double.isNaN(got[i]))) {
                fail((i % 2 == 0 ? "sin(" : "cos(") + x[i / 2] + "): StrictMath " + want[i] + " native " + got[i]);
            }
        }
        // past fdlibm's medium range the native side declines rather than guess
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(NativeLib.UNSUPPORTED, (int) lib.sincos.invoke(arena.allocateFrom(JAVA_DOUBLE, 1e7), 1, arena.allocate(JAVA_DOUBLE, 2)));
        }
    }
}
