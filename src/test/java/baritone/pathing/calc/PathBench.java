/*
 * Ostinato: headless, repeatable pathfinding benchmark. New file, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.HeadlessToolSet;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.calc.bench.Terrain;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * Ostinato: runs the real pathfinder (whatever {@link #finder} builds) over synthetic {@link Terrain} worlds with no
 * client and reports plan time (median / p95), nodes expanded, path cost, allocation and failure rate per terrain and
 * route length. Everything is seeded, so path costs and node counts are exactly repeatable; only times vary.
 */
public final class PathBench {

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

    public record Query(Terrain terrain, Length length, int n, BetterBlockPos start, Goal goal) {
        public String id() {
            return terrain + "/" + length + "/" + n;
        }
    }

    public record Result(Query q, boolean ok, double cost, int nodes, double ms, long allocBytes) {}

    /** Builds a pathfinder for a query; the default is the stock A*. */
    public interface Finder {
        AbstractNodeCostSearch create(BetterBlockPos start, Goal goal, CalculationContext ctx);
    }

    public static final Finder ASTAR = (s, g, c) -> new AStarPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c);

    /**
     * Planner configurations. Physics does not have its own search: it walks a Baritone path, so its
     * plan times match Baritone. Soprano here is the hand-cost jump set (parkour, neo, momentum, climb)
     * with the kinematic 4-block gap left off. Kinematic is that set plus the 4-block gap.
     */
    public enum Engine {
        BARITONE, PHYSICS, SOPRANO, KINEMATIC
    }

    public static void apply(Engine engine) {
        var s = Baritone.settings();
        s.experimentalMovement.value = false;
        s.allowParkour.value = false;
        s.allowParkourAscend.value = false;
        s.allowParkourPlace.value = false;
        s.allowNeos.value = false;
        s.allowMomentumJumps.value = false;
        s.allowClimbJumps.value = false;
        s.allowDiagonalAscend.value = false;
        s.allowDiagonalDescend.value = false;
        s.kinematicTravel.value = false;
        s.physicsTravel.value = false;
        s.allowBreak.value = true;
        switch (engine) {
            case BARITONE -> {
            }
            case PHYSICS -> s.physicsTravel.value = true;
            case SOPRANO -> {
                s.allowParkour.value = true;
                s.allowParkourAscend.value = true;
                s.allowNeos.value = true;
                s.allowMomentumJumps.value = true;
                s.allowClimbJumps.value = true;
            }
            case KINEMATIC -> {
                s.kinematicTravel.value = true;
                s.allowParkour.value = true;
                s.allowParkourAscend.value = true;
                s.allowNeos.value = true;
                s.allowMomentumJumps.value = true;
                s.allowClimbJumps.value = true;
            }
        }
    }

    /** Weighted A* exactly as PathingBehavior picks it: weight only on far segments (settings pathingFarSegment*). */
    public static Finder weighted(double w) {
        return (s, g, c) -> {
            Baritone.settings().pathingFarSegmentWeight.value = w;
            try {
                return new AStarPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c, baritone.behavior.PathingBehavior.farSegmentWeight(g, s));
            } finally {
                Baritone.settings().pathingFarSegmentWeight.value = 1.0;
            }
        };
    }

    /** OSTINATO_BENCH_FINDER: astar (default), wa:&lt;weight&gt;. */
    public static Finder finder(String spec) {
        if (spec == null || spec.isEmpty() || spec.equals("astar")) return ASTAR;
        if (spec.startsWith("wa:")) return weighted(Double.parseDouble(spec.substring(3)));
        if (spec.startsWith("hpa")) { // hpa[:factor[:radius]]; a fresh SectionGraph per query = cold cache (worst case)
            String[] p = spec.split(":");
            double factor = p.length > 1 ? Double.parseDouble(p[1]) : 0;
            int radius = p.length > 2 ? Integer.parseInt(p[2]) : 1;
            // Ostinato: hpa:factor:radius:levels:coarseShift:candidates (multi-level and second-candidate options)
            int levels = p.length > 3 ? Integer.parseInt(p[3]) : 1;
            int fshift = p.length > 4 ? Integer.parseInt(p[4]) : 2;
            int cands = p.length > 5 ? Integer.parseInt(p[5]) : 1;
            boolean warm = System.getenv("OSTINATO_BENCH_WARM") != null;
            return (s, g, c) -> {
                Baritone.settings().pathingHierarchyHeuristicFactor.value = factor;
                Baritone.settings().pathingHierarchyCorridorRadius.value = radius;
                Baritone.settings().pathingHierarchyLevels.value = levels;
                Baritone.settings().pathingHierarchyCoarseFactorShift.value = fshift;
                Baritone.settings().pathingHierarchyCandidates.value = cands;
                baritone.pathing.calc.hierarchy.SectionGraph graph = warm ? WARM.computeIfAbsent(c.bsi, k -> new baritone.pathing.calc.hierarchy.SectionGraph()) : new baritone.pathing.calc.hierarchy.SectionGraph();
                return new HierarchicalPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c, graph, 1);
            };
        }
        throw new IllegalArgumentException(spec);
    }

    /** With OSTINATO_BENCH_WARM: one SectionGraph per world, reused across queries (as if prefetched while chunks loaded). */
    private static final Map<Object, baritone.pathing.calc.hierarchy.SectionGraph> WARM = new IdentityHashMap<>();

    private static boolean booted;

    public static synchronized void boot() {
        if (booted) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Baritone.settings().considerPotionEffects.value = false; // HeadlessToolSet has no player
        Baritone.settings().chatDebug.value = false;
        Baritone.settings().desktopNotifications.value = false;
        booted = true;
    }

    private static final Map<Terrain, SyntheticWorld> WORLDS = new HashMap<>(); // not EnumMap: that would initialise Terrain (and Blocks) before boot()

    public static synchronized SyntheticWorld world(Terrain t) {
        return WORLDS.computeIfAbsent(t, k -> k.build(SIZE_X, SIZE_Z, 0x057A7170L));
    }

    public static CalculationContext context(SyntheticWorld w) {
        return new CalculationContext(w, new HeadlessToolSet(), Baritone.settings().allowPlace.value, SyntheticWorld.MIN_Y, SyntheticWorld.HEIGHT);
    }

    private static boolean standable(SyntheticWorld w, int x, int y, int z) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState below = w.get0(x, y - 1, z);
        return w.get0(x, y, z) == air && w.get0(x, y + 1, z) == air && below != air && below.getFluidState().isEmpty();
    }

    /** A spot to stand in column x near z: for caves, inside a tunnel; otherwise on the surface (or the water's bottom-most dry neighbour). */
    static BetterBlockPos spot(SyntheticWorld w, Terrain t, int x0, int zWant) {
        for (int dx = 0; dx < 60; dx++) {
            int x = x0 + ((dx & 1) == 0 ? dx / 2 : -(dx + 1) / 2);
            if (x < 1 || x >= SIZE_X - 1) continue;
            BetterBlockPos p = spotInColumn(w, t, x, zWant);
            if (p != null) return p;
        }
        throw new IllegalStateException("no spot near column " + x0 + " for " + t);
    }

    private static BetterBlockPos spotInColumn(SyntheticWorld w, Terrain t, int x, int zWant) {
        for (int d = 0; d < SIZE_Z; d++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int z = zWant + d * sgn;
                if (z < 1 || z >= SIZE_Z - 1) continue;
                if (t == Terrain.CAVES) {
                    for (int dy = 0; dy <= 14; dy++) {
                        for (int s2 = -1; s2 <= 1; s2 += 2) {
                            int y = Terrain.GROUND + dy * s2;
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

    public static List<Query> queries(Terrain... terrains) {
        List<Query> out = new ArrayList<>();
        Random r = new Random(1234);
        for (Terrain t : terrains) {
            SyntheticWorld w = world(t);
            for (Length len : Length.values()) {
                for (int n = 0; n < QUERIES; n++) {
                    int x0 = 8 + r.nextInt(SIZE_X - len.dx - 16);
                    int z0 = 8 + r.nextInt(SIZE_Z - 16), z1 = 8 + r.nextInt(SIZE_Z - 16);
                    BetterBlockPos s = spot(w, t, x0, z0);
                    BetterBlockPos g = spot(w, t, x0 + len.dx, z1);
                    out.add(new Query(t, len, n, s, new GoalBlock(g.x, g.y, g.z)));
                }
            }
        }
        return out;
    }

    public static Result run(Query q, Finder finder) {
        SyntheticWorld w = world(q.terrain);
        CalculationContext ctx = context(w);
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
        double cost = p.map(x -> ((baritone.pathing.calc.Path) x).totalCost()).orElse(Double.NaN);
        int nodes = p.map(IPath::getNumNodesConsidered).orElse(-1);
        return new Result(q, ok, cost, nodes, (t1 - t0) / 1e6, a1 - a0);
    }

    static double pct(List<Double> xs, double p) {
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        if (s.isEmpty()) return Double.NaN;
        int i = (int) Math.ceil(p * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, i)));
    }

    /** One full pass over the queries, repeated {@code reps} times after one warm-up pass. Returns all timed results. */
    public static List<Result> bench(List<Query> qs, Finder f, int reps) {
        for (Query q : qs) run(q, f);
        List<Result> all = new ArrayList<>();
        for (int i = 0; i < reps; i++) for (Query q : qs) all.add(run(q, f));
        return all;
    }

    public static String report(String label, List<Result> rs, int reps) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(label).append("\n\n");
        sb.append("| terrain | length | queries | fail | median ms | p95 ms | median nodes | mean cost | mean MB alloc |\n|---|---|---|---|---|---|---|---|---|\n");
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
            double mb = l.stream().mapToLong(Result::allocBytes).average().orElse(0) / 1e6;
            String[] k = e.getKey().split("\\|");
            sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %.1f | %.1f | %.0f | %.1f | %.1f |%n", k[0], k[1], l.size() / reps, f, pct(ms, .5), pct(ms, .95), pct(nodes, .5), cost, mb));
        }
        sb.append(String.format(Locale.ROOT, "%nAll: median %.1f ms, p95 %.1f ms, failures %d of %d queries, %d reps.%n%n", pct(allMs, .5), pct(allMs, .95), fails, rs.size() / reps, reps));
        // Ostinato: travel-first score. Failures rank first; then estimated time-to-arrive per solved query =
        // planned travel ticks (path cost) + planning wall time in ticks (50 ms/tick), since the bot waits on the plan.
        double travel = rs.stream().filter(Result::ok).mapToDouble(Result::cost).average().orElse(Double.NaN);
        double arrive = rs.stream().filter(Result::ok).mapToDouble(r -> r.cost + r.ms / 50.0).average().orElse(Double.NaN);
        sb.append(String.format(Locale.ROOT, "Score (travel-first): failure rate %.1f%%, mean travel %.1f ticks, mean time-to-arrive %.1f ticks (travel + plan).%n%n",
                100.0 * fails / Math.max(1, rs.size() / reps), travel, arrive));
        return sb.toString();
    }

    /** Per-query exact outcome (cost to 1e-6 and node count), the fingerprint the cost-equality test compares. */
    public static String fingerprint(List<Result> rs) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (Result r : rs) {
            if (!seen.add(r.q.id())) continue;
            // a search cut off by the timeout depends on wall time, so only its id is fingerprinted
            if (r.ms >= TIMEOUT_MS * 0.95) sb.append(r.q.id()).append(" timeout\n");
            else sb.append(String.format(Locale.ROOT, "%s %s %.6f %d\n", r.q.id(), r.ok ? "ok" : "fail", r.cost, r.nodes));
        }
        return sb.toString();
    }

    public static void write(String name, String text) {
        try {
            Path dir = Path.of(System.getProperty("user.dir"), "build", "pathbench");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
