package baritone.bastion;

import java.util.*;

/**
 * Nether lava spread forecast (pure logic). Lava falls first; on a floor it spreads sideways, losing one level per block
 * (7 blocks from a source in the Nether), one step every 10 ticks. Returns the tick each open cell is reached.
 */
public final class LavaFlow {
    public static final int STEP_TICKS = 10, MAX_RUN = 7;

    public interface Grid {
        /** -1 not lava, 0 source, 1..7 flowing (distance from its source) */
        int lava(int x, int y, int z);
        /** lava can enter (air or replaceable) */
        boolean open(int x, int y, int z);
    }

    public static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    /** Cells within r of (cx,cy,cz) that lava will reach within horizonTicks, mapped to arrival tick. */
    public static Map<Long, Integer> forecast(Grid g, int cx, int cy, int cz, int r, int horizonTicks) {
        Map<Long, Integer> arrive = new HashMap<>();
        Map<Long, Integer> level = new HashMap<>();
        ArrayDeque<int[]> q = new ArrayDeque<>(); // x,y,z,level,tick
        for (int x = cx - r; x <= cx + r; x++) for (int y = cy - r; y <= cy + r; y++) for (int z = cz - r; z <= cz + r; z++) {
            int l = g.lava(x, y, z);
            if (l >= 0 && l < MAX_RUN) { q.add(new int[]{x, y, z, l, 0}); level.put(key(x, y, z), l); }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int t = c[4] + STEP_TICKS;
            if (t > horizonTicks) continue;
            if (g.open(c[0], c[1] - 1, c[2])) { // falls first, and falling lava starts a fresh run
                visit(g, q, arrive, level, cx, cy, cz, r, c[0], c[1] - 1, c[2], 0, t);
                continue;
            }
            if (c[3] + 1 > MAX_RUN) continue;
            for (int[] d : dirs) visit(g, q, arrive, level, cx, cy, cz, r, c[0] + d[0], c[1], c[2] + d[1], c[3] + 1, t);
        }
        return arrive;
    }

    private static void visit(Grid g, ArrayDeque<int[]> q, Map<Long, Integer> arrive, Map<Long, Integer> level, int cx, int cy, int cz, int r, int x, int y, int z, int l, int t) {
        if (Math.abs(x - cx) > r + MAX_RUN || Math.abs(z - cz) > r + MAX_RUN || y < cy - r - 8) return;
        if (!g.open(x, y, z) && g.lava(x, y, z) < 0) return;
        long k = key(x, y, z);
        Integer have = level.get(k);
        if (have != null && have <= l) return;
        level.put(k, l);
        if (g.lava(x, y, z) < 0) arrive.merge(k, t, Math::min);
        q.add(new int[]{x, y, z, l, t});
    }

    private LavaFlow() {}
}
