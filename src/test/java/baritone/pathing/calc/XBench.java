/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Shared file, identical in every fork.
 */
package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Three-way bench: Ostinato's PathBench worlds and queries (same seeds, same 60 queries), run through whichever fork's
 * unmodified pathfinder this file is compiled into. Each fork prices moves with its own cost model, so besides plan time,
 * nodes and failures it records the path's move count and straight-line length, which mean the same thing in every fork.
 */
public final class XBench {

    public enum Length {
        SHORT(40), MEDIUM(250), LONG(500);
        public final int dx;

        Length(int dx) {
            this.dx = dx;
        }
    }

    public static final int SIZE_X = 1000, SIZE_Z = 96;
    public static final int QUERIES = 4;
    public static final long TIMEOUT_MS = 10_000;

    public record Query(XTerrain terrain, Length length, int n, BetterBlockPos start, BetterBlockPos goalPos, Goal goal) {
        public String id() {
            return terrain + "/" + length + "/" + n;
        }
    }

    public record Result(Query q, boolean ok, double cost, int nodes, double ms, long allocBytes, int moves, double blocks) {}

    public interface Finder {
        AbstractNodeCostSearch create(BetterBlockPos start, Goal goal, CalculationContext ctx);
    }

    private static final Map<XTerrain, XWorld> WORLDS = new HashMap<>();

    public static synchronized XWorld world(XTerrain t) {
        return WORLDS.computeIfAbsent(t, k -> new XWorld(k.build(SIZE_X, SIZE_Z, 0x057A7170L)));
    }

