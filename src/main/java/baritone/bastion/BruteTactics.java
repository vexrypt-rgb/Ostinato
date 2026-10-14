package baritone.bastion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Two ways of dealing with a brute without hitting it (a hit sets every piglin that sees it on us):
 * LAVA pours a bucket under or in front of it from a cell the forecast says the lava never reaches, waits while it
 * burns, then scoops the lava back so barter drops do not burn. PIT digs a sealed 1x1, 2-deep pit beside the trade
 * hole (sharing a wall with it, no opening toward us), lures the brute across it and covers the top. Pure logic.
 */
public final class BruteTactics {
    private BruteTactics() {}

    public enum Plan { NONE, LAVA, PIT }

    /** Engage a brute within this range, or one standing this close to a piglin we would trade with. */
    public static final double ENGAGE = 10, BLOCKS_TRADE = 4;
    /** Never pour lava closer than this to us. */
    public static final double MIN_POUR = 2.5;
    /** Lava forecast horizon: long enough for the lava to settle. */
    public static final int HORIZON = 200;
    /** Scoop the lava after this long even if the brute lives (fire keeps burning it). */
    public static final int BURN_TICKS = 160;
    public static final int LURE_TICKS = 400;

    public static Plan choose(String setting, double bruteDist, boolean blocksTrading, boolean hasLava, boolean lavaSafe, boolean pitSite) {
        if ("off".equals(setting) || bruteDist > ENGAGE && !blocksTrading) return Plan.NONE;
        boolean lavaOk = !"pit".equals(setting) && hasLava && lavaSafe;
        if (lavaOk) return Plan.LAVA;
        if (!"lava-only".equals(setting) && pitSite) return Plan.PIT;
        return Plan.NONE;
    }

    /** Lava from a source at (tx,ty,tz) never reaches our feet, head or the four cells beside our feet. */
    public static boolean pourSafe(LavaFlow.Grid g, int tx, int ty, int tz, int mx, int my, int mz) {
        if (Math.sqrt(sq(tx - mx) + sq(ty - my) + sq(tz - mz)) < MIN_POUR) return false;
        LavaFlow.Grid with = new LavaFlow.Grid() {
            public int lava(int x, int y, int z) { return x == tx && y == ty && z == tz ? 0 : g.lava(x, y, z); }
            public boolean open(int x, int y, int z) { return g.open(x, y, z); }
        };
        Map<Long, Integer> f = LavaFlow.forecast(with, tx, ty, tz, 8, HORIZON);
        int[][] ours = {{0, 0, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : ours) if (f.containsKey(LavaFlow.key(mx + o[0], my + o[1], mz + o[2]))) return false;
        return true;
    }

    /** Where to pour: the brute's own cell first, then the cell one step toward us. */
    public static List<int[]> pourCandidates(int bx, int by, int bz, int mx, int mz) {
        List<int[]> c = new ArrayList<>();
        c.add(new int[]{bx, by, bz});
        int dx = Integer.signum(mx - bx), dz = Integer.signum(mz - bz);
        if (Math.abs(mx - bx) >= Math.abs(mz - bz)) dz = 0; else dx = 0;
        if (dx != 0 || dz != 0) c.add(new int[]{bx + dx, by, bz + dz});
        return c;
    }

    public static boolean scoopNow(long sincePour, boolean bruteAlive, boolean bruteInLava, boolean dropsNear) {
        return !bruteAlive || sincePour >= BURN_TICKS || sincePour > 40 && !bruteInLava || dropsNear;
    }

    /** Pit cells beside the trade hole that share a wall with it and are not next to (or at) our cell. */
    public static List<int[]> pitSites(int hx, int hy, int hz, int mx, int mz) {
        List<int[]> s = new ArrayList<>();
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            int x = hx + o[0], z = hz + o[1];
            if (Math.abs(x - mx) + Math.abs(z - mz) <= 1) continue;
            s.add(new int[]{x, hy, z});
        }
        return s;
    }

    /** Lure stand: the cell beyond the pit on the far side from the brute, so it crosses the pit to reach us. */
    public static int[] lureStand(int px, int py, int pz, double bx, double bz) {
        double dx = px + 0.5 - bx, dz = pz + 0.5 - bz;
        if (Math.abs(dx) >= Math.abs(dz)) return new int[]{px + (dx >= 0 ? 1 : -1), py, pz};
        return new int[]{px, py, pz + (dz >= 0 ? 1 : -1)};
    }

    /** In the pit: same column, feet at most one below the pit's top cell. */
    public static boolean inPit(int px, int py, int pz, int bx, double by, int bz) {
        return bx == px && bz == pz && by <= py - 0.5;
    }

    private static double sq(double v) { return v * v; }
}
