package baritone.pathing.kinematic;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Jumps {@code MovementParkour} can't plan (diagonal, knight's-move, down-and-across, neo round the end of a wall,
 * 4-block flat and 3-block up gaps), found offline with {@link JumpSearch} in an empty world and stored in
 * {@link JumpTemplateData}. Frame: approach along +a from the take-off block at the origin, lateral +b, feet y 0.
 * Regenerate with {@code main} after changing the search or the physics.
 */
public final class JumpTemplates {

    public static final class Template {
        public final int a, dy, b, runUp, ticks;
        /** Start this far to the +b side of the run-up's centre line. */
        public final double lateral;
        /** {@link JumpSearch} plan indices found offline; the run-time search starts around them. */
        public final int[] plan;
        /** Cells {a, y, b, solid} the player's box (plus a small margin) touches: passable, or solid when the box only slides along it (a neo's wall). */
        public final int[][] cells;
        /** Swings round the end of a wall: some cell is solid. */
        public final boolean neo;

        Template(int a, int dy, int b, int runUp, double lateral, int ticks, int[] plan, int[][] cells) {
            this.lateral = lateral;
            this.plan = plan;
            this.a = a;
            this.dy = dy;
            this.b = b;
            this.runUp = runUp;
            this.ticks = ticks;
            this.cells = cells;
            boolean wall = false;
            for (int[] c : cells) wall |= c[3] == 1;
            this.neo = wall;
        }
    }

    public static final List<Template> ALL = new ArrayList<>();

    static {
        for (String line : JumpTemplateData.DATA) {
            String[] parts = line.split(" ");
            int[][] cells = new int[parts.length - 7][];
            for (int i = 7; i < parts.length; i++) {
                boolean solid = parts[i].startsWith("s");
                String[] c = parts[i].substring(solid ? 1 : 0).split(",");
                cells[i - 7] = new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), solid ? 1 : 0};
            }
            int[] plan = new int[JumpSearch.DIMS];
            for (int k = 0; k < plan.length; k++) plan[k] = parts[6].charAt(k) - '0';
            ALL.add(new Template(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]) / 10.0, Integer.parseInt(parts[5]), plan, cells));
        }
    }

    private static final double MARGIN = 0.05;
    private static final int[] RUN_UPS = {0, 1, 2, 3};
    private static final int[] LATERAL = {0, 3, 6, 7, 8}; // tenths of a block

    /** Plain full blocks, slipperiness 0.6. */
    private static final class BoxWorld implements PlayerSim.World {
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

    public static void main(String[] args) throws Exception {
        List<int[]> dests = new ArrayList<>(); // a, dy, b, wall
        for (int a = 2; a <= 7; a++) for (int b = 0; b <= 4; b++) for (int dy = 1; dy >= -3; dy--) {
            if (b == 0 && dy >= 0 && a < (dy == 0 ? 5 : 4)) continue; // MovementParkour's straight gaps
            dests.add(new int[]{a, dy, b, 0});
        }
        // neos: a wall {wall} thick straight ahead, running off to the -b side; the jump swings round its end
        for (int wall = 1; wall <= 3; wall++) for (int b = 0; b >= -1; b--) for (int dy = 0; dy >= -1; dy--) dests.add(new int[]{wall + 1, dy, b, wall});
        List<String> out = new ArrayList<>();
        for (int[] d : dests) {
            search:
            for (int r : RUN_UPS) for (int lat : LATERAL) {
                BoxWorld w = new BoxWorld();
                for (int a = -r; a <= 0; a++) w.add(a, -1, 0);
                w.add(d[0], d[1] - 1, d[2]);
                for (int a = 1; a <= d[3]; a++) for (int b = -4; b <= 0; b++) for (int y = 0; y <= 2; y++) w.add(a, y, b);
                JumpSearch js = new JumpSearch(w);
                js.dirX = 1;
                js.dirZ = 0;
                js.edge = 1;
                js.destX = d[0];
                js.destY = d[1];
                js.destZ = d[2];
                PlayerSim start = new PlayerSim(w);
                start.x = -r + 0.5;
                start.z = 0.5 + lat / 10.0;
                start.onGround = true;
                start.vy = -0.0784000015258789; // vanilla's resting value: gravity pulls into the ground every tick, else the first tick counts as airborne
                if (!js.search(start, false)) continue;
                // the mover accepts a run-up stop anywhere within 0.15 of the start (and a few hundredths to the
                // side); the jump must work from all of it, not just the two ends: r=1 4-gaps had dead bands inside
                boolean robust = true;
                for (int ox = -15; ox <= 5 && robust; ox += 2) for (int oz = -5; oz <= 5 && robust; oz += 5) {
                    JumpSearch rj = new JumpSearch(w);
                    rj.dirX = 1;
                    rj.dirZ = 0;
                    rj.edge = 1;
                    rj.destX = d[0];
                    rj.destY = d[1];
                    rj.destZ = d[2];
                    PlayerSim rs = new PlayerSim(w);
                    rs.x = start.x + ox / 100.0;
                    rs.z = start.z + oz / 100.0;
                    rs.vy = start.vy;
                    rs.onGround = true;
                    robust = rj.search(rs, false);
                }
                if (!robust) continue;
                Set<String> cells = new TreeSet<>();
                JumpSearch.CellSink sink = (x, y, z) -> {
                    for (int cx = PlayerSim.floor(x - 0.3 - MARGIN); cx <= PlayerSim.floor(x + 0.3 + MARGIN); cx++)
                        for (int cy = PlayerSim.floor(y + 1e-3); cy <= PlayerSim.floor(y + 1.8); cy++)
                            for (int cz = PlayerSim.floor(z - 0.3 - MARGIN); cz <= PlayerSim.floor(z + 0.3 + MARGIN); cz++)
                                cells.add((w.solid.contains(BoxWorld.key(cx, cy, cz)) ? "s" : "") + cx + "," + cy + "," + cz);
                };
                sink.box(start.x, start.y, start.z);
                js.run(start, js.plan, false, 0, sink);
                StringBuilder plan = new StringBuilder();
                for (int k : js.plan) plan.append(k);
                out.add(d[0] + " " + d[1] + " " + d[2] + " " + r + " " + lat + " " + js.ticks + " " + plan + " " + String.join(" ", cells));
                System.err.println("ok " + d[0] + " " + d[1] + " " + d[2] + " wall=" + d[3] + " runUp=" + r + " lat=" + lat + " ticks=" + js.ticks + " miss=" + String.format("%.2f", js.miss));
                break search;
            }
        }
        try (PrintWriter pw = new PrintWriter(args[0])) {
            pw.println("package baritone.pathing.kinematic;");
            pw.println();
            pw.println("/** Generated by {@link JumpTemplates#main}: a dy b runUp lateral(tenths) ticks plan cells... */");
            pw.println("final class JumpTemplateData {");
            pw.println("    static final String[] DATA = {");
            for (String s : out) pw.println("            \"" + s + "\",");
            pw.println("    };");
            pw.println("}");
        }
    }
}
