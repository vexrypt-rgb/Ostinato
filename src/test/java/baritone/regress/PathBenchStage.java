/*
 * Ostinato: regression suite, pathfinding benchmark stage. New file, replaces no Baritone code.
 */
package baritone.regress;

import baritone.Baritone;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.Terrain;
import org.junit.Assume;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Ostinato: the pfs pathfinding benchmark (PathBench's seeded synthetic terrains) as a regression stage. Runs the short
 * and medium queries the golden-cost test runs (the long ones, and the few over 300k expansions, stay with the full
 * benchmark) for each {@code engine:finder} in {@code -Dostinato.regress.engines} (default
 * {@code BARITONE:astar,KINEMATIC:game}; {@code game} is the far-segment weighted A* PathingBehavior uses with the current
 * pathingFarSegmentWeight). Failures and mean path cost are exact and gate; plan times are reported and only warn.
 */
public class PathBenchStage {

    @Test
    public void pathBench() throws Exception {
        Assume.assumeTrue("set -Dostinato.regress=true (./gradlew regress)", Regress.stage("pathbench"));
        PathBench.boot();
        String golden;
        try (InputStream in = PathBenchStage.class.getResourceAsStream("/pathbench/golden.txt")) {
            golden = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
        }
        String g = golden;
        List<PathBench.Query> qs = PathBench.queries(Terrain.values()).stream()
                .filter(q -> q.length() != PathBench.Length.LONG)
                .filter(q -> g.isEmpty() || g.lines().anyMatch(l -> l.startsWith(q.id() + " ") && !l.endsWith("timeout")
                        && Long.parseLong(l.substring(l.lastIndexOf(' ') + 1)) <= 300_000))
                .toList();
        int reps = Integer.getInteger("ostinato.regress.benchReps", 1);
        List<String> combos = Regress.list("ostinato.regress.engines");
        if (combos.isEmpty()) combos = List.of("BARITONE:astar", "KINEMATIC:game");
        for (String combo : combos) {
            String[] p = combo.split(":", 2);
            PathBench.Engine engine = PathBench.Engine.valueOf(p[0].toUpperCase(Locale.ROOT));
            String fs = p.length > 1 ? p[1] : "astar";
            StageResult r = new StageResult("pathbench:" + engine.name().toLowerCase(Locale.ROOT) + ":" + fs, "pathbench");
            r.commit = Regress.commit();
            var snap = Regress.snapshot();
            long t0 = System.nanoTime();
            try {
                PathBench.apply(engine);
                PathBench.Finder finder = fs.equals("game") ? PathBench.weighted(Baritone.settings().pathingFarSegmentWeight.value) : PathBench.finder(fs);
                List<PathBench.Result> all = Regress.quiet(() -> PathBench.bench(qs, finder, reps));
                Map<String, PathBench.Result> first = new LinkedHashMap<>();
                for (PathBench.Result x : all) first.putIfAbsent(x.q().id(), x);
                int fails = 0, timeouts = 0;
                double cost = 0;
                long nodes = 0;
                for (PathBench.Result x : first.values()) {
                    boolean timeout = x.ms() >= PathBench.TIMEOUT_MS * 0.95;
                    // a search cut off by the timeout depends on machine speed: it is counted apart and kept out of the exact numbers
                    StageResult.Case c = r.add(x.q().id(), timeout ? "timeout" : x.ok() ? "ok" : "fail");
                    if (timeout) {
                        timeouts++;
                    } else if (x.ok()) {
                        c.cost = Math.round(x.cost() * 1e6) / 1e6;
                        c.nodes = (long) x.nodes();
                        cost += x.cost();
                        nodes += x.nodes();
                    } else {
                        fails++;
                    }
                }
                Map<String, List<Double>> ms = new LinkedHashMap<>();
                for (PathBench.Result x : all) ms.computeIfAbsent(x.q().id(), k -> new ArrayList<>()).add(x.ms());
                for (Map.Entry<String, List<Double>> e : ms.entrySet()) r.cases.get(e.getKey()).ms = Math.round(Regress.median(e.getValue()) * 10) / 10.0;
                List<Double> allMs = all.stream().map(PathBench.Result::ms).toList();
                int okN = first.size() - fails - timeouts;
                double arrive = all.stream().filter(PathBench.Result::ok).mapToDouble(x -> x.cost() + x.ms() / 50.0).average().orElse(0);
                r.metric("queries", first.size(), "info", "warn", 0, 0, "");
                r.metric("failures", fails, "lower", "fail", 0, 0, ""); // searches that ended without the goal, before the timeout
                r.metric("timeouts", timeouts, "lower", "warn", 0, 0, "");
                r.metric("mean_cost", okN == 0 ? 0 : Math.round(cost / okN * 1e4) / 1e4, "lower", "fail", 1e-3, 0.005, "ticks");
                r.metric("nodes_total", nodes, "lower", "warn", 0, 0.10, "");
                r.metric("plan_ms_median", Math.round(Regress.median(allMs) * 10) / 10.0, "lower", "warn", 5, 0.5, "ms");
                r.metric("plan_ms_p95", Math.round(Regress.pct(allMs, 0.95) * 10) / 10.0, "lower", "warn", 20, 0.5, "ms");
                r.metric("time_to_arrive", Math.round(arrive * 10) / 10.0, "lower", "warn", 1, 0.02, "ticks");
                r.note = reps + " timed rep(s) after a warm-up; per-query ms is the median";
            } catch (Throwable t) {
                r.status = "error";
                r.note = t.toString();
                t.printStackTrace();
            } finally {
                Regress.restore(snap);
            }
            r.write(Regress.out());
            System.out.printf(Locale.ROOT, "[regress] %s %s, %.1f s%n", r.stage, r.status, (System.nanoTime() - t0) / 1e9);
        }
    }
}
