package baritone.pathing.kinematic;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Two jumps through a one block pad: land on the pad with momentum and jump again without stopping, which reaches
 * further than any single jump in {@link JumpTemplates}. Found offline with two {@link JumpSearch}es in an empty world
 * and stored in {@link ChainTemplateData}. Frame as in {@link JumpTemplates}: approach along +a from the take-off block at
 * the origin, lateral +b, feet y 0; the pad is straight ahead at (padA, padDy, 0).
 * Regenerate with {@code main} after changing the search or the physics.
 */
public final class ChainTemplates {

    public static final class Template {
        public final int padA, padDy, a, dy, b, runUp, ticks;
        public final double lateral;
        /** {@link JumpSearch} plan indices for the jump to the pad and the jump from it. */
        public final int[] plan1, plan2;
        /** Cells {a, y, b, solid} both flights touch. */
        public final int[][] cells;

        Template(int padA, int padDy, int a, int dy, int b, int runUp, double lateral, int ticks, int[] plan1, int[] plan2, int[][] cells) {
            this.padA = padA;
            this.padDy = padDy;
            this.a = a;
            this.dy = dy;
            this.b = b;
            this.runUp = runUp;
            this.lateral = lateral;
            this.ticks = ticks;
            this.plan1 = plan1;
            this.plan2 = plan2;
            this.cells = cells;
        }
    }

    public static final List<Template> ALL = new ArrayList<>();

