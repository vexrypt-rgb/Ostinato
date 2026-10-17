/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Shared file, identical in every fork.
 */
package baritone.pathing.calc;

import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/** Three-way bench entry point. Runs only with XBENCH set (XBENCH_REPS, default 3); writes build/xbench/. */
public class XBenchTest {

    @Test
    public void threeWay() {
        Assume.assumeTrue("set XBENCH to run", System.getenv("XBENCH") != null);
        XAdapter.boot();
        int reps = Integer.parseInt(System.getenv().getOrDefault("XBENCH_REPS", "3"));
        List<XBench.Query> qs = XBench.queries(XTerrain.values());
        String fork = XAdapter.fork();
        XBench.write(fork + "-queries.txt", XBench.describe(qs));
        for (Map.Entry<String, XBench.Finder> e : XAdapter.finders().entrySet()) {
            List<XBench.Result> rs = XBench.bench(qs, e.getValue(), reps);
            String md = XBench.report(e.getKey(), rs, reps);
            System.err.println(md);
            XBench.write(e.getKey() + ".md", md);
            XBench.write(e.getKey() + ".csv", XBench.csv(fork, e.getKey(), rs));
        }
    }
}
