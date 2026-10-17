package baritone.pathing.kinematic;

import baritone.natives.NativeLib;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Ostinato: {@link TasBrute#search} in the native library ({@code native/src/tas.rs}), over a snapshot of the cells around
 * the jump. Gives exactly the Java search's answer, or null when it cannot: no library, a world that cannot list its
 * cells, a search that leaves even the largest snapshot, or any error. The caller then runs the Java search.
 */
final class NativeTas {

    /** The native search's answer; {@code plan} is null when there is no plan. */
    record Answer(TasBrute.Result plan) {
    }

    /** Snapshot margins (blocks) tried in turn when a search leaves the one before. */
    static final int[] PADS = {6, 12, 24};
    /** No snapshot larger than this (cells): copying it would cost more than the search saves. */
    static final int MAX_CELLS = 400_000;

    private NativeTas() {
    }

    static Answer search(TasBrute b, PlayerSim.World world, PlayerSim start, float yaw0, TasBrute.Landing goal) {
        NativeLib lib = NativeLib.get();
        if (lib == null) {
            return null;
        }
        double floor = Math.max(b.floorY, Math.min(start.y, goal.y - 1) - 1.0);
        for (int pad : PADS) {
            int x0 = PlayerSim.floor(Math.min(start.x, goal.x)) - pad, x1 = PlayerSim.floor(Math.max(start.x, goal.x + 1)) + pad;
            int z0 = PlayerSim.floor(Math.min(start.z, goal.z)) - pad, z1 = PlayerSim.floor(Math.max(start.z, goal.z + 1)) + pad;
            int y0 = PlayerSim.floor(Math.min(floor, start.y)) - 2 - pad / 2, y1 = PlayerSim.floor(Math.max(start.y, goal.y)) + 3 + pad / 2;
            Snapshot snap = Snapshot.take(world, x0, y0, z0, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
            if (snap == null) {
                return null;
            }
            int r;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment[] s = snap.segments(arena);
                MemorySegment state = arena.allocateFrom(JAVA_DOUBLE, state(start));
                MemorySegment params = arena.allocateFrom(JAVA_DOUBLE, yaw0, b.beam, b.maxTicks, b.maxTurn, b.allowBack ? 1 : 0, b.floorY,
                        goal.x, goal.y, goal.z, goal.grab ? 1 : 0, threads, b.allowStrafe ? 1 : 0);
                MemorySegment turns = arena.allocateFrom(JAVA_FLOAT, b.turns);
                int cap = Math.max(1, b.maxTicks);
                MemorySegment oy = arena.allocate(JAVA_FLOAT, cap), oi = arena.allocate(JAVA_INT, cap), oj = arena.allocate(JAVA_BYTE, cap);
                MemorySegment os = arena.allocate(JAVA_DOUBLE, STATE);
                r = (int) lib.tasSearch.invoke(s[0], s[1], s[2], snap.boxCount, s[3], s[4], state, params, turns, b.turns.length, oy, oi, oj, cap, os);
                if (r >= 0) {
                    float[] yaw = oy.asSlice(0, 4L * r).toArray(JAVA_FLOAT);
                    // each tick's keys come packed as forward + 10 * strafe
                    int[] input = oi.asSlice(0, 4L * r).toArray(JAVA_INT), strafe = new int[r];
                    byte[] j = oj.asSlice(0, r).toArray(JAVA_BYTE);
                    boolean[] jump = new boolean[r];
                    for (int t = 0; t < r; t++) {
                        jump[t] = j[t] != 0;
                        strafe[t] = Math.round(input[t] / 10f);
                        input[t] -= 10 * strafe[t];
                    }
                    PlayerSim end = new PlayerSim(world);
                    load(end, os.toArray(JAVA_DOUBLE));
                    return new Answer(new TasBrute.Result(yaw, input, strafe, jump, end));
                }
            } catch (Throwable t) {
                NativeLib.disable("TasBrute search threw " + t);
                return null;
            }
            if (r == NativeLib.NONE) {
                return new Answer(null);
            }
            if (r == NativeLib.PANIC) {
                NativeLib.disable("TasBrute search panicked");
            }
            if (r != NativeLib.OUT_OF_REGION) {
                return null;
            }
        }
        return null;
    }

    /**
     * Threads the native search expands each beam layer on. The answer is the same for any count (the children are
     * merged back in the single-threaded order); half the cores, at most 4, leaves room for the game.
     */
    static volatile int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    /** Tests: every native answer is checked against the Java search ({@link #check}). */
    static volatile boolean verify;
    static final java.util.concurrent.atomic.AtomicInteger checked = new java.util.concurrent.atomic.AtomicInteger();

    /** Throws unless the two plans are the same to the bit: every tick's input and the end state. */
    static void check(TasBrute.Result rust, TasBrute.Result ref) {
        checked.incrementAndGet();
        if (rust == null || ref == null) {
            if (rust != ref) {
                throw new AssertionError("native " + rust + " but Java " + ref);
            }
            return;
        }
        if (!Arrays.equals(rust.yaw, ref.yaw) || !Arrays.equals(rust.input, ref.input) || !Arrays.equals(rust.strafe, ref.strafe)
                || !Arrays.equals(rust.jump, ref.jump) || !Arrays.equals(state(rust.end), state(ref.end))) {
            throw new AssertionError("native plan " + rust + " differs from Java " + ref);
        }
    }

    static final int STATE = 15;

    static double[] state(PlayerSim s) {
        return new double[]{s.x, s.y, s.z, s.vx, s.vy, s.vz, s.onGround ? 1 : 0, s.sprinting ? 1 : 0, s.collidedH ? 1 : 0,
                s.swimming ? 1 : 0, s.jumpTicks, s.speedScale, s.jumpStrength, s.jumpBoost, s.movementEfficiency};
    }

    static void load(PlayerSim s, double[] v) {
        s.x = v[0];
        s.y = v[1];
        s.z = v[2];
        s.vx = v[3];
        s.vy = v[4];
        s.vz = v[5];
        s.onGround = v[6] != 0;
        s.sprinting = v[7] != 0;
        s.collidedH = v[8] != 0;
        s.swimming = v[9] != 0;
        s.jumpTicks = (int) v[10];
        s.speedScale = v[11];
        s.jumpStrength = v[12];
        s.jumpBoost = v[13];
        s.movementEfficiency = v[14];
    }

    /**
     * A box of cells copied out of a {@link PlayerSim.World}: per cell its boxes (through {@link PlayerSim.World#cellBoxes},
     * the same source {@code collect} reads), a flag byte and friction/speed/jump factors. Cells go x, then y, then z.
     */
    static final class Snapshot {
        static final int BOUNCY = 1, WATER = 2, CLIMBABLE = 4, STICKY = 8;

        final int[] region;
        final int[] boxStart;
        final double[] boxes;
        final int boxCount;
        final byte[] flags;
        final float[] factors;

        private Snapshot(int[] region, int[] boxStart, double[] boxes, int boxCount, byte[] flags, float[] factors) {
            this.region = region;
            this.boxStart = boxStart;
            this.boxes = boxes;
            this.boxCount = boxCount;
            this.flags = flags;
            this.factors = factors;
        }

        /** Null if the world cannot list a cell, or the box is too big to be worth copying. */
        static Snapshot take(PlayerSim.World w, int x0, int y0, int z0, int nx, int ny, int nz) {
            if (nx <= 0 || ny <= 0 || nz <= 0 || (long) nx * ny * nz > MAX_CELLS) {
                return null;
            }
            int n = nx * ny * nz;
            int[] start = new int[n + 1];
            double[] boxes = new double[6 * 256];
            int nb = 0;
            byte[] flags = new byte[n];
            float[] factors = new float[3 * n];
            List<double[]> cell = new ArrayList<>();
            int i = 0;
            for (int x = x0; x < x0 + nx; x++) {
                for (int y = y0; y < y0 + ny; y++) {
                    for (int z = z0; z < z0 + nz; z++, i++) {
                        cell.clear();
                        if (!w.cellBoxes(x, y, z, cell)) {
                            return null;
                        }
                        if ((nb + cell.size()) * 6 > boxes.length) {
                            boxes = Arrays.copyOf(boxes, Math.max(boxes.length * 2, (nb + cell.size()) * 6));
                        }
                        for (double[] b : cell) {
                            System.arraycopy(b, 0, boxes, nb * 6, 6);
                            nb++;
                        }
                        start[i + 1] = nb;
                        flags[i] = (byte) ((w.bouncy(x, y, z) ? BOUNCY : 0) | (w.water(x, y, z) ? WATER : 0)
                                | (w.climbable(x, y, z) ? CLIMBABLE : 0) | (w.sticky(x, y, z) ? STICKY : 0));
                        factors[3 * i] = w.slipperiness(x, y, z);
                        factors[3 * i + 1] = w.speedFactor(x, y, z);
                        factors[3 * i + 2] = w.jumpFactor(x, y, z);
                    }
                }
            }
            return new Snapshot(new int[]{x0, y0, z0, nx, ny, nz}, start, boxes, nb, flags, factors);
        }

        /** Region, box offsets, boxes, flags, factors, copied into {@code arena}. */
        MemorySegment[] segments(Arena arena) {
            MemorySegment boxSeg = arena.allocate(JAVA_DOUBLE, Math.max(1, boxCount * 6L));
            MemorySegment.copy(boxes, 0, boxSeg, JAVA_DOUBLE, 0, boxCount * 6);
            return new MemorySegment[]{arena.allocateFrom(JAVA_INT, region), arena.allocateFrom(JAVA_INT, boxStart), boxSeg,
                    arena.allocateFrom(JAVA_BYTE, flags), arena.allocateFrom(JAVA_FLOAT, factors)};
        }
    }
}
