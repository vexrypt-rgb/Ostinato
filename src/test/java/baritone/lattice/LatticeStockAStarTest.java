/*
 * Ostinato: review fix. Stock A* (current navigation's planner) on the Phase 03 lattice worlds. Not upstream Baritone.
 * Planner tier only: PathExecutor is not headless (D2), so stock travel ticks are the planner's cost estimate,
 * NOT_EQUIVALENT to the lattice's simulated ticks. Success means the returned path ends in the goal block.
 * The moves column lists block steps dx/dy/dz; a horizontal step longer than 1 is a parkour jump.
 */
package baritone.lattice;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.level.block.Blocks;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LatticeStockAStarTest {
    /** Lattice cells span x -6..8, y -1..1, z -2..12. This offset puts them inside a SyntheticWorld. */
    static final int OX = 16;
    static final int OY = 10;
    static final int OZ = 16;
    static final int SIZE = 48;

    public record Row(String split, String id, int trial, String config, boolean success, double estTicks,
                      int expansions, double latencyMs, String moves) {
    }

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void stockFindsTheFlatCasesAndTheConversionKeepsTheFloor() {
        LatticeCase flat = LatticeCase.development().get(0);
        assertEquals("flat-4", flat.id());
        SyntheticWorld w = world(flat.world());
        assertTrue(w.real(OX, OY - 1, OZ).getBlock() == Blocks.STONE);
        assertTrue(w.real(OX, OY, OZ).isAir());
        Row row = stock(flat, 0, false);
        assertTrue(row.toString(), row.success());
        assertTrue(row.expansions() > 0);
    }

    /** OSTINATO_BENCH=phase03-review. Trials from OSTINATO_LATTICE_TRIALS (default 20). Writes to build/ unless OSTINATO_WRITE_DOCS=1. */
    @Test
    public void reviewBench() throws Exception {
        Assume.assumeTrue("phase03-review".equals(System.getenv("OSTINATO_BENCH")));
        int trials = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_LATTICE_TRIALS", "20"));
        Path dir = "1".equals(System.getenv("OSTINATO_WRITE_DOCS"))
                ? project("docs/ostinato2/raw/review")
                : Paths.get("build", "ostinato2", "raw", "review");
        LatticeBench.Report report = LatticeBench.run(trials);
        assertTrue(LatticeBench.reproducible(report.trials()));
        LatticeBench.write(report, dir);

        List<LatticeCase> cases = new ArrayList<>(LatticeCase.development());
        cases.addAll(LatticeCase.heldOut());
        List<Row> rows = new ArrayList<>();
        for (LatticeCase item : cases) {
            for (int trial = 0; trial < trials; trial++) {
                rows.add(stock(item, trial, false));
                rows.add(stock(item, trial, true));
            }
        }
        StringBuilder csv = new StringBuilder("split,id,trial,config,success,estTicks,expansions,latencyMs,moves\n");
        for (Row r : rows) {
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%s,%s,%.3f,%d,%.3f,%s%n", r.split, r.id, r.trial, r.config,
                    r.success, r.estTicks, r.expansions, r.latencyMs, r.moves));
        }
        Files.writeString(dir.resolve("phase03-stock.csv"), csv.toString(), StandardCharsets.US_ASCII);
        StringBuilder summary = new StringBuilder();
        summary.append("stock A* on the lattice worlds; planner tier; estTicks is A* cost, NOT_EQUIVALENT to simulated ticks\n");
        for (String split : List.of("dev", "held")) {
            for (String config : List.of("stock-default", "stock-parkour")) {
                int k = 0;
                int n = 0;
                List<Double> latency = new ArrayList<>();
                for (Row r : rows) {
                    if (!r.split.equals(split) || !r.config.equals(config)) {
                        continue;
                    }
                    latency.add(r.latencyMs);
                    if (r.trial == 0) {
                        n++;
                        k += r.success ? 1 : 0;
                    }
                }
                double[] ci = LatticeStats.wilson(k, n);
                summary.append(String.format(Locale.ROOT,
                        "stock %s %s success %d/%d wilson95 [%.3f, %.3f] latencyMs n=%d p50 %.3f p95 %.3f p99 %.3f%n",
                        split, config, k, n, ci[0], ci[1], latency.size(), LatticeStats.percentile(latency, 0.5),
                        LatticeStats.percentile(latency, 0.95), LatticeStats.percentile(latency, 0.99)));
            }
        }
        for (Row r : rows) {
            if (r.trial == 0) {
                summary.append(String.format(Locale.ROOT, "%s %s %s success=%s estTicks=%.1f expansions=%d moves=%s%n",
                        r.split, r.id, r.config, r.success, r.estTicks, r.expansions, r.moves));
            }
        }
        Files.writeString(dir.resolve("phase03-stock-summary.txt"), summary.toString(), StandardCharsets.US_ASCII);
    }

    static Row stock(LatticeCase item, int trial, boolean parkour) {
        boolean before = Baritone.settings().allowParkour.value;
        Baritone.settings().allowParkour.value = parkour;
        PrintStream out = System.out;
        try {
            SyntheticWorld w = world(item.world());
            CalculationContext ctx = PathBench.context(w);
            BetterBlockPos start = new BetterBlockPos((int) Math.floor(item.startX()) + OX, OY, (int) Math.floor(item.startZ()) + OZ);
            GoalBlock goal = new GoalBlock((int) Math.floor(item.goalX()) + OX, (int) Math.round(item.goalY()) + OY,
                    (int) Math.floor(item.goalZ()) + OZ);
            System.setOut(new PrintStream(PrintStream.nullOutputStream()));
            long t0 = System.nanoTime();
            AbstractNodeCostSearch finder = PathBench.ASTAR.create(start, goal, ctx);
            Optional<IPath> path = PathBench.plan(finder);
            double ms = (System.nanoTime() - t0) / 1e6;
            boolean ok = path.isPresent() && goal.isInGoal(path.get().getDest());
            double cost = path.map(baritone.pathing.calc.PathCostProbe::totalCost).orElse(Double.NaN);
            int nodes = path.map(IPath::getNumNodesConsidered).orElse(-1);
            StringJoiner moves = new StringJoiner(";");
            if (ok) { // Movement objects need a live Baritone, so the steps are position deltas dx/dy/dz
                List<BetterBlockPos> pos = path.get().positions();
                for (int i = 1; i < pos.size(); i++) {
                    moves.add((pos.get(i).x - pos.get(i - 1).x) + "/" + (pos.get(i).y - pos.get(i - 1).y) + "/"
                            + (pos.get(i).z - pos.get(i - 1).z));
                }
            }
            return new Row(item.split(), item.id(), trial, parkour ? "stock-parkour" : "stock-default", ok,
                    ok ? cost : -1, nodes, ms, ok ? moves.toString() : "-");
        } finally {
            System.setOut(out);
            Baritone.settings().allowParkour.value = before;
        }
    }

    static SyntheticWorld world(LatticeBlocks blocks) {
        SyntheticWorld w = new SyntheticWorld(SIZE, SIZE);
        List<double[]> boxes = new ArrayList<>();
        blocks.collect(-OX, -OY + 1, -OZ, SIZE - OX - 1, 20, SIZE - OZ - 1, boxes);
        for (double[] box : boxes) {
            w.set((int) box[0] + OX, (int) box[1] + OY, (int) box[2] + OZ, Blocks.STONE.defaultBlockState());
        }
        return w;
    }

    private static Path project(String rel) {
        Path path = Paths.get(rel);
        return Files.exists(path.getParent()) ? path : Paths.get("..").resolve(rel);
    }
}
