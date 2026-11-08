/*
 * Ostinato 2.0 Phase 05B: how far the simulator's multi-tick predictions drift from live laps, headless.
 *
 * Off unless OSTINATO_SIMEDGE_TRACE names a directory holding simedge-trace.csv and simedge-paths.csv (written by a live
 * run with -Dostinato.legatotest.trace=true). The course is rebuilt as SimEdgeCourseTest.course(), with live feet y F + 1
 * at synthetic Y. Four measurements, written to build/phase05b/:
 *   1. actual inputs: from each recorded tick, the recorded yaw and keys replayed open loop for k ticks;
 *   2. planner-assumed inputs: from each recorded tick, SimEdge's own edge controller chained along the path being run;
 *   3. per edge: the ticks the executor spent on each path position against SimEdge's one-edge prediction from the
 *      recorded entry state, SimEdge's chained price from the plan's start body, and the legacy cost;
 *   4. steering: the yaw, jump and sprint the SimEdge controller would choose on each tick against what the executor did.
 * Windows with a server position packet or a sneak are left out.
 */
package baritone.pathing.calc;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.legato.LegatoCourse;
import baritone.pathing.movement.CalculationContext;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static baritone.pathing.calc.MaestroTest.Y;

public class SimEdgeDivergenceTest {

    static final int[] KS = {1, 2, 5, 10, 20, 40};
    static final int DY = Y - (LegatoCourse.F + 1);

    record Row(String seed, int lap, int rep, String leg, String mode, int t, double x, double y, double z, double vx, double vy, double vz,
               boolean ground, boolean sprinting, boolean collided, float yaw, boolean fwd, boolean back, boolean left, boolean right,
               boolean jump, boolean sprintKey, boolean sneak, String driver, int path, int pos, int packets) {}

