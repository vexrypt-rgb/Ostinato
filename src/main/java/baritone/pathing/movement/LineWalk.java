package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A straight walk across a knight offset (2 by 1). The corridor is the whole rectangle, which is
 * wider than the body, so a line is only offered in the open. Narrow corridors keep the grid path.
 */
public final class LineWalk {

    /** The eight moves A* actually asks for. Built once so the hot path does not allocate a rectangle per neighbor. */
    private static final Map<Long, int[][]> KNIGHTS;

    static {
        Map<Long, int[][]> m = new HashMap<>();
        for (int[] d : new int[][]{{2, -1}, {2, 1}, {-2, -1}, {-2, 1}, {1, -2}, {-1, -2}, {1, 2}, {-1, 2}}) {
            m.put(key(d[0], d[1]), build(d[0], d[1]));
        }
        KNIGHTS = Collections.unmodifiableMap(m);
    }

    private LineWalk() {}

    public static double ticks(int dx, int dz, boolean sprint) {
        double per = sprint ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST;
        return Math.hypot(dx, dz) * per;
    }

    /** Cells other than the start, in sign-adjusted offsets, that the rectangle covers. */
    public static int[][] corridor(int dx, int dz) {
        int[][] known = KNIGHTS.get(key(dx, dz));
        return known != null ? known : build(dx, dz);
    }

    private static long key(int dx, int dz) {
        return ((long) dx << 32) ^ (dz & 0xffffffffL);
    }

    private static int[][] build(int dx, int dz) {
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        int n = (ax + 1) * (az + 1) - 1;
        int[][] cells = new int[n][2];
        int k = 0;
        for (int x = 0; x <= ax; x++) {
            for (int z = 0; z <= az; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                cells[k][0] = x * sx;
                cells[k][1] = z * sz;
                k++;
            }
        }
        return cells;
    }
}