    private static boolean standable(XVoxels w, int x, int y, int z) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState below = w.real(x, y - 1, z);
        return w.real(x, y, z) == air && w.real(x, y + 1, z) == air && below != air && below.getFluidState().isEmpty();
    }

    static BetterBlockPos spot(XVoxels w, XTerrain t, int x0, int zWant) {
        for (int dx = 0; dx < 60; dx++) {
            int x = x0 + ((dx & 1) == 0 ? dx / 2 : -(dx + 1) / 2);
            if (x < 1 || x >= SIZE_X - 1) continue;
            BetterBlockPos p = spotInColumn(w, t, x, zWant);
            if (p != null) return p;
        }
        throw new IllegalStateException("no spot near column " + x0 + " for " + t);
    }

    private static BetterBlockPos spotInColumn(XVoxels w, XTerrain t, int x, int zWant) {
        for (int d = 0; d < SIZE_Z; d++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int z = zWant + d * sgn;
                if (z < 1 || z >= SIZE_Z - 1) continue;
                if (t == XTerrain.CAVES) {
                    for (int dy = 0; dy <= 14; dy++) {
                        for (int s2 = -1; s2 <= 1; s2 += 2) {
                            int y = XTerrain.GROUND + dy * s2;
                            if (standable(w, x, y, z)) return new BetterBlockPos(x, y, z);
                        }
                    }
                } else {
                    int y = w.top(x, z) + 1;
                    if (standable(w, x, y, z)) return new BetterBlockPos(x, y, z);
                }
            }
        }
        return null;
    }

    /** The same query set as Ostinato's PathBench.queries (same Random(1234) draw order). */
    public static List<Query> queries(XTerrain... terrains) {
        List<Query> out = new ArrayList<>();
        Random r = new Random(1234);
        for (XTerrain t : terrains) {
            XVoxels w = world(t).voxels;
            for (Length len : Length.values()) {
                for (int n = 0; n < QUERIES; n++) {
                    int x0 = 8 + r.nextInt(SIZE_X - len.dx - 16);
                    int z0 = 8 + r.nextInt(SIZE_Z - 16), z1 = 8 + r.nextInt(SIZE_Z - 16);
                    BetterBlockPos s = spot(w, t, x0, z0);
                    BetterBlockPos g = spot(w, t, x0 + len.dx, z1);
                    out.add(new Query(t, len, n, s, g, new GoalBlock(g.x, g.y, g.z)));
                }
            }
        }
        return out;
    }

    private static Field nodesField;

    /** The path's total cost in its own fork's ticks: the last node's g-cost (stock Path has no totalCost()). */
    static double cost(IPath p) {
        try {
            if (nodesField == null) {
                nodesField = baritone.pathing.calc.Path.class.getDeclaredField("nodes");
                nodesField.setAccessible(true);
            }
            List<?> nodes = (List<?>) nodesField.get(p);
            return ((PathNode) nodes.get(nodes.size() - 1)).cost;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Result run(Query q, Finder finder) {
        XWorld w = world(q.terrain);
        CalculationContext ctx = XAdapter.context(w);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream())); // A* prints progress lines
        long a0 = mx.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        Optional<IPath> p;
        try {
            AbstractNodeCostSearch f = finder.create(q.start, q.goal, ctx);
            p = f.calculate0(TIMEOUT_MS, TIMEOUT_MS);
        } finally {
            System.setOut(out);
        }
        long t1 = System.nanoTime();
        long a1 = mx.getThreadAllocatedBytes(tid);
        boolean ok = p.isPresent() && q.goal.isInGoal(p.get().getDest());
        double cost = p.map(XBench::cost).orElse(Double.NaN);
        int nodes = p.map(IPath::getNumNodesConsidered).orElse(-1);
        int moves = -1;
        double blocks = Double.NaN;
        if (p.isPresent()) {
            List<BetterBlockPos> pos = p.get().positions();
            moves = pos.size() - 1;
            blocks = 0;
            for (int i = 1; i < pos.size(); i++) {
                BetterBlockPos a = pos.get(i - 1), b = pos.get(i);
                double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
                blocks += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        return new Result(q, ok, cost, nodes, (t1 - t0) / 1e6, a1 - a0, moves, blocks);
    }

    static double pct(List<Double> xs, double p) {
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        if (s.isEmpty()) return Double.NaN;
        int i = (int) Math.ceil(p * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, i)));
    }

    /** One warm-up pass over the queries, then {@code reps} timed passes. */
    public static List<Result> bench(List<Query> qs, Finder f, int reps) {
        for (Query q : qs) run(q, f);
        List<Result> all = new ArrayList<>();
        for (int i = 0; i < reps; i++) for (Query q : qs) all.add(run(q, f));
        return all;
    }

    public static String report(String label, List<Result> rs, int reps) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(label).append("\n\n");
        sb.append("| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean moves | mean blocks | mean MB alloc |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, List<Result>> by = new LinkedHashMap<>();
        for (Result r : rs) by.computeIfAbsent(r.q.terrain + "|" + r.q.length, k -> new ArrayList<>()).add(r);
        List<Double> allMs = new ArrayList<>();
        int fails = 0;
        for (Map.Entry<String, List<Result>> e : by.entrySet()) {
            List<Result> l = e.getValue();
            List<Double> ms = l.stream().map(Result::ms).toList();
            allMs.addAll(ms);
            long f = l.stream().filter(r -> !r.ok).count() / Math.max(1, reps);
            fails += f;
            List<Double> nodes = l.stream().map(r -> (double) r.nodes).toList();
            double cost = l.stream().filter(Result::ok).mapToDouble(Result::cost).average().orElse(Double.NaN);
            double moves = l.stream().filter(Result::ok).mapToInt(Result::moves).average().orElse(Double.NaN);
            double blocks = l.stream().filter(Result::ok).mapToDouble(Result::blocks).average().orElse(Double.NaN);
            double mb = l.stream().mapToLong(Result::allocBytes).average().orElse(0) / 1e6;
            String[] k = e.getKey().split("\\|");
            sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %.1f | %.1f | %.0f | %.1f | %.1f | %.1f | %.1f |%n", k[0], k[1], l.size() / reps, f, pct(ms, .5), pct(ms, .95), pct(nodes, .5), cost, moves, blocks, mb));
        }
        sb.append(String.format(Locale.ROOT, "%nAll: median %.1f ms, p95 %.1f ms, failures %d of %d queries, %d reps.%n%n", pct(allMs, .5), pct(allMs, .95), fails, rs.size() / reps, reps));
        return sb.toString();
    }

    public static String csv(String fork, String config, List<Result> rs) {
        StringBuilder sb = new StringBuilder("fork,config,query,ok,cost,nodes,ms,alloc_bytes,moves,blocks\n");
        for (Result r : rs)
            sb.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%.6f,%d,%.3f,%d,%d,%.3f%n", fork, config, r.q.id(), r.ok, r.cost, r.nodes, r.ms, r.allocBytes, r.moves, r.blocks));
        return sb.toString();
    }

    /** The query set itself, so the three runs can be checked to have planned the same trips. */
    public static String describe(List<Query> qs) {
        StringBuilder sb = new StringBuilder();
        for (Query q : qs) sb.append(q.id()).append(' ').append(q.start).append(" -> ").append(q.goalPos).append('\n');
        return sb.toString();
    }

    public static void write(String name, String text) {
        try {
            Path dir = Path.of(System.getProperty("user.dir"), "build", "xbench");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