    record PathRec(List<int[]> cells, List<String> moves, boolean simEdge, int t) {}

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void divergence() throws IOException {
        String dir = System.getenv("OSTINATO_SIMEDGE_TRACE");
        Assume.assumeTrue(dir != null);
        Map<String, List<Row>> laps = new LinkedHashMap<>();
        List<String> tl = Files.readAllLines(Path.of(dir, "simedge-trace.csv"));
        for (String line : tl.subList(1, tl.size())) {
            if (line.startsWith("seed,")) continue; // a later run appended under its own header
            String[] c = line.split(",");
            Row r = new Row(c[0], i(c[1]), i(c[2]), c[3], c[4], i(c[5]), d(c[6]), d(c[7]) + DY, d(c[8]), d(c[9]), d(c[10]), d(c[11]),
                    b(c[12]), b(c[13]), b(c[14]), (float) d(c[15]), b(c[16]), b(c[17]), b(c[18]), b(c[19]), b(c[20]), b(c[21]), b(c[22]),
                    c[23], i(c[24]), i(c[25]), i(c[26]));
            laps.computeIfAbsent(r.seed + "/" + r.lap, k -> new ArrayList<>()).add(r);
        }
        Map<String, PathRec> paths = new HashMap<>();
        List<String> pl = Files.readAllLines(Path.of(dir, "simedge-paths.csv"));
        for (String line : pl.subList(1, pl.size())) {
            if (line.startsWith("seed,")) continue;
            String[] c = line.split(",");
            List<int[]> cells = new ArrayList<>();
            for (String s : c[8].split("\\|")) {
                String[] p = s.split(":");
                cells.add(new int[]{i(p[0]), i(p[1]) + DY, i(p[2])});
            }
            List<String> moves = c.length > 9 && !c[9].isEmpty() ? List.of(c[9].split("\\|")) : List.of();
            paths.put(c[0] + "/" + c[1] + "/" + c[6], new PathRec(cells, moves, b(c[7]), i(c[5])));
        }

        SyntheticWorld w = SimEdgeCourseTest.course();
        course = w;
        CalculationContext ctx = PathBench.context(w);
        BsiWorld world = new BsiWorld(ctx.bsi);
        SimEdge edge = new SimEdge(world, ctx);
        edge.sprintAscends = true;
        PlayerSim sim = new PlayerSim(world);

        // [driver][k] -> errors
        Map<String, List<List<double[]>>> open = new TreeMap<>(), planned = new TreeMap<>();
        Map<String, int[]> plannedCover = new TreeMap<>();
        StringBuilder edges = new StringBuilder("seed,lap,rep,leg,mode,path,pos,move,driver,actual,simEntry,simChained,legacy,entrySpeed,entryGround\n");
        StringBuilder steer = new StringBuilder();
        Map<String, List<Double>> yawErr = new TreeMap<>();
        Map<String, int[]> jumpAgree = new TreeMap<>(), sprintAgree = new TreeMap<>();
        StringBuilder firstPaths = new StringBuilder("seed,lap,rep,leg,mode,simEdge,edgesDone,actual,simChained,legacy,cells,toGoalEnd\n");

        for (List<Row> rows : laps.values()) {
            int n = rows.size();
            // jumpTicks is not recorded: carry it with one-step predictions from each recorded state
            int[] jt = new int[n + 1];
            for (int t = 0; t < n; t++) {
                Row r = rows.get(t);
                load(sim, r, jt[t]);
                if (!Float.isNaN(r.yaw)) tick(sim, r);
                jt[t + 1] = sim.jumpTicks;
            }
            // 1. actual inputs, open loop
            for (int t = 0; t < n; t++) {
                Row r0 = rows.get(t);
                if (Float.isNaN(r0.yaw)) continue;
                load(sim, r0, jt[t]);
                int ki = 0;
                for (int k = 1; k <= 40 && t + k < n; k++) {
                    Row in = rows.get(t + k - 1), at = rows.get(t + k);
                    if (Float.isNaN(in.yaw) || in.sneak || at.packets != r0.packets || at.t != r0.t + k) break;
                    tick(sim, in);
                    if (k == KS[ki]) {
                        add(open, r0.driver, ki, sim.x - at.x, sim.y - at.y, sim.z - at.z);
                        add(open, "ALL", ki, sim.x - at.x, sim.y - at.y, sim.z - at.z);
                        if (++ki == KS.length) break;
                    }
                }
            }
            // 2. planner-assumed inputs, and 4. steering
            for (int t = 1; t < n; t++) {
                Row r0 = rows.get(t);
                if (r0.path < 0 || r0.pos < 0 || Float.isNaN(rows.get(t - 1).yaw)) continue;
                PathRec p = paths.get(r0.seed + "/" + r0.lap + "/" + r0.path);
                if (p == null || r0.pos + 1 >= p.cells.size()) continue;
                SimEdge.Body body = body(r0, rows.get(t - 1).yaw, jt[t], p, r0.pos);
                edge.trail = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
                SimEdge.Result res = new SimEdge.Result();
                int stop = 0; // why the chain ended: 0 enough ticks, 1 not a sim edge, 2 sim missed, 3 path end
                for (int e = r0.pos; ; e++) {
                    if (edge.trail.size() / 3 >= 40) break;
                    if (e + 1 >= p.cells.size()) {
                        stop = 3;
                        break;
                    }
                    int[] a = p.cells.get(e), c = p.cells.get(e + 1);
                    int kind = kind(p, e);
                    if (kind == SimEdge.NONE || !edge.eligible(kind, a[0], a[1], a[2], c[0], c[1], c[2])) {
                        stop = 1;
                        break;
                    }
                    if (!edge.run(body, kind, a[0], a[1], a[2], c[0], c[1], c[2], res)) {
                        stop = 2;
                        break;
                    }
                    body = new SimEdge.Body().set(res.end);
                }
                var tr = edge.trail;
                edge.trail = null;
                int[] cover = plannedCover.computeIfAbsent(r0.driver, k -> new int[KS.length * 2]);
                for (int ki = 0; ki < KS.length; ki++) {
                    int k = KS[ki];
                    if (t + k >= n) break;
                    Row at = rows.get(t + k);
                    if (at.packets != r0.packets || at.t != r0.t + k) break;
                    cover[ki * 2]++;
                    if (tr.size() / 3 < k) continue;
                    cover[ki * 2 + 1]++;
                    double px = tr.getDouble((k - 1) * 3), py = tr.getDouble((k - 1) * 3 + 1), pz = tr.getDouble((k - 1) * 3 + 2);
                    add(planned, r0.driver, ki, px - at.x, py - at.y, pz - at.z);
                    add(planned, "ALL", ki, px - at.x, py - at.y, pz - at.z);
                }
                steering(r0, rows.get(t - 1).yaw, p, yawErr, jumpAgree, sprintAgree);
            }
            // 3. per edge, and the first plan's realized prefix
            Map<Integer, List<int[]>> runs = new LinkedHashMap<>(); // path -> [pos, startIdx, endIdx]
            int prevPath = -2, prevPos = -2;
            for (int t = 0; t < n; t++) {
                Row r = rows.get(t);
                if (r.path < 0 || r.pos < 0) {
                    prevPath = -2;
                    continue;
                }
                List<int[]> rs = runs.computeIfAbsent(r.path, k -> new ArrayList<>());
                if (r.path == prevPath && r.pos == prevPos) rs.get(rs.size() - 1)[2] = t;
                else rs.add(new int[]{r.pos, t, t});
                prevPath = r.path;
                prevPos = r.pos;
            }
            Row head = rows.get(0);
            for (var en : runs.entrySet()) {
                PathRec p = paths.get(head.seed + "/" + head.lap + "/" + en.getKey());
                if (p == null) continue;
                SimEdge.Body startBody = null;
                List<SimEdge.Priced> chained = null;
                List<int[]> rs = en.getValue();
                for (int ri = 0; ri < rs.size(); ri++) {
                    int[] run = rs.get(ri);
                    int pos = run[0];
                    if (pos + 1 >= p.cells.size()) continue;
                    // completed: the next run on this path is the next position
                    boolean done = ri + 1 < rs.size() && rs.get(ri + 1)[0] == pos + 1;
                    if (!done) continue;
                    Row entry = rows.get(run[1]);
                    if (chained == null) {
                        Row first = rows.get(rs.get(0)[1]);
                        startBody = body(first, run[1] > 0 && !Float.isNaN(rows.get(rs.get(0)[1] - 1).yaw) ? rows.get(rs.get(0)[1] - 1).yaw : first.yaw,
                                jt[rs.get(0)[1]], p, rs.get(0)[0]);
                        chained = edge.price(p.cells.subList(rs.get(0)[0], p.cells.size()), startBody);
                    }
                    int ci = pos - rs.get(0)[0];
                    SimEdge.Priced pr = ci >= 0 && ci < chained.size() ? chained.get(ci) : null;
                    int[] a = p.cells.get(pos), c = p.cells.get(pos + 1);
                    int kind = kind(p, pos);
                    String simEntry = "";
                    if (kind != SimEdge.NONE && edge.eligible(kind, a[0], a[1], a[2], c[0], c[1], c[2]) && run[1] > 0) {
                        SimEdge.Body b = body(entry, rows.get(run[1] - 1).yaw, jt[run[1]], p, pos);
                        SimEdge.Result res = new SimEdge.Result();
                        simEntry = edge.run(b, kind, a[0], a[1], a[2], c[0], c[1], c[2], res) ? Integer.toString(res.ticks) : "miss";
                    }
                    edges.append(String.join(",", head.seed, "" + head.lap, "" + head.rep, head.leg, head.mode, "" + en.getKey(), "" + pos,
                            pos < p.moves.size() ? p.moves.get(pos) : "?", majority(rows, run[1], run[2]), "" + (run[2] - run[1] + 1), simEntry,
                            pr == null || !pr.simulated() ? "" : String.format(Locale.ROOT, "%.2f", pr.sim()),
                            pr == null ? "" : String.format(Locale.ROOT, "%.2f", pr.legacy()),
                            String.format(Locale.ROOT, "%.3f", Math.hypot(entry.vx, entry.vz)), "" + entry.ground)).append('\n');
                }
                if (en.getKey() == 0) {
                    // the first plan: from the lap's start body, the realized ticks of the edges it completed
                    int done = 0, actual = 0;
                    for (int ri = 0; ri + 1 < rs.size(); ri++) {
                        if (rs.get(ri + 1)[0] != rs.get(ri)[0] + 1) break;
                        done++;
                        actual += rs.get(ri)[2] - rs.get(ri)[1] + 1;
                    }
                    SimEdge.Body rest = new SimEdge.Body();
                    rest.known = true;
                    rest.x = head.x;
                    rest.y = head.y;
                    rest.z = head.z;
                    rest.onGround = true;
                    rest.yaw = -90;
                    int from = rs.get(0)[0];
                    List<SimEdge.Priced> pr = edge.price(p.cells.subList(from, Math.min(p.cells.size(), from + done + 1)), rest);
                    double simSum = 0, legSum = 0;
                    for (SimEdge.Priced q : pr) {
                        simSum += q.sim();
                        legSum += q.legacy();
                    }
                    int[] end = p.cells.get(p.cells.size() - 1);
                    firstPaths.append(String.join(",", head.seed, "" + head.lap, "" + head.rep, head.leg, head.mode, "" + p.simEdge, "" + done, "" + actual,
                            String.format(Locale.ROOT, "%.2f", simSum), String.format(Locale.ROOT, "%.2f", legSum), "" + p.cells.size(),
                            end[0] + ":" + (end[1] - DY) + ":" + end[2])).append('\n');
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("== 1. actual inputs, open loop: horizontal / vertical position error (m) at k ticks: median p90 max (n)\n");
        report(out, open);
        out.append("\n== 2. planner-assumed (SimEdge edge controller chained along the run path from the recorded state)\n");
        report(out, planned);
        out.append("coverage (samples with a k-tick sim chain / samples):");
        for (var en : plannedCover.entrySet()) {
            out.append("\n  ").append(en.getKey());
            for (int ki = 0; ki < KS.length; ki++) out.append(String.format(Locale.ROOT, " k%d=%d/%d", KS[ki], en.getValue()[ki * 2 + 1], en.getValue()[ki * 2]));
        }
        out.append("\n\n== 4. steering: |SimEdge yaw choice - executor yaw| (deg) median p90, by driver and edge kind\n");
        for (var en : yawErr.entrySet()) {
            List<Double> v = en.getValue();
            Collections.sort(v);
            out.append(String.format(Locale.ROOT, "  %-22s median %.1f p90 %.1f n=%d%n", en.getKey(), q(v, .5), q(v, .9), v.size()));
        }
        out.append("jump (both / sim only / executor only / neither) on ascend edges, ground ticks:\n");
        for (var en : jumpAgree.entrySet()) out.append("  ").append(en.getKey()).append(' ').append(Arrays.toString(en.getValue())).append('\n');
        out.append("sprint key (both / sim only / executor only / neither):\n");
        for (var en : sprintAgree.entrySet()) out.append("  ").append(en.getKey()).append(' ').append(Arrays.toString(en.getValue())).append('\n');
        System.out.println(out);
        Path o = Path.of(System.getProperty("user.dir"), "build", "phase05b");
        Files.createDirectories(o);
        Files.writeString(o.resolve("divergence-summary.txt"), out.toString());
        Files.writeString(o.resolve("divergence-edges.csv"), edges.toString());
        Files.writeString(o.resolve("divergence-firstpaths.csv"), firstPaths.toString());
    }

    /** The SimEdge body for a recorded state at path position {@code pos}: the yaw the body had before this tick turned. */
    private static SimEdge.Body body(Row r, float yawBefore, int jumpTicks, PathRec p, int pos) {
        SimEdge.Body b = new SimEdge.Body();
        b.known = true;
        b.x = r.x;
        b.y = r.y;
        b.z = r.z;
        b.vx = r.vx;
        b.vy = r.vy;
        b.vz = r.vz;
        b.yaw = yawBefore;
        b.onGround = r.ground;
        b.sprinting = r.sprinting;
        b.jumpTicks = jumpTicks;
        b.lastKind = SimEdge.NONE;
        if (pos > 0) {
            b.lastKind = kind(p, pos - 1);
            b.lastDx = p.cells.get(pos)[0] - p.cells.get(pos - 1)[0];
            b.lastDz = p.cells.get(pos)[2] - p.cells.get(pos - 1)[2];
        }
        return b;
    }

    private static int kind(PathRec p, int e) {
        if (e >= p.moves.size()) return SimEdge.NONE;
        return switch (p.moves.get(e)) {
            case "Traverse" -> SimEdge.TRAVERSE;
            case "Diagonal" -> p.cells.get(e)[1] == p.cells.get(e + 1)[1] ? SimEdge.DIAGONAL : SimEdge.NONE;
            case "Ascend" -> SimEdge.ASCEND;
            default -> SimEdge.NONE;
        };
    }

    /** What SimEdge's edge controller would do this tick against what the executor did. */
    private static void steering(Row r, float yawBefore, PathRec p, Map<String, List<Double>> yawErr, Map<String, int[]> jumpAgree, Map<String, int[]> sprintAgree) {
        int[] a = p.cells.get(r.pos), c = p.cells.get(r.pos + 1);
        int kind = kind(p, r.pos);
        String kn = kind == SimEdge.TRAVERSE ? "traverse" : kind == SimEdge.DIAGONAL ? "diagonal" : kind == SimEdge.ASCEND ? "ascend" : "other";
        double tx = c[0] + 0.5, tz = c[2] + 0.5;
        float want = (float) Math.toDegrees(Math.atan2(-(tx - r.x), tz - r.z));
        float yaw = yawBefore + Math.max(-SimEdge.YAW_CAP, Math.min(SimEdge.YAW_CAP, wrap(want - yawBefore)));
        double err = Math.abs(wrap(yaw - r.yaw));
        yawErr.computeIfAbsent(r.driver + "/" + kn, k -> new ArrayList<>()).add(err);
        if (kind == SimEdge.ASCEND && r.ground && r.y < c[1] - 1e-3) {
            int ddx = c[0] - a[0], ddz = c[2] - a[2];
            double lateral = ddx != 0 ? r.vz : r.vx;
            double flat = Math.abs(ddx) * Math.abs(tx - r.x) + Math.abs(ddz) * Math.abs(tz - r.z);
            double side = Math.abs(ddz) * Math.abs(tx - r.x) + Math.abs(ddx) * Math.abs(tz - r.z);
            boolean headClear = air(a[0] + 1, a[1] + 2, a[2]) && air(a[0] - 1, a[1] + 2, a[2]) && air(a[0], a[1] + 2, a[2] + 1) && air(a[0], a[1] + 2, a[2] - 1);
            boolean simJump = Math.abs(lateral) <= 0.1 && (headClear || flat <= 1.2 && side <= 0.2);
            int[] j = jumpAgree.computeIfAbsent(r.driver, k -> new int[4]);
            j[simJump && r.jump ? 0 : simJump ? 1 : r.jump ? 2 : 3]++;
        }
        boolean simSprint = kind != SimEdge.ASCEND || (r.pos > 0 && kind(p, r.pos - 1) == SimEdge.TRAVERSE
                && p.cells.get(r.pos)[0] - p.cells.get(r.pos - 1)[0] == c[0] - a[0] && p.cells.get(r.pos)[2] - p.cells.get(r.pos - 1)[2] == c[2] - a[2]);
        int[] s = sprintAgree.computeIfAbsent(r.driver + "/" + kn, k -> new int[4]);
        s[simSprint && r.sprintKey ? 0 : simSprint ? 1 : r.sprintKey ? 2 : 3]++;
    }

    private static SyntheticWorld course;

    private static boolean air(int x, int y, int z) {
        return course.get0(x, y, z).isAir();
    }

    private static void load(PlayerSim s, Row r, int jumpTicks) {
        s.x = r.x;
        s.y = r.y;
        s.z = r.z;
        s.vx = r.vx;
        s.vy = r.vy;
        s.vz = r.vz;
        s.onGround = r.ground;
        s.sprinting = r.sprinting;
        s.collidedH = r.collided;
        s.minorCollision = false;
        s.swimming = false;
        s.supported = false;
        s.onGroundNoBlocks = false;
        s.jumpTicks = jumpTicks;
    }

    private static void tick(PlayerSim s, Row r) {
        s.tick(r.yaw, (r.fwd ? 1 : 0) - (r.back ? 1 : 0), (r.left ? 1 : 0) - (r.right ? 1 : 0), r.sprintKey, r.jump);
    }

    private static String majority(List<Row> rows, int from, int to) {
        Map<String, Integer> m = new HashMap<>();
        for (int t = from; t <= to; t++) m.merge(rows.get(t).driver, 1, Integer::sum);
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
    }

    private static void add(Map<String, List<List<double[]>>> m, String key, int ki, double ex, double ey, double ez) {
        List<List<double[]>> l = m.computeIfAbsent(key, k -> {
            List<List<double[]>> x = new ArrayList<>();
            for (int i = 0; i < KS.length; i++) x.add(new ArrayList<>());
            return x;
        });
        l.get(ki).add(new double[]{Math.hypot(ex, ez), Math.abs(ey)});
    }

    private static void report(StringBuilder out, Map<String, List<List<double[]>>> m) {
        for (var en : m.entrySet()) {
            out.append("  ").append(en.getKey()).append('\n');
            for (int ki = 0; ki < KS.length; ki++) {
                List<double[]> v = en.getValue().get(ki);
                if (v.isEmpty()) continue;
                List<Double> h = new ArrayList<>(), y = new ArrayList<>();
                for (double[] e : v) {
                    h.add(e[0]);
                    y.add(e[1]);
                }
                Collections.sort(h);
                Collections.sort(y);
                out.append(String.format(Locale.ROOT, "    k=%-2d h %.4f %.4f %.4f  v %.4f %.4f %.4f  (n=%d)%n", KS[ki], q(h, .5), q(h, .9), h.get(h.size() - 1),
                        q(y, .5), q(y, .9), y.get(y.size() - 1), v.size()));
            }
        }
    }

    private static double q(List<Double> sorted, double f) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(f * (sorted.size() - 1) + 0.5)));
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static int i(String s) {
        return Integer.parseInt(s.trim());
    }

    private static double d(String s) {
        return s.equals("NaN") ? Double.NaN : Double.parseDouble(s);
    }

    private static boolean b(String s) {
        return Boolean.parseBoolean(s.trim());
    }
}
