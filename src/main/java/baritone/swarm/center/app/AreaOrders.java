package baritone.swarm.center.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ostinato: Phase 3 window, goto-area and mine-in-area. Pure arithmetic: bots spread on a grid, and a mine is one
 * equal strip per bot along the longer edge (remainder on the first strips). A strip whose edge is 64 or more is
 * refused with {@link #EDGE_REASON}; the area is not shrunk to fit. This is the volume split, not a schematic's
 * occupancy-weighted strips.
 */
public final class AreaOrders {

    /** Same limit as a region lease: an edge of this many blocks (x2 - x1) is illegal. */
    public static final int MAX_EDGE = 64;
    public static final String EDGE_REASON = "box edge over 64";
    public static final String HOST_REASON = "area mining needs the command host to be the lease coordinator";
    public static final String SELECT_REASON = "select bots first";
    public static final String SMALL_REASON = "area is smaller than the selection";
    public static final String BLOCK_REASON = "missing block";

    private AreaOrders() {}

    /** Inclusive block box. Corners are sorted. */
    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public Box {
            int nx1 = Math.min(x1, x2);
            int nx2 = Math.max(x1, x2);
            int ny1 = Math.min(y1, y2);
            int ny2 = Math.max(y1, y2);
            int nz1 = Math.min(z1, z2);
            int nz2 = Math.max(z1, z2);
            x1 = nx1;
            x2 = nx2;
            y1 = ny1;
            y2 = ny2;
            z1 = nz1;
            z2 = nz2;
        }

        public int spanX() { return x2 - x1 + 1; }
        public int spanY() { return y2 - y1 + 1; }
        public int spanZ() { return z2 - z1 + 1; }

        public Box atY(int y1, int y2) {
            return new Box(x1, y1, z1, x2, y2, z2);
        }
    }

    public record BotAt(String name, double x, double y, double z) {}

    public record Assign(String bot, int x, int z) {
        public String target() { return x + " " + z; }
    }

    public record Strip(String bot, Box box) {}

    public record Mine(List<Strip> strips, String refusal) {
        public boolean ok() { return refusal == null; }

        public List<String> lines(char dim, String ore) {
            List<String> out = new ArrayList<>();
            if (!ok()) {
                return out;
            }
            for (Strip s : strips) {
                out.add(mineLine(s.bot(), dim, s.box(), ore));
            }
            return out;
        }
    }

    /** Median y, then eight blocks either side. No samples uses 64. */
    public static int[] yRange(List<Integer> ys) {
        int mid = 64;
        if (ys != null && !ys.isEmpty()) {
            List<Integer> sorted = new ArrayList<>(ys);
            sorted.sort(Integer::compareTo);
            int n = sorted.size();
            if (n % 2 == 1) {
                mid = sorted.get(n / 2);
            } else {
                mid = (int) Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
            }
        }
        return new int[]{mid - 8, mid + 8};
    }

    /**
     * One block per bot inside {@code area}, on a grid that follows the area's shape. Each bot is paired with the
     * nearest still-free point (ties: bot name, then point order).
     */
    public static List<Assign> spread(Box area, List<BotAt> bots) {
        List<Assign> out = new ArrayList<>();
        if (area == null || bots == null || bots.isEmpty()) {
            return out;
        }
        List<BotAt> live = new ArrayList<>();
        for (BotAt b : bots) {
            if (b != null && b.name() != null && !b.name().isBlank()) {
                live.add(b);
            }
        }
        if (live.isEmpty()) {
            return out;
        }
        int n = live.size();
        int[][] points = grid(area, n);
        boolean[] usedBot = new boolean[n];
        boolean[] usedPoint = new boolean[n];
        List<int[]> pairs = new ArrayList<>();
        for (int bi = 0; bi < n; bi++) {
            for (int pi = 0; pi < n; pi++) {
                pairs.add(new int[]{bi, pi});
            }
        }
        pairs.sort(Comparator
                .comparingDouble((int[] p) -> dist2(live.get(p[0]), points[p[1]]))
                .thenComparing(p -> live.get(p[0]).name())
                .thenComparingInt(p -> p[1]));
        int placed = 0;
        for (int[] p : pairs) {
            if (usedBot[p[0]] || usedPoint[p[1]]) {
                continue;
            }
            usedBot[p[0]] = true;
            usedPoint[p[1]] = true;
            out.add(new Assign(live.get(p[0]).name(), points[p[1]][0], points[p[1]][1]));
            if (++placed == n) {
                break;
            }
        }
        out.sort(Comparator.comparingInt((Assign a) -> indexOf(live, a.bot())));
        return out;
    }

    /**
     * One strip per bot along the longer horizontal edge. X wins a tie. {@code bots} order is the strip order,
     * low coordinate first.
     */
    public static Mine strips(Box area, List<String> bots) {
        List<String> names = new ArrayList<>();
        if (bots != null) {
            for (String name : bots) {
                if (name != null && !name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        if (area == null || names.isEmpty()) {
            return new Mine(List.of(), SELECT_REASON);
        }
        if (area.y2 - area.y1 >= MAX_EDGE) {
            return new Mine(List.of(), EDGE_REASON);
        }
        boolean cutX = area.spanX() >= area.spanZ();
        int span = cutX ? area.spanX() : area.spanZ();
        int n = names.size();
        if (n > span) {
            return new Mine(List.of(), SMALL_REASON);
        }
        int[] share = shares(span, n);
        List<Strip> out = new ArrayList<>();
        int cursor = cutX ? area.x1 : area.z1;
        for (int i = 0; i < n; i++) {
            int end = cursor + share[i] - 1;
            Box strip = cutX
                    ? new Box(cursor, area.y1, area.z1, end, area.y2, area.z2)
                    : new Box(area.x1, area.y1, cursor, area.x2, area.y2, end);
            if (strip.x2 - strip.x1 >= MAX_EDGE || strip.y2 - strip.y1 >= MAX_EDGE || strip.z2 - strip.z1 >= MAX_EDGE) {
                return new Mine(List.of(), EDGE_REASON);
            }
            out.add(new Strip(names.get(i), strip));
            cursor = end + 1;
        }
        return new Mine(out, null);
    }

    public static Mine mine(Box area, List<String> bots, String ore) {
        if (ore == null || ore.isBlank()) {
            return new Mine(List.of(), BLOCK_REASON);
        }
        return strips(area, bots);
    }

    public static String mineLine(String bot, char dim, Box box, String ore) {
        return "swarm lease " + bot + " r:" + dim + ":" + box.x1 + "," + box.y1 + "," + box.z1
                + ":" + box.x2 + "," + box.y2 + "," + box.z2 + " mine " + ore.trim();
    }

    /** Near-equal positive shares that sum to {@code total}. The remainder goes to the first shares. */
    public static int[] shares(int total, int parts) {
        int[] s = new int[parts];
        if (parts < 1) {
            return s;
        }
        int base = Math.max(0, total) / parts;
        int rem = Math.max(0, total) % parts;
        for (int i = 0; i < parts; i++) {
            s[i] = base + (i < rem ? 1 : 0);
        }
        return s;
    }

    private static int[][] grid(Box area, int n) {
        double aspect = area.spanX() / (double) area.spanZ();
        int cols = n == 1 ? 1 : (int) Math.round(Math.sqrt(n * aspect));
        cols = Math.max(1, Math.min(n, cols));
        int rows = (int) Math.ceil(n / (double) cols);
        int[][] points = new int[n][2];
        for (int i = 0; i < n; i++) {
            int c = i % cols;
            int r = i / cols;
            int x = area.x1 + (int) Math.floor((c + 0.5) * area.spanX() / cols);
            int z = area.z1 + (int) Math.floor((r + 0.5) * area.spanZ() / rows);
            points[i][0] = Math.min(area.x2, Math.max(area.x1, x));
            points[i][1] = Math.min(area.z2, Math.max(area.z1, z));
        }
        return points;
    }

    private static double dist2(BotAt bot, int[] point) {
        double dx = bot.x - point[0];
        double dz = bot.z - point[1];
        return dx * dx + dz * dz;
    }

    private static int indexOf(List<BotAt> bots, String name) {
        for (int i = 0; i < bots.size(); i++) {
            if (bots.get(i).name().equals(name)) {
                return i;
            }
        }
        return bots.size();
    }
}
