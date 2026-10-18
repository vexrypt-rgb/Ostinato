package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A jump that failed in the world stays available, but it costs more for a while, and the extra fades.
 * A hard skip made the only route a nopath for 30s; a high cost sends the search down another route
 * and still allows this jump when nothing else reaches.
 */
public final class JumpFailures {

    public static final long WINDOW_MS = 30_000;

    /** fails in {@code [0]}, time of the latest failure in {@code [1]}. */
    private static final ConcurrentHashMap<Long, long[]> FAILED = new ConcurrentHashMap<>();

    private JumpFailures() {}

    /**
     * Key for a failed movement other than a kinematic jump (those mark their own key and fold it into their cost):
     * the search multiplies any move from src to dest by its fade, so a step that keeps failing in the world (a
     * diagonal up beside a wall over water) yields to another route instead of being repeated until stuck.
     */
    public static long moveKey(int x, int y, int z, int dx, int dy, int dz) {
        return (BetterBlockPos.longHash(x, y, z) * 31 + BetterBlockPos.longHash(dx, dy, dz)) ^ 0x5DEECE66DL;
    }

    public static void forget() {
        FAILED.clear();
    }

    public static void mark(long key, long now) {
        FAILED.compute(key, (k, v) -> {
            if (v == null || now - v[1] >= WINDOW_MS) {
                return new long[]{1, now};
            }
            return new long[]{Math.min(v[0] + 1, 5), now};
        });
    }

    /** True when no failure is still inside the window, so jump costs are back to 1. */
    public static boolean settled(long now) {
        if (FAILED.isEmpty()) {
            return true;
        }
        for (long[] v : FAILED.values()) {
            long age = now - v[1];
            if (age >= 0 && age < WINDOW_MS) {
                return false;
            }
        }
        return true;
    }

    /** 1 when this jump has not failed lately. */
    public static double multiplier(long key, long now) {
        if (FAILED.isEmpty()) {
            return 1;
        }
        long[] v = FAILED.get(key);
        if (v == null) {
            return 1;
        }
        long age = now - v[1];
        if (age >= WINDOW_MS || age < 0) {
            FAILED.remove(key, v);
            return 1;
        }
        return curve(v[0], age, WINDOW_MS);
    }

    /**
     * First failure peaks at 4 and fades to 1 across the window. Each further failure inside the window
     * doubles that peak, up to 64.
     */
    public static double curve(long fails, long ageMs, long windowMs) {
        if (fails <= 0 || windowMs <= 0 || ageMs < 0 || ageMs >= windowMs) {
            return 1;
        }
        double remain = 1.0 - (double) ageMs / (double) windowMs;
        int steps = (int) Math.min(fails, 5);
        double peak = 1L << (steps + 1);
        return 1 + (peak - 1) * remain;
    }
}
