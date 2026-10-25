/*
 * Ostinato: pathfinding benchmark entry point and cost-equality guard. New file, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.pathing.calc.bench.Terrain;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class PathBenchTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    /**
     * Ostinato: stock A* must reproduce the recorded per-query path cost and expansion count exactly (any optimisation of
     * movement-cost evaluation has to be behaviour-identical). Runs the short and medium queries only, to stay quick.
     */
    @Test
    public void stockAStarMatchesGoldenCosts() throws Exception {
        String golden;
        try (InputStream in = PathBenchTest.class.getResourceAsStream("/pathbench/golden.txt")) {
            Assume.assumeNotNull(in);
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
        }
        String g = golden;
        // queries the baseline only finished by timeout are skipped: their outcome depends on wall time
        List<PathBench.Query> qs = PathBench.queries(Terrain.values()).stream()
                .filter(q -> q.length() != PathBench.Length.LONG)
                // and the huge ones (over 300k expansions, several seconds each) are left to the benchmark
                .filter(q -> g.lines().anyMatch(l -> l.startsWith(q.id() + " ") && !l.endsWith("timeout")
                        && Long.parseLong(l.substring(l.lastIndexOf(' ') + 1)) <= 300_000))
                .toList();
        List<PathBench.Result> rs = qs.stream().map(q -> PathBench.run(q, PathBench.ASTAR)).toList();
        String got = PathBench.fingerprint(rs);
        for (String line : got.split("\n")) {
            String id = line.split(" ")[0];
            String want = golden.lines().filter(l -> l.startsWith(id + " ")).findFirst().orElse(null);
            assertEquals("query " + id, want, line);
        }
    }

    /**
     * Same queries for Baritone, the physics flag, the Soprano-style jump set, and kinematic.
     */
    @Test
    public void engines() throws Exception {
        Assume.assumeTrue("engines".equals(System.getenv("OSTINATO_BENCH")));
        int reps = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_BENCH_REPS", "2"));
        List<PathBench.Query> qs = PathBench.queries(Terrain.values());
        String only = System.getenv("OSTINATO_BENCH_ONLY");
        if (only != null && !only.isEmpty()) {
            qs = qs.stream().filter(q -> only.contains(q.length().name())).toList();
        }
        var s = baritone.Baritone.settings();
        boolean experimental = s.experimentalMovement.value;
        boolean parkour = s.allowParkour.value;
        boolean parkourAscend = s.allowParkourAscend.value;
        boolean parkourPlace = s.allowParkourPlace.value;
        boolean neos = s.allowNeos.value;
        boolean momentum = s.allowMomentumJumps.value;
        boolean climb = s.allowClimbJumps.value;
        boolean diagA = s.allowDiagonalAscend.value;
        boolean diagD = s.allowDiagonalDescend.value;
        boolean kinematic = s.kinematicTravel.value;
        boolean physics = s.physicsTravel.value;
        boolean allowBreak = s.allowBreak.value;
        StringBuilder all = new StringBuilder();
        String onlyEngine = System.getenv("OSTINATO_BENCH_ENGINE");
        java.nio.file.Path progress = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "pathbench", "engines-progress.txt");
        try {
            java.nio.file.Files.createDirectories(progress.getParent());
            if (java.nio.file.Files.exists(progress)) {
                java.nio.file.Files.delete(progress);
            }
            for (PathBench.Engine engine : PathBench.Engine.values()) {
                if (onlyEngine != null && !onlyEngine.isEmpty()) {
                    boolean match = false;
                    for (String part : onlyEngine.split(",")) {
                        if (engine.name().equalsIgnoreCase(part.trim())) {
                            match = true;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                }
                PathBench.apply(engine);
                java.nio.file.Files.writeString(progress, "BEGIN " + engine + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                for (PathBench.Query q : qs) {
                    PathBench.run(q, PathBench.ASTAR);
                }
                List<PathBench.Result> rs = new java.util.ArrayList<>();
                for (int i = 0; i < reps; i++) {
                    for (PathBench.Query q : qs) {
                        PathBench.Result r = PathBench.run(q, PathBench.ASTAR);
                        rs.add(r);
                        java.nio.file.Files.writeString(progress, engine + " " + r.q().id() + " " + (r.ok() ? "ok" : "fail") + " " + String.format(java.util.Locale.ROOT, "%.0f", r.ms()) + "ms " + r.nodes() + "\n", java.nio.file.StandardOpenOption.APPEND);
                    }
                }
                String rep = PathBench.report(engine.name().toLowerCase(java.util.Locale.ROOT), rs, reps);
                all.append(rep).append('\n');
                PathBench.write(engine.name().toLowerCase(java.util.Locale.ROOT) + ".md", rep);
                java.nio.file.Files.writeString(progress, "END " + engine + "\n", java.nio.file.StandardOpenOption.APPEND);
            }
        } finally {
            s.experimentalMovement.value = experimental;
            s.allowParkour.value = parkour;
            s.allowParkourAscend.value = parkourAscend;
            s.allowParkourPlace.value = parkourPlace;
            s.allowNeos.value = neos;
            s.allowMomentumJumps.value = momentum;
            s.allowClimbJumps.value = climb;
            s.allowDiagonalAscend.value = diagA;
            s.allowDiagonalDescend.value = diagD;
            s.kinematicTravel.value = kinematic;
            s.physicsTravel.value = physics;
            s.allowBreak.value = allowBreak;
        }
        all.append("Physics sets physicsTravel only. The search is the Baritone one.\n");
        all.append("Soprano is the hand-cost jump set in this tree (parkour, neo, momentum, climb), not the Soprano repo.\n");
        all.append("Kinematic is that set plus the 4-block gap.\n");
        PathBench.write("engines.md", all.toString());
        System.err.println(all);
    }

    /** Ostinato: the full benchmark; only when OSTINATO_BENCH is set (e.g. OSTINATO_BENCH=baseline). Writes build/pathbench/. */
    /**
     * Ostinato: trains the path decision matrix. OSTINATO_MATRIX_TRAIN=1: every action on query numbers 0-1 (train),
     * then on 2-3 (held out) compares the matrix's pick against the default (weighted A* on far segments, as shipped).
     * Writes build/pathbench/path-matrix.tsv and path-matrix-eval.md.
     */
    @Test
    public void trainDecisionMatrix() throws Exception {
        Assume.assumeTrue(System.getenv("OSTINATO_MATRIX_TRAIN") != null);
        PathBench.boot();
        PathBench.apply(PathBench.Engine.BARITONE);
        baritone.pathing.decide.PathDecisionMatrix m = new baritone.pathing.decide.PathDecisionMatrix();
        m.minSamples = 2;
        List<PathBench.Query> qs = PathBench.queries(Terrain.values());
        java.util.Map<PathBench.Query, baritone.pathing.decide.PathDecisionMatrix.Category> cat = new java.util.HashMap<>();
        for (PathBench.Query q : qs) {
            var w = PathBench.world(q.terrain());
            var ctx = PathBench.context(w);
            var g = ((baritone.api.pathing.goals.GoalBlock) q.goal());
            double blocks = q.goal().heuristic(q.start().x, q.start().y, q.start().z) / baritone.Baritone.settings().costHeuristic.value;
            cat.put(q, baritone.pathing.decide.PathDecisionMatrix.categorize(ctx, q.start().x, q.start().y, q.start().z, g.x, g.z, blocks));
        }
        for (PathBench.Query q : qs) PathBench.run(q, PathBench.ASTAR); // warm-up
        java.util.function.Function<baritone.pathing.decide.PathDecisionMatrix.Action, PathBench.Finder> fin = a -> (s, g, c) -> a.hierarchical
                ? new HierarchicalPathFinder(s, s.x, s.y, s.z, g, new baritone.utils.pathing.Favoring(null, c), c, new baritone.pathing.calc.hierarchy.SectionGraph(), a.weight)
                : new AStarPathFinder(s, s.x, s.y, s.z, g, new baritone.utils.pathing.Favoring(null, c), c, a.weight);
        for (PathBench.Query q : qs) {
            if (q.n() >= 2) continue;
            for (var a : baritone.pathing.decide.PathDecisionMatrix.Action.values()) {
                PathBench.Result r = PathBench.run(q, fin.apply(a));
                m.record(cat.get(q), a, baritone.pathing.decide.PathDecisionMatrix.score(r.ok(), r.cost(), r.ms()));
            }
        }
        StringBuilder rep = new StringBuilder("| query | category | pick | pick score | default score |\n|---|---|---|---|---|\n");
        double sp = 0, sd = 0;
        int held = 0, decided = 0, pf = 0, df = 0;
        for (PathBench.Query q : qs) {
            if (q.n() < 2) continue;
            held++;
            PathBench.Result d = PathBench.run(q, PathBench.weighted(baritone.Baritone.settings().pathingFarSegmentWeight.value));
            var pick = m.choose(cat.get(q));
            PathBench.Result p = pick == null ? d : PathBench.run(q, fin.apply(pick));
            if (pick != null) decided++;
            double ps = baritone.pathing.decide.PathDecisionMatrix.score(p.ok(), p.cost(), p.ms());
            double ds = baritone.pathing.decide.PathDecisionMatrix.score(d.ok(), d.cost(), d.ms());
            sp += ps;
            sd += ds;
            if (!p.ok()) pf++;
            if (!d.ok()) df++;
            rep.append(String.format(java.util.Locale.ROOT, "| %s/%s/%d | %s | %s | %.1f | %.1f |%n", q.terrain(), q.length(), q.n(), cat.get(q).key(), pick, ps, ds));
        }
        rep.append(String.format(java.util.Locale.ROOT, "%nHeld out %d queries, matrix decided %d. Mean score (travel+plan ticks): matrix %.1f vs default %.1f. Failures: matrix %d, default %d.%n",
                held, decided, sp / held, sd / held, pf, df));
        System.out.println(rep);
        PathBench.write("path-matrix-eval.md", rep.toString());
        PathBench.write("path-matrix.tsv", m.export());
        // round trip: the exported file loads back to the same picks
        var back = baritone.pathing.decide.PathDecisionMatrix.parse(m.export());
        back.minSamples = 2;
        for (PathBench.Query q : qs) assertEquals(m.choose(cat.get(q)), back.choose(cat.get(q)));
    }

    @Test
    public void benchmark() {
        String label = System.getenv("OSTINATO_BENCH");
        Assume.assumeTrue(label != null && !label.isEmpty() && !"engines".equals(label));
        int reps = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_BENCH_REPS", "5"));
        List<PathBench.Query> qs = PathBench.queries(Terrain.values());
        String only = System.getenv("OSTINATO_BENCH_ONLY"); // e.g. SHORT,MEDIUM
        if (only != null && !only.isEmpty()) qs = qs.stream().filter(q -> only.contains(q.length().name())).toList();
        jdk.jfr.Recording rec = null;
        if (System.getenv("OSTINATO_BENCH_PROFILE") != null) { // JFR CPU + allocation samples, dumped next to the results
            rec = new jdk.jfr.Recording();
            rec.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(5));
            rec.enable("jdk.ObjectAllocationSample");
            rec.start();
        }
        String spec = System.getenv("OSTINATO_BENCH_FINDER");
        String tie = System.getenv("OSTINATO_BENCH_TIE"); // Ostinato: Phase 2 tie-break A/B
        if (tie != null) baritone.Baritone.settings().pathTieBreak.value = Double.parseDouble(tie);
        List<PathBench.Result> rs = PathBench.bench(qs, PathBench.finder(spec), reps);
        if (rec != null) {
            rec.stop();
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "pathbench");
                java.nio.file.Files.createDirectories(dir);
                rec.dump(dir.resolve(label + ".jfr"));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        String rep = PathBench.report(label + " (" + (spec == null ? "astar" : spec) + ")", rs, reps);
        System.out.println(rep);
        PathBench.write(label + ".md", rep);
        PathBench.write(label + "-fingerprint.txt", PathBench.fingerprint(rs));
        StringBuilder raw = new StringBuilder("query,ok,cost,nodes,ms,allocBytes\n");
        for (PathBench.Result r : rs) raw.append(r.q().id()).append(',').append(r.ok()).append(',').append(r.cost()).append(',').append(r.nodes()).append(',').append(r.ms()).append(',').append(r.allocBytes()).append('\n');
        PathBench.write(label + "-raw.csv", raw.toString());
    }
}
