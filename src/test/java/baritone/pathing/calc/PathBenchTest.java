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

    /** Ostinato: the full benchmark; only when OSTINATO_BENCH is set (e.g. OSTINATO_BENCH=baseline). Writes build/pathbench/. */
    @Test
    public void benchmark() {
        String label = System.getenv("OSTINATO_BENCH");
        Assume.assumeTrue(label != null && !label.isEmpty());
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
