/*
 * Ostinato 2.0 Phase 05B: where simulated-edge planning time goes on the live course, headless.
 *
 * Off unless OSTINATO_SIMEDGE_PROFILE is set (it is a measurement, not a check). Every leg plans from the lap start body
 * (at rest, facing +x) with the live primary/failure timeouts, legacy A* and simulated edges alternating, a few warm-up
 * rounds first. Each sim-edge plan prints its SIMEDGEPROF line; the summary prints per-planner wall time, allocation and
 * expansions. OSTINATO_SIMEDGE_HORIZON sets simEdgeHorizon for the sim-edge plans (default 32).
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.legato.LegatoCourse;
import baritone.pathing.legato.SimEdgeCourse;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import static baritone.pathing.calc.MaestroTest.Y;

public class SimEdgeProfileTest {

    static final long PRIMARY_MS = 500, FAILURE_MS = 2000;
    static final int WARM = 3, ROUNDS = 6;

    static {
        if (System.getenv("OSTINATO_SIMEDGE_PROFILE") != null) System.setProperty("ostinato.simedge.profile", "true");
    }

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void profileCourse() {
        Assume.assumeTrue(System.getenv("OSTINATO_SIMEDGE_PROFILE") != null);
        Assume.assumeTrue("SimEdge loaded before the profile flag was set", SimEdge.PROFILE);
        SyntheticWorld w = SimEdgeCourseTest.course();
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        StringBuilder report = new StringBuilder();
        int horizon = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_SIMEDGE_HORIZON", "32"));
        Baritone.settings().simEdgeHorizon.value = horizon;
        try {
            run(w, mx, tid, report, horizon);
        } finally {
            Baritone.settings().simEdgeHorizon.value = Baritone.settings().simEdgeHorizon.defaultValue;
        }
        PathBench.write(horizon == 32 ? "simedge-profile.txt" : "simedge-profile-h" + horizon + ".txt", report.toString());
    }

    private static void run(SyntheticWorld w, com.sun.management.ThreadMXBean mx, long tid, StringBuilder report, int horizon) {
        for (int round = 0; round < WARM + ROUNDS; round++) {
            for (LegatoCourse.Leg leg : SimEdgeCourse.LEGS) {
                BetterBlockPos s = new BetterBlockPos(leg.sx(), Y, leg.sz());
                GoalBlock g = new GoalBlock(leg.gx(), Y, leg.gz());
                for (int arm = 0; arm < 2; arm++) {
                    AbstractNodeCostSearch f;
                    if (arm == 0) {
                        f = PathBench.ASTAR.create(s, g, PathBench.context(w));
                    } else {
                        SimEdgePathFinder sf = SimEdgeTest.finder(w, s, g);
                        sf.startBody(SimEdgeTest.rest(s.x, s.y, s.z));
                        f = sf;
                    }
                    PrintStream out = System.out;
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
                    long a0 = mx.getThreadAllocatedBytes(tid), t0 = System.nanoTime();
                    Optional<baritone.api.pathing.calc.IPath> p;
                    try {
                        p = f.calculate0(PRIMARY_MS, FAILURE_MS);
                    } finally {
                        System.setOut(out);
                    }
                    long us = (System.nanoTime() - t0) / 1000, kb = (mx.getThreadAllocatedBytes(tid) - a0) / 1024;
                    if (round < WARM) continue;
                    String prof = "";
                    for (String line : buf.toString(StandardCharsets.UTF_8).split("\\R")) {
                        if (line.startsWith("SIMEDGEPROF")) prof = line.substring("SIMEDGEPROF".length());
                    }
                    boolean toGoal = p.isPresent() && g.isInGoal(p.get().getDest());
                    String row = String.format(Locale.ROOT, "PROFROW horizon=%d round=%d leg=%s planner=%s us=%d allocKB=%d map=%d timedOut=%b toGoal=%b len=%d fellBack=%b%s",
                            horizon, round - WARM, leg.name(), arm == 0 ? "astar" : "simedge", us, kb, f.mapSize(), f.timedOut(), toGoal,
                            p.map(x -> x.positions().size()).orElse(0), f instanceof SimEdgePathFinder sf2 && sf2.fellBack(), prof);
                    System.out.println(row);
                    report.append(row).append('\n');
                }
            }
        }
    }
}
