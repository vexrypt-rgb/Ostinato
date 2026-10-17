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
        /** Ostinato: and this far behind the run-up block's centre (a neo off a short run-up starts near the back edge). */
        public final double back;
        /** {@link JumpSearch} plan indices found offline; the run-time search starts around them. */
        public final int[] plan;
        /** Cells {a, y, b, solid} the player's box (plus a small margin) touches: passable, or solid when the box only slides along it (a neo's wall). */
        public final int[][] cells;
        /** Swings round the end of a wall: some cell is solid. */
        public final boolean neo;

        /** Along-a coordinate of the run-up start, the take-off block's centre being 0.5. */
        public double startA() {
            return -runUp + 0.5 - back;
        }

        Template(int a, int dy, int b, int runUp, double lateral, int ticks, int[] plan, int[][] cells) {
            this(a, dy, b, runUp, lateral, 0, ticks, plan, cells);
        }

        /**
         * Ostinato: off a lone block whose front edge juts out at its top (a ladder's plate): a copy of a run-up template
         * flown from a standstill, taking off from the ledge. Kept only once the sim flies it against the real blocks.
         */
        public final boolean ledge;
        private Template ledgeCopy;

        Template(int a, int dy, int b, int runUp, double lateral, double back, int ticks, int[] plan, int[][] cells) {
            this(a, dy, b, runUp, lateral, back, ticks, plan, cells, false);
        }

        private Template(int a, int dy, int b, int runUp, double lateral, double back, int ticks, int[] plan, int[][] cells, boolean ledge) {
            this.ledge = ledge;
            this.lateral = lateral;
            this.back = back;
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

        /** This jump without its run-up, off a ledge (see {@link #ledge}); the run-up's cells are dropped. */
        public Template ledgeVariant() {
            if (ledgeCopy == null) {
                List<int[]> kept = new ArrayList<>();
                for (int[] c : cells) if (c[0] >= 0) kept.add(c);
                ledgeCopy = new Template(a, dy, b, 0, lateral, 0, ticks, plan, kept.toArray(new int[0][]), true);
            }
            return ledgeCopy;
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
            String[] run = parts[3].split("b"); // runUp, or runUp"b"back(tenths)
            ALL.add(new Template(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(run[0]), Integer.parseInt(parts[4]) / 10.0, run.length > 1 ? Integer.parseInt(run[1]) / 10.0 : 0,
                    Integer.parseInt(parts[5]), plan, cells));
        }
    }

    // Ostinato: straight jumps under a ceiling. MovementParkour wants 3 clear cells over the arc, so a 2-2.5 high
    // gap (a head hitter) is never planned there; these carry the cells a straight jump touches, the head cells
    // (y=2) marked passable so MovementJump treats a solid one as a ceiling and verifies the jump in the sim.
    public static final List<Template> HEAD_HITTERS = new ArrayList<>();
    /** Ostinato: the head-hitter templates flown as a run of timed hops ({@link MomentumHop}) rather than one jump. */
    public static final java.util.Set<Template> MOMENTUM = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    static {
        for (int dy = 0; dy >= -1; dy--) for (int a = 2; a <= 4; a++) for (int r = 0; r <= 4; r++) {
            List<int[]> cells = new ArrayList<>();
            for (int k = -r; k <= a; k++) {
                for (int y = (k == a ? dy : 0); y <= 2; y++) cells.add(new int[]{k, y, 0, 0});
            }
            int[] plan = new int[JumpSearch.DIMS];
            plan[0] = 1;
            plan[3] = 4;
            HEAD_HITTERS.add(new Template(a, dy, 0, r, 0, 4 * a + 4, plan, cells.toArray(new int[0][])));
        }
        // Ostinato: multi-hop momentum. Gap 2 flat under a 2-high ceiling only works after a long run of timed
        // head-hitter hops (run-up 5..12) with the last takeoff at the edge; MomentumHop finds the timing in the sim.
        for (int r = 5; r <= 12; r++) {
            List<int[]> cells = new ArrayList<>();
            for (int k = -r; k <= 3; k++) {
                for (int y = 0; y <= 2; y++) cells.add(new int[]{k, y, 0, 0});
            }
            int[] plan = new int[JumpSearch.DIMS];
            plan[0] = 1;
            plan[3] = 4;
            Template m = new Template(3, 0, 0, r, 0, 16 + 2 * r, plan, cells.toArray(new int[0][]));
            HEAD_HITTERS.add(m);
            MOMENTUM.add(m);
        }
    }

    private static final double MARGIN = 0.05;
    private static final int[] RUN_UPS = {0, 1, 2, 3};
    /** Ostinato: start offsets behind the run-up block's centre, in tenths; tried for neos only, after the centre. */
    private static final int[] NEO_BACKS = {0, 2};
    /** Ostinato: a neo up one off a single block needs the take-off from near its back edge. */
    private static final int[] NEO_UP_BACKS = {0, 2, 4, 6};
    private static final int[] LATERAL = {0, 3, 6, 7, 8, 1}; // tenths of a block; 1 last: a 1-wall neo only works from about there

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
        List<int[]> dests = new ArrayList<>(); // a, dy, b, wall[, wallEndOffset[, wallStart]]
        for (int a = 2; a <= 7; a++) for (int b = 0; b <= 4; b++) for (int dy = 1; dy >= -3; dy--) {
            if (b == 0 && dy >= 0 && a < (dy == 0 ? 5 : 4)) continue; // MovementParkour's straight gaps
            dests.add(new int[]{a, dy, b, 0});
        }
        // neos: a wall {wall} thick straight ahead, running off to the -b side; the jump swings round its end
        for (int wall = 1; wall <= 3; wall++) for (int b = 0; b >= -1; b--) for (int dy = 0; dy >= -1; dy--) dests.add(new int[]{wall + 1, dy, b, wall});
        // Ostinato: the wall ending one column to the side (-b) of the take-off: fly past its end and drift back in behind it
        for (int wall = 1; wall <= 3; wall++) for (int dy = 0; dy >= -1; dy--) dests.add(new int[]{wall + 1, dy, -1, wall, 1});
        // Ostinato: a neo up one, landing on the block right behind the wall
        for (int wall = 1; wall <= 2; wall++) for (int b = 0; b >= -1; b--) dests.add(new int[]{wall + 1, 1, b, wall});
        // Ostinato: a gap before the wall (wall from a=2), and a gap after it too
        for (int post = 0; post <= 1; post++) for (int b = 0; b >= -1; b--) for (int dy = 1 - post; dy >= -1; dy--) dests.add(new int[]{3 + post, dy, b, 1, 0, 2});
        List<String> out = new ArrayList<>();
        for (int[] d : dests) {
            search:
            for (int r : RUN_UPS) for (int lat : LATERAL) for (int back : d[3] > 0 ? (d[1] > 0 ? NEO_UP_BACKS : NEO_BACKS) : new int[]{0}) {
                BoxWorld w = new BoxWorld();
                for (int a = -r; a <= 0; a++) w.add(a, -1, 0);
                w.add(d[0], d[1] - 1, d[2]);
                int wallEnd = d.length > 4 ? -d[4] : 0;
                int wallStart = d.length > 5 ? d[5] : 1;
                for (int a = wallStart; a < wallStart + d[3]; a++) for (int b = -4; b <= wallEnd; b++) for (int y = 0; y <= 2; y++) w.add(a, y, b);
                JumpSearch js = new JumpSearch(w);
                js.dirX = 1;
                js.dirZ = 0;
                js.edge = 1;
                js.destX = d[0];
                js.destY = d[1];
                js.destZ = d[2];
                PlayerSim start = new PlayerSim(w);
                start.x = -r + 0.5 - back / 10.0;
                start.z = 0.5 + lat / 10.0;
                start.onGround = true;
                start.vy = -0.0784000015258789; // vanilla's resting value: gravity pulls into the ground every tick, else the first tick counts as airborne
                if (!js.search(start, false)) continue;
                // the mover accepts a run-up stop anywhere within 0.15 of the start (and a few hundredths to the
                // side); the jump must work from all of it, not just the two ends: r=1 4-gaps had dead bands inside
                boolean robust = true;
                // Ostinato: a neo is a precise jump (players line it up by hand): its stop window is the mover's tight one
                int oxMin = d[3] > 0 ? -6 : -15, ozStep = d[3] > 0 ? 100 : 5;
                for (int ox = oxMin; ox <= 5 && robust; ox += 2) for (int oz = d[3] > 0 ? 0 : -5; oz <= 5 && robust; oz += ozStep) {
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
                out.add(d[0] + " " + d[1] + " " + d[2] + " " + r + (back > 0 ? "b" + back : "") + " " + lat + " " + js.ticks + " " + plan + " " + String.join(" ", cells));
                System.err.println("ok " + d[0] + " " + d[1] + " " + d[2] + " wall=" + d[3] + " runUp=" + r + " back=" + back + " lat=" + lat + " ticks=" + js.ticks + " miss=" + String.format("%.2f", js.miss));
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