    static {
        for (String line : ChainTemplateData.DATA) {
            String[] p = line.split(" ");
            int[][] cells = new int[p.length - 10][];
            for (int i = 10; i < p.length; i++) {
                boolean solid = p[i].startsWith("s");
                String[] c = p[i].substring(solid ? 1 : 0).split(",");
                cells[i - 10] = new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), solid ? 1 : 0};
            }
            ALL.add(new Template(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]) / 10.0, Integer.parseInt(p[7]),
                    plan(p[8]), plan(p[9]), cells));
        }
    }

    private static int[] plan(String s) {
        int[] plan = new int[JumpSearch.DIMS];
        for (int k = 0; k < plan.length; k++) plan[k] = s.charAt(k) - '0';
        return plan;
    }

    private static final double MARGIN = 0.05;
    private static final int[] RUN_UPS = {2, 3};
    private static final int[] LATERAL = {0, 3}; // tenths of a block
    private static final int BUCKETS = 12;

    private static final class World implements PlayerSim.World {
        final Set<Long> solid = new HashSet<>();

        void add(int x, int y, int z) {
            solid.add(key(x, y, z));
        }

        static long key(int x, int y, int z) {
            return ((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512);
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++)
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++)
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++)
                        if (solid.contains(key(x, y, z))) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }
    }

    private static JumpSearch stage1(World w, int[] d) {
        JumpSearch js = new JumpSearch(w);
        js.dirX = 1;
        js.dirZ = 0;
        js.edge = 1;
        js.destX = d[0];
        js.destY = d[1];
        js.destZ = 0;
        js.carry = true;
        return js;
    }

    private static JumpSearch stage2(World w, int[] d) {
        JumpSearch js = new JumpSearch(w);
        js.dirX = 1;
        js.dirZ = 0;
        js.edge = d[0] + 1; // the pad's front edge
        js.destX = d[2];
        js.destY = d[3];
        js.destZ = d[4];
        return js;
    }

    private static PlayerSim start(World w, int r, int lat) {
        PlayerSim s = new PlayerSim(w);
        s.x = -r + 0.5;
        s.z = 0.5 + lat / 10.0;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        return s;
    }

    private static final class Landing {
        final int[] plan;
        final int ticks;
        final PlayerSim state;
        final double speed;

        Landing(int[] plan, int ticks, PlayerSim state) {
            this.plan = plan;
            this.ticks = ticks;
            this.state = state;
            this.speed = state.vx;
        }
    }

    /** The chain for {@code d} = {padA, padDy, a, dy, b}, or null. */
    private static String find(int[] d) {
        for (int r : RUN_UPS) for (int lat : LATERAL) {
            World w = new World();
            for (int a = -r; a <= 0; a++) w.add(a, -1, 0);
            w.add(d[0], d[1] - 1, 0);
            w.add(d[2], d[3] - 1, d[4]);
            JumpSearch j1 = stage1(w, d);
            PlayerSim start = start(w, r, lat);
            // every way onto the pad, one per distinct landing state: the best one for the second jump is rarely the tidiest
            Map<String, Landing> buckets = new HashMap<>();
            int[] size = {JumpSearch.O0.length, JumpSearch.HOP.length, JumpSearch.EDGE.length, JumpSearch.O1.length,
                    JumpSearch.SWITCH.length, JumpSearch.RELEASE.length, JumpSearch.BRAKE.length};
            int[] p = new int[JumpSearch.DIMS];
            for (p[0] = 0; p[0] < size[0]; p[0]++) for (p[1] = 0; p[1] < size[1]; p[1]++) for (p[2] = 0; p[2] < size[2]; p[2]++)
                for (p[3] = 0; p[3] < size[3]; p[3]++) for (p[4] = 0; p[4] < size[4]; p[4]++) for (p[5] = 0; p[5] < size[5]; p[5]++)
                    for (p[6] = 0; p[6] < size[6]; p[6]++) {
                        if (!j1.run(start, p, false, 0, null)) continue;
                        PlayerSim s = new PlayerSim(w).copyFrom(j1.sim());
                        String key = Math.round(s.x * 10) + "," + Math.round(s.z * 10) + "," + Math.round(s.vx * 20) + "," + Math.round(s.vz * 20) + "," + s.jumpTicks;
                        Landing old = buckets.get(key);
                        if (old == null || j1.ticks < old.ticks) buckets.put(key, new Landing(p.clone(), j1.ticks, s));
                    }
            List<Landing> order = new ArrayList<>(buckets.values());
            order.sort(Comparator.comparingDouble((Landing l) -> -l.speed).thenComparingInt(l -> l.ticks));
            int tried = 0;
            for (Landing l : order) {
                if (tried++ >= BUCKETS) break;
                JumpSearch j2 = stage2(w, d);
                if (!j2.search(new PlayerSim(w).copyFrom(l.state), false)) continue;
                int[] plan2 = j2.plan.clone();
                int ticks = l.ticks + j2.ticks;
                // the mover starts a little off: the first jump must still land on the pad, and the second be found again from there
                boolean robust = true;
                for (int ox = -15; ox <= 5 && robust; ox += 4) for (int oz = -5; oz <= 5 && robust; oz += 5) {
                    JumpSearch r1 = stage1(w, d);
                    PlayerSim rs = start(w, r, lat);
                    rs.x += ox / 100.0;
                    rs.z += oz / 100.0;
                    if (!r1.run(rs, l.plan, false, 0, null)) {
                        robust = false;
                        break;
                    }
                    JumpSearch r2 = stage2(w, d);
                    System.arraycopy(plan2, 0, r2.plan, 0, JumpSearch.DIMS);
                    robust = r2.search(new PlayerSim(w).copyFrom(r1.sim()), true);
                }
                if (!robust) continue;
                Set<String> cells = new TreeSet<>();
                JumpSearch.CellSink sink = (x, y, z) -> {
                    for (int cx = PlayerSim.floor(x - 0.3 - MARGIN); cx <= PlayerSim.floor(x + 0.3 + MARGIN); cx++)
                        for (int cy = PlayerSim.floor(y + 1e-3); cy <= PlayerSim.floor(y + 1.8); cy++)
                            for (int cz = PlayerSim.floor(z - 0.3 - MARGIN); cz <= PlayerSim.floor(z + 0.3 + MARGIN); cz++)
                                cells.add((w.solid.contains(World.key(cx, cy, cz)) ? "s" : "") + cx + "," + cy + "," + cz);
                };
                sink.box(start.x, start.y, start.z);
                JumpSearch c1 = stage1(w, d);
                c1.run(start, l.plan, false, 0, sink);
                JumpSearch c2 = stage2(w, d);
                c2.run(new PlayerSim(w).copyFrom(c1.sim()), plan2, false, 0, sink);
                StringBuilder s1 = new StringBuilder(), s2 = new StringBuilder();
                for (int k : l.plan) s1.append(k);
                for (int k : plan2) s2.append(k);
                System.err.println("ok pad=" + d[0] + "," + d[1] + " dest=" + d[2] + "," + d[3] + "," + d[4] + " runUp=" + r + " lat=" + lat + " ticks=" + ticks);
                return d[0] + " " + d[1] + " " + d[2] + " " + d[3] + " " + d[4] + " " + r + " " + lat + " " + ticks + " " + s1 + " " + s2 + " " + String.join(" ", cells);
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        List<int[]> dests = new ArrayList<>(); // padA, padDy, a, dy, b
        for (int padA = 3; padA <= 5; padA++) for (int padDy = 0; padDy >= -1; padDy--)
            for (int gap = 3; gap <= 5; gap++) for (int dd = 0; dd >= -1; dd--) for (int b = 0; b <= 1; b++) {
                int a = padA + gap, dy = padDy + dd;
                boolean single = false;
                for (JumpTemplates.Template t : JumpTemplates.ALL) single |= t.a == a && t.dy == dy && t.b == b;
                if (!single) dests.add(new int[]{padA, padDy, a, dy, b});
            }
        System.err.println(dests.size() + " chains to try");
        List<String> out = java.util.Collections.synchronizedList(new ArrayList<>());
        dests.parallelStream().forEach(d -> {
            String line = find(d);
            if (line != null) out.add(line);
        });
        List<String> sorted = new ArrayList<>(out);
        java.util.Collections.sort(sorted);
        try (PrintWriter pw = new PrintWriter(args[0])) {
            pw.println("package baritone.pathing.kinematic;");
            pw.println();
            pw.println("/** Generated by {@link ChainTemplates#main}: padA padDy a dy b runUp lateral(tenths) ticks plan1 plan2 cells... */");
            pw.println("final class ChainTemplateData {");
            pw.println("    static final String[] DATA = {");
            for (String s : sorted) pw.println("            \"" + s + "\",");
            pw.println("    };");
            pw.println("}");
        }
    }
}
