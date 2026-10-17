package baritone.pathing.kinematic;

import baritone.natives.NativeLib;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Ostinato: {@code PhysicsPathfinder.plan}, {@link JumpSearch#search} / {@link JumpSearch#searchStraight} and {@link
 * MomentumHop#find} in the native library ({@code native/src/physics.rs}, {@code jump.rs}), over a {@link
 * NativeTas.Snapshot} of the cells around the search. Each gives exactly the Java search's answer, or a "don't know"
 * (null, or {@link #UNKNOWN}) when it cannot: no library, a world that cannot list its cells, a search that leaves even
 * the largest snapshot, or any error. The caller then runs the Java search.
 */
public final class NativePlanners {

    private NativePlanners() {
    }

    /** Tests: every native answer is checked against the Java search. */
    public static volatile boolean verify;
    public static final java.util.concurrent.atomic.AtomicInteger checked = new java.util.concurrent.atomic.AtomicInteger();

    /** {@link #physicsPlan}'s answer: action indices per held step, or null for "no plan". */
    public record Steps(byte[] steps) {
    }

    /** Longest plan the native planner hands back (held steps). */
    static final int MAX_STEPS = 1 << 16;

    /**
     * {@code PhysicsPathfinder.plan0} in Rust: indices into its action list, one per held step, wrapped so a "no plan"
     * answer differs from "don't know" (null).
     */
    public static Steps physicsPlan(PlayerSim.World world, PlayerSim start, double gx, double gy, double gz, double radius, int maxNodes) {
        NativeLib lib = NativeLib.get();
        if (lib == null) {
            return null;
        }
        for (int pad : NativeTas.PADS) {
            int x0 = PlayerSim.floor(Math.min(start.x, gx)) - pad, x1 = PlayerSim.floor(Math.max(start.x, gx)) + pad;
            int z0 = PlayerSim.floor(Math.min(start.z, gz)) - pad, z1 = PlayerSim.floor(Math.max(start.z, gz)) + pad;
            int y0 = PlayerSim.floor(Math.min(start.y, gy)) - 3 - pad / 2, y1 = PlayerSim.floor(Math.max(start.y, gy)) + 3 + pad / 2;
            NativeTas.Snapshot snap = NativeTas.Snapshot.take(world, x0, y0, z0, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
            if (snap == null) {
                return null;
            }
            int r;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment[] s = snap.segments(arena);
                MemorySegment state = arena.allocateFrom(JAVA_DOUBLE, NativeTas.state(start));
                MemorySegment params = arena.allocateFrom(JAVA_DOUBLE, gx, gy, gz, radius, maxNodes);
                MemorySegment out = arena.allocate(JAVA_BYTE, MAX_STEPS);
                r = (int) lib.physicsPlan.invoke(s[0], s[1], s[2], snap.boxCount, s[3], s[4], state, params, out, MAX_STEPS);
                if (r >= 0) {
                    return new Steps(out.asSlice(0, r).toArray(JAVA_BYTE));
                }
            } catch (Throwable t) {
                NativeLib.disable("physics plan threw " + t);
                return null;
            }
            if (r == NativeLib.NONE) {
                return new Steps(null);
            }
            if (!retry(r, "physics plan")) {
                return null;
            }
        }
        return null;
    }

    /** {@link #jumpSearch}'s modes. */
    public static final int FULL = 0, LOCAL = 1, STRAIGHT = 2;
    /** {@link #jumpSearch}: the library could not answer; run the Java search. */
    public static final int UNKNOWN = -1;

    /**
     * {@link JumpSearch#search} ({@link #FULL} or {@link #LOCAL}) or {@link JumpSearch#searchStraight} ({@link
     * #STRAIGHT}) in Rust, leaving {@code js} as the Java search would: plan, ticks, miss, landSpeed, the destination
     * (which {@link JumpSearch#anyDest} rewrites) and the sim's state. Returns 1 found, 0 not, or {@link #UNKNOWN} with
     * {@code js} untouched.
     */
    static int jumpSearch(JumpSearch js, PlayerSim start, int mode) {
        NativeLib lib = NativeLib.get();
        if (lib == null) {
            return UNKNOWN;
        }
        PlayerSim.World world = js.sim().world();
        double floor = Math.min(Math.min(PlayerSim.floor(start.y + 0.01), js.destY), js.bounceY) - 0.6;
        for (int pad : NativeTas.PADS) {
            int x0 = PlayerSim.floor(Math.min(start.x, js.destX)) - pad, x1 = PlayerSim.floor(Math.max(start.x, js.destX + 1)) + pad;
            int z0 = PlayerSim.floor(Math.min(start.z, js.destZ)) - pad, z1 = PlayerSim.floor(Math.max(start.z, js.destZ + 1)) + pad;
            int y0 = PlayerSim.floor(floor) - 2, y1 = PlayerSim.floor(Math.max(start.y, js.destY)) + 3 + pad / 2;
            NativeTas.Snapshot snap = NativeTas.Snapshot.take(world, x0, y0, z0, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
            if (snap == null) {
                return UNKNOWN;
            }
            int r;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment[] s = snap.segments(arena);
                MemorySegment state = arena.allocateFrom(JAVA_DOUBLE, NativeTas.state(start));
                MemorySegment params = arena.allocateFrom(JAVA_DOUBLE, mode, js.approachYaw(), js.dirX, js.dirZ, js.edge,
                        js.destX, js.destY, js.destZ, js.side, js.bounceY, b(js.anyDest), b(js.noJump), b(js.grab),
                        b(js.standJump), b(js.carry), b(js.ledge), b(js.jumped), js.airTicks, b(js.slewYaw), js.faceYaw);
                MemorySegment plan = arena.allocateFrom(JAVA_INT, js.plan);
                MemorySegment out = arena.allocate(JAVA_DOUBLE, 6 + NativeTas.STATE);
                r = (int) lib.jumpSearch.invoke(s[0], s[1], s[2], snap.boxCount, s[3], s[4], state, params, plan, out);
                if (r == 1 || r == NativeLib.NONE) {
                    System.arraycopy(plan.toArray(JAVA_INT), 0, js.plan, 0, JumpSearch.DIMS);
                    double[] o = out.toArray(JAVA_DOUBLE);
                    if (r == 1) { // a failed search leaves these as they were (run sets them only on success)
                        js.ticks = (int) o[0];
                        js.miss = o[1];
                        js.landSpeed = o[2];
                    }
                    js.destX = (int) o[3];
                    js.destY = (int) o[4];
                    js.destZ = (int) o[5];
                    double[] end = new double[NativeTas.STATE];
                    System.arraycopy(o, 6, end, 0, end.length);
                    NativeTas.load(js.sim(), end);
                    return r == 1 ? 1 : 0;
                }
            } catch (Throwable t) {
                NativeLib.disable("jump search threw " + t);
                return UNKNOWN;
            }
            if (!retry(r, "jump search")) {
                return UNKNOWN;
            }
        }
        return UNKNOWN;
    }

    /** {@link MomentumHop#find}'s answer: the trail, or null for "none". */
    public record Trail(String trail) {
    }

    /** {@link MomentumHop#find} in Rust; null when the library cannot answer. */
    static Trail momentumHop(PlayerSim.World world, PlayerSim start, float yaw, int dirX, int dirZ, int destX, int destY, int destZ,
                             boolean jumpedLastTick, int budget) {
        NativeLib lib = NativeLib.get();
        if (lib == null) {
            return null;
        }
        for (int pad : NativeTas.PADS) {
            int x0 = PlayerSim.floor(Math.min(start.x, destX)) - pad, x1 = PlayerSim.floor(Math.max(start.x, destX + 1)) + pad;
            int z0 = PlayerSim.floor(Math.min(start.z, destZ)) - pad, z1 = PlayerSim.floor(Math.max(start.z, destZ + 1)) + pad;
            int y0 = PlayerSim.floor(Math.min(start.y, destY)) - 2, y1 = PlayerSim.floor(Math.max(start.y, destY)) + 3 + pad / 2;
            NativeTas.Snapshot snap = NativeTas.Snapshot.take(world, x0, y0, z0, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
            if (snap == null) {
                return null;
            }
            int r;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment[] s = snap.segments(arena);
                MemorySegment state = arena.allocateFrom(JAVA_DOUBLE, NativeTas.state(start));
                MemorySegment params = arena.allocateFrom(JAVA_DOUBLE, yaw, dirX, dirZ, destX, destY, destZ, b(jumpedLastTick), budget);
                int cap = MomentumHop.MAX_TICKS + 1;
                MemorySegment out = arena.allocate(JAVA_BYTE, cap);
                r = (int) lib.momentumHop.invoke(s[0], s[1], s[2], snap.boxCount, s[3], s[4], state, params, out, cap);
                if (r >= 0) {
                    return new Trail(new String(out.asSlice(0, r).toArray(JAVA_BYTE), java.nio.charset.StandardCharsets.US_ASCII));
                }
            } catch (Throwable t) {
                NativeLib.disable("momentum hop threw " + t);
                return null;
            }
            if (r == NativeLib.NONE) {
                return new Trail(null);
            }
            if (!retry(r, "momentum hop")) {
                return null;
            }
        }
        return null;
    }

    /** True when a bigger snapshot may help; disables the library after a panic. */
    private static boolean retry(int r, String what) {
        if (r == NativeLib.PANIC) {
            NativeLib.disable(what + " panicked");
        }
        return r == NativeLib.OUT_OF_REGION;
    }

    private static double b(boolean v) {
        return v ? 1 : 0;
    }
}
