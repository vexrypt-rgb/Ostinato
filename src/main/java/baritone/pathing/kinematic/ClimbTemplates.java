package baritone.pathing.kinematic;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Jumps onto and off ladders and vines, found offline with {@link JumpSearch} in an empty world and stored in
 * {@link ClimbTemplateData}, the way {@link JumpTemplates} does for plain jumps. Frame: approach (or leap) along +a from
 * the origin, lateral +b, feet y 0.
 * <p>
 * Two kinds: {@link #GRAB} runs up from the ground and catches a ladder in the air; {@link #LEAP} lets go of a ladder at
 * the origin and lands on the ground or on another ladder. {@code wall} says which side of the ladder is the wall it is
 * stuck to (of the destination ladder for a grab, of the origin ladder for a leap, and of both for a leap onto a
 * ladder: they hang on the same wall).
 * Regenerate with {@code main} after changing the search or the physics.
 */
public final class ClimbTemplates {

    public static final int GRAB = 0, LEAP = 1;
    /** Where the wall is, relative to the ladder: ahead (+a), behind (-a), to the +b side, to the -b side. */
    public static final int AHEAD = 0, BEHIND = 1, LEFT = 2, RIGHT = 3;

    /** Ladder plate thickness in blocks (3/16). */
    private static final double PLATE = 0.1875;

    public static final class Template {
        public final int mode, wall, a, dy, b, runUp, ticks;
        public final boolean destLadder;
        public final double lateral;
        public final int[] plan;
        /** Cells {a, y, b, kind}: 0 passable, 1 solid, 2 the ladders themselves (not checked). */
        public final int[][] cells;

        Template(int mode, int wall, int a, int dy, int b, boolean destLadder, int runUp, double lateral, int ticks, int[] plan, int[][] cells) {
            this.mode = mode;
            this.wall = wall;
            this.a = a;
            this.dy = dy;
            this.b = b;
            this.destLadder = destLadder;
            this.runUp = runUp;
            this.lateral = lateral;
            this.ticks = ticks;
            this.plan = plan;
            this.cells = cells;
        }
    }

    public static final List<Template> ALL = new ArrayList<>();

    static {
        for (String line : ClimbTemplateData.DATA) {
            String[] p = line.split(" ");
            int[][] cells = new int[p.length - 10][];
            for (int i = 10; i < p.length; i++) {
                char k = p[i].charAt(0);
                int kind = k == 's' ? 1 : k == 'l' ? 2 : 0;
                String[] c = p[i].substring(kind == 0 ? 0 : 1).split(",");
                cells[i - 10] = new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), kind};
            }
            int[] plan = new int[JumpSearch.DIMS];
            for (int k = 0; k < plan.length; k++) plan[k] = p[9].charAt(k) - '0';
            ALL.add(new Template(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), p[5].equals("1"), Integer.parseInt(p[6]), Integer.parseInt(p[7]) / 100.0,
                    Integer.parseInt(p[8]), plan, cells));
        }
    }

    /** Frame unit vector (a, b) of the wall side. */
    public static int[] wallVector(int wall) {
        switch (wall) {
            case AHEAD: return new int[]{1, 0};
            case BEHIND: return new int[]{-1, 0};
            case LEFT: return new int[]{0, 1};
            default: return new int[]{0, -1};
        }
    }

    /** Full blocks and ladders (a thin plate against the wall side, hangable). */
    private static final class World implements PlayerSim.World {
        final Set<Long> solid = new HashSet<>();
        final List<int[]> ladders = new ArrayList<>(); // x, y, z, wall

        static long key(int x, int y, int z) {
            return ((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512);
        }

        void add(int x, int y, int z) {
            solid.add(key(x, y, z));
        }

        void ladder(int x, int y, int z, int wall) {
            ladders.add(new int[]{x, y, z, wall});
            int[] w = wallVector(wall);
            add(x + w[0], y, z + w[1]);
        }

        boolean isLadder(int x, int y, int z) {
            for (int[] l : ladders) if (l[0] == x && l[1] == y && l[2] == z) return true;
            return false;
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++)
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++)
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++)
                        if (solid.contains(key(x, y, z))) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
            for (int[] l : ladders) {
                double x0 = l[0], z0 = l[2], x1 = x0 + 1, z1 = z0 + 1;
                switch (l[3]) {
                    case AHEAD: x0 = x1 - PLATE; break;
                    case BEHIND: x1 = x0 + PLATE; break;
                    case LEFT: z0 = z1 - PLATE; break;
                    default: z1 = z0 + PLATE;
                }
                out.add(new double[]{x0, l[1], z0, x1, l[1] + 1, z1});
            }
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return isLadder(x, y, z);
        }
    }

    private static final double MARGIN = 0.05;
    private static final int[] RUN_UPS = {0, 1, 2, 3};
    private static final int[] LATERAL = {0, 30, 60, 80}; // hundredths of a block, grabs
    private static final int[] HANG_OFFSET = {-15, 0, 15}; // hundredths along the wall, leaps

    private static JumpSearch search(World w, int[] d, boolean leap) {
        JumpSearch js = new JumpSearch(w);
        js.dirX = 1;
        js.dirZ = 0;
        js.edge = 1;
        js.destX = d[2];
        js.destY = d[3];
        js.destZ = d[4];
        js.grab = d[5] == 1;
        js.noJump = leap;
        js.jumped = leap;
        return js;
    }

    private static PlayerSim startSim(World w, int mode, int wall, int runUp, int lat) {
        PlayerSim s = new PlayerSim(w);
        s.vy = -0.0784000015258789;
        if (mode == GRAB) {
            s.x = -runUp + 0.5;
            s.z = 0.5 + lat / 100.0;
            s.onGround = true;
        } else {
            // hanging on the origin ladder, pressed against its plate; lat slides along the wall
            int[] wv = wallVector(wall);
            // cell centre is 0.5 from the wall face; the box centre sits PLATE + HALF_WIDTH (+ a hair) from it
            double fromFace = PLATE + PlayerSim.HALF_WIDTH + 0.0125;
            double cx = 0.5 + wv[0] * (0.5 - fromFace), cz = 0.5 + wv[1] * (0.5 - fromFace);
            s.x = cx + (wv[0] == 0 ? lat / 100.0 : 0);
            s.z = cz + (wv[1] == 0 ? lat / 100.0 : 0);
            s.onGround = false;
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        // d: mode, wall, a, dy, b, destLadder
        List<int[]> dests = new ArrayList<>();
        for (int wall : new int[]{AHEAD, LEFT, RIGHT})
            for (int a = 2; a <= 5; a++) for (int b = 0; b <= 2; b++) for (int dy = 1; dy >= -3; dy--)
                dests.add(new int[]{GRAB, wall, a, dy, b, 1});
        for (int wall : new int[]{BEHIND, LEFT, RIGHT}) {
            for (int a = 1; a <= 4; a++) for (int b = -2; b <= 2; b++) for (int dy = 1; dy >= -3; dy--) {
                dests.add(new int[]{LEAP, wall, a, dy, b, 0});
                dests.add(new int[]{LEAP, wall, a, dy, b, 1});
            }
        }
        for (int[] d : dests) {
            boolean leap = d[0] == LEAP;
            search:
            for (int r : leap ? new int[]{0} : RUN_UPS) for (int lat : leap ? HANG_OFFSET : LATERAL) {
                World w = new World();
                if (!leap) {
                    for (int a = -r; a <= 0; a++) w.add(a, -1, 0);
                    w.ladder(d[2], d[3], d[4], d[1]);
                } else {
                    w.ladder(0, 0, 0, d[1]);
                    if (d[5] == 1) w.ladder(d[2], d[3], d[4], d[1]);
                    else w.add(d[2], d[3] - 1, d[4]);
                    int[] wv = wallVector(d[1]);
                    if (d[2] == wv[0] && d[4] == wv[1]) continue search; // inside the wall
                    if (w.solid.contains(World.key(d[2], d[3], d[4])) || w.solid.contains(World.key(d[2], d[3] + 1, d[4]))) continue search;
                    if (d[5] == 0 && d[3] <= 0 && d[2] == 0 && d[4] == 0) continue search;
                }
                JumpSearch js = search(w, d, leap);
                PlayerSim start = startSim(w, d[0], d[1], r, lat);
                if (!js.search(start, false)) continue;
                // the mover can be a little off the start: the jump must work from all of it
                boolean robust = true;
                for (int ox = leap ? 0 : -15; ox <= (leap ? 0 : 5) && robust; ox += 2) for (int oz = -5; oz <= 5 && robust; oz += 5) {
                    JumpSearch rj = search(w, d, leap);
                    PlayerSim rs = startSim(w, d[0], d[1], r, lat);
                    rs.x += ox / 100.0;
                    rs.z += oz / 100.0;
                    robust = rj.search(rs, false);
                }
                if (!robust) continue;
                Set<String> cells = new TreeSet<>();
                for (int[] l : w.ladders) cells.add("l" + l[0] + "," + l[1] + "," + l[2]);
                JumpSearch.CellSink sink = (x, y, z) -> {
                    for (int cx = PlayerSim.floor(x - 0.3 - MARGIN); cx <= PlayerSim.floor(x + 0.3 + MARGIN); cx++)
                        for (int cy = PlayerSim.floor(y + 1e-3); cy <= PlayerSim.floor(y + 1.8); cy++)
                            for (int cz = PlayerSim.floor(z - 0.3 - MARGIN); cz <= PlayerSim.floor(z + 0.3 + MARGIN); cz++) {
                                if (w.isLadder(cx, cy, cz)) continue;
                                cells.add((w.solid.contains(World.key(cx, cy, cz)) ? "s" : "") + cx + "," + cy + "," + cz);
                            }
                };
                sink.box(start.x, start.y, start.z);
                js.run(start, js.plan, js.jumped, 0, sink);
                for (int[] l : w.ladders) { // the walls the ladders hang on must be there whether or not the box brushed them
                    int[] wv = wallVector(l[3]);
                    cells.add("s" + (l[0] + wv[0]) + "," + l[1] + "," + (l[2] + wv[1]));
                }
                StringBuilder plan = new StringBuilder();
                for (int k : js.plan) plan.append(k);
                out.add(d[0] + " " + d[1] + " " + d[2] + " " + d[3] + " " + d[4] + " " + d[5] + " " + r + " " + lat + " " + js.ticks + " " + plan + " " + String.join(" ", cells));
                System.err.println("ok " + (leap ? "leap" : "grab") + " wall=" + d[1] + " a=" + d[2] + " dy=" + d[3] + " b=" + d[4] + " ladder=" + d[5] + " runUp=" + r + " lat=" + lat + " ticks=" + js.ticks);
                break search;
            }
        }
        try (PrintWriter pw = new PrintWriter(args[0])) {
            pw.println("package baritone.pathing.kinematic;");
            pw.println();
            pw.println("/** Generated by {@link ClimbTemplates#main}: mode wall a dy b destLadder runUp lateral(hundredths) ticks plan cells... */");
            pw.println("final class ClimbTemplateData {");
            pw.println("    static final String[] DATA = {");
            for (String s : out) pw.println("            \"" + s + "\",");
            pw.println("    };");
            pw.println("}");
        }
    }
}
