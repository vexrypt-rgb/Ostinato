/*
 * Ostinato 2.0 Phase 07: tests and corpus runner for the segmented-trip simulator. New file, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.LongTerrain;
import baritone.pathing.calc.bench.SyntheticWorld;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Ostinato 2.0 Phase 07: the trip simulator follows findPathInNewThread's budgets, walks trips to the goal on both
 * arms, recovers from a wall dropped on its route, replans on a goal change, and shows the hierarchy falling back to A*
 * for goals outside loaded chunks. {@link #corpus} (OSTINATO_LONGROUTE=&lt;label&gt;) runs the long-distance corpus.
 */
public class LongRouteSimTest {

    private static SyntheticWorld small;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
        LongRouteSim.bindItemComponents();
        small = LongTerrain.OPEN.build(480, 64, 7);
    }

    @Before
    public void defaults() {
        Settings s = Baritone.settings();
        for (Settings.Setting<?> x : List.of(s.pathingFarSegmentWeight, s.pathingHierarchical, s.primaryTimeoutMS, s.failureTimeoutMS,
                s.failureTimeoutMaxMS, s.planAheadPrimaryTimeoutMS, s.planAheadFailureTimeoutMS, s.planningTickLookahead,
                s.pathingHierarchyLevels, s.pathingHierarchyCandidates, s.pathingHierarchyBidirectional, s.pathingHierarchyHeuristicFactor,
                s.pathingHierarchyCorridorRadius, s.pathingHierarchyCoarseFactorShift, s.pathingHierarchyFarTerrain, s.simplifyUnloadedYCoord,
                s.maxPathLengthBlocks, s.cutoffAtLoadBoundary, s.costVerificationLookahead, s.pathingStateIdCache)) {
            reset(x);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void reset(Settings.Setting<T> x) {
        x.value = x.defaultValue;
    }

    private static <T> T quiet(java.util.function.Supplier<T> f) {
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream())); // A* prints progress lines
        try {
            return f.get();
        } finally {
            System.setOut(out);
        }
    }

    @Test
    public void budgetsFollowFindPathInNewThread() {
        LongRouteSim sim = new LongRouteSim(small, LongRouteSim.Arm.PROD, 0);
        sim.goal = new GoalBlock(400, 65, 30);
        BetterBlockPos a = new BetterBlockPos(10, 65, 30);
        long[][] want = {{500, 2000, 1}, {2000, 2000, 1}, {4000, 4000, 1}, {8000, 8000, 1}, {16000, 16000, 0}, {16000, 16000, 0}};
        for (long[] w : want) assertArrayEquals(w, sim.budgets(a, false));
        BetterBlockPos b = new BetterBlockPos(50, 65, 30);
        assertArrayEquals(new long[]{4000, 5000, 1}, sim.budgets(b, true));
        assertArrayEquals(new long[]{8000, 8000, 1}, sim.budgets(b, true)); // a repeated plan-ahead start
        assertArrayEquals(new long[]{16000, 16000, 0}, sim.budgets(b, false)); // then a fresh search there
        sim.goal = new GoalBlock(300, 65, 30); // a new goal forgets the starts
        assertArrayEquals(new long[]{500, 2000, 1}, sim.budgets(a, false));
    }

    private static LongRouteSim.Route route(SyntheticWorld w, int x0, int x1) {
        return new LongRouteSim.Route(LongTerrain.OPEN, x1 - x0, 0, LongRouteSim.spot(w, false, x0, 20), LongRouteSim.spot(w, false, x1, 44));
    }

    private static GoalBlock goal(BetterBlockPos g) {
        return new GoalBlock(g.x, g.y, g.z);
    }

    @Test
    public void tripsReachTheGoalOnBothArms() {
        LongRouteSim.Route r = route(small, 20, 440);
        for (LongRouteSim.Arm arm : LongRouteSim.Arm.values()) {
            LongRouteSim sim = new LongRouteSim(small, arm, 0);
            LongRouteSim.Trip t = quiet(() -> sim.run(0, r.start(), goal(r.goal()), LongRouteSim.NO_EVENTS));
            assertTrue(arm + " " + t.reason, t.ok);
            assertTrue(t.travel > 420 * 3);
            assertFalse(t.searches.isEmpty());
            assertEquals(t.travel + t.waitTicks, t.total(), 1e-9);
            if (arm == LongRouteSim.Arm.HPA) assertTrue(t.searches.get(0).outcome(), t.searches.get(0).outcome().startsWith("corridor"));
        }
    }

    @Test
    public void unloadedGoalIsSimplifiedAndTheHierarchyFallsBack() {
        LongRouteSim.Route r = route(small, 20, 440);
        LongRouteSim sim = new LongRouteSim(small, LongRouteSim.Arm.HPA, 96);
        LongRouteSim.Trip t = quiet(() -> sim.run(0, r.start(), goal(r.goal()), LongRouteSim.NO_EVENTS));
        assertTrue(t.reason, t.ok);
        assertTrue("several segments with a 96-block horizon", t.segments >= 3);
        LongRouteSim.Search first = t.searches.get(0);
        assertTrue(first.simplified());
        assertEquals("fallback:goal has no position", first.outcome());
        assertEquals(Integer.MAX_VALUE, small.knownMaxX);
    }

    @Test
    public void wallOnTheRouteStopsTheSegmentAndTheTripRecovers() {
        LongRouteSim.Route r = route(small, 20, 300);
        BlockState[] before = new BlockState[1];
        for (LongRouteSim.Arm arm : LongRouteSim.Arm.values()) {
            LongRouteSim sim = new LongRouteSim(small, arm, 0);
            LongRouteSim.Barrier b = new LongRouteSim.Barrier();
            LongRouteSim.Trip t = quiet(() -> sim.run(0, r.start(), goal(r.goal()), b));
            assertTrue(arm + " " + t.reason, t.ok);
            assertTrue(t.barrierHit);
            assertEquals(1, t.stops);
            assertTrue(t.searches.size() >= 2);
        }
        // the world is put back
        for (int x = 0; x < small.sizeX; x += 7) for (int z = 0; z < small.sizeZ; z += 3) for (int y = 66; y < 72; y++)
            assertNotEquals(net.minecraft.world.level.block.Blocks.BEDROCK, small.real(x, y, z).getBlock());
    }

    @Test
    public void goalChangeReplansFromWhereTheBotStands() {
        LongRouteSim.Route r = route(small, 20, 440);
        BetterBlockPos back = LongRouteSim.spot(small, false, 120, 50);
        LongRouteSim sim = new LongRouteSim(small, LongRouteSim.Arm.PROD, 0);
        LongRouteSim.Trip t = quiet(() -> sim.run(0, r.start(), goal(r.goal()), new LongRouteSim.GoalSwitch(goal(back), 500)));
        assertTrue(t.reason, t.ok);
        assertEquals(1, t.goalSwitches);
        assertFalse(Double.isNaN(t.replanMs));
        assertTrue(goal(back).isInGoal(t.end));
    }

    @Test
    public void stateIdCacheLeavesAnUnbudgetedTripUnchanged() {
        LongRouteSim.Route r = route(small, 20, 440);
        LongRouteSim.Trip a = quiet(() -> new LongRouteSim(small, LongRouteSim.Arm.PROD, 0).run(0, r.start(), goal(r.goal()), LongRouteSim.NO_EVENTS));
        LongRouteSim.Trip b = quiet(() -> new LongRouteSim(small, LongRouteSim.Arm.IDS, 0).run(0, r.start(), goal(r.goal()), LongRouteSim.NO_EVENTS));
        assertTrue(a.reason, a.ok);
        assertTrue(b.reason, b.ok);
        assertFalse(a.searches.stream().anyMatch(LongRouteSim.Search::timedOut));
        assertEquals(a.travel, b.travel, 0);
        assertEquals(a.nodes(), b.nodes());
        assertEquals(a.segments, b.segments);
        assertEquals(a.end, b.end);
        assertTrue(Baritone.settings().pathingStateIdCache.value); // the IDS arm turned it on for its searches
    }

    // ---- corpus ----

    static final int SIZE_X = 2400, SIZE_Z = 128, HORIZON = 160;

    @Test
    public void corpus() throws Exception {
        String label = System.getenv("OSTINATO_LONGROUTE");
        Assume.assumeTrue(label != null && !label.isEmpty());
        String set = System.getenv().getOrDefault("OSTINATO_LONGROUTE_SET", "dev");
        long[] seeds = Arrays.stream(System.getenv().getOrDefault("OSTINATO_LONGROUTE_SEEDS", set.equals("dev") ? "2000" : "3001,3002").split(","))
                .mapToLong(Long::parseLong).toArray();
        int trips = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_LONGROUTE_TRIPS", "2"));
        String only = System.getenv().getOrDefault("OSTINATO_LONGROUTE_FAMILIES", "");
        String modes = System.getenv().getOrDefault("OSTINATO_LONGROUTE_MODES", "known,horizon,change,goal,cancel");
        List<LongRouteSim.Arm> arms = Arrays.stream(System.getenv().getOrDefault("OSTINATO_LONGROUTE_ARMS", "PROD,HPA").split(","))
                .map(LongRouteSim.Arm::valueOf).toList();
        // OAP: named planning-budget arms, "name=primary/failure/aheadPrimary/aheadFailure/failureMax;...". Each runs every
        // arm on the same routes, interleaved; the CSV arm reads ARM@name. Empty: the settings' defaults, arm names as before.
        Map<String, long[]> budgets = new LinkedHashMap<>();
        for (String b : System.getenv().getOrDefault("OSTINATO_LONGROUTE_BUDGETS", "").split(";")) {
            if (b.isBlank()) continue;
            String[] kv = b.split("=");
            budgets.put(kv[0].trim(), Arrays.stream(kv[1].split("/")).mapToLong(Long::parseLong).toArray());
        }
        if (budgets.isEmpty()) budgets.put("", null);
        List<Object[]> runs = new ArrayList<>(); // {arm, budget name, budget}
        for (LongRouteSim.Arm arm : arms) for (Map.Entry<String, long[]> e : budgets.entrySet()) runs.add(new Object[]{arm, e.getKey(), e.getValue()});
        Path dir = Path.of(System.getProperty("user.dir"), "build", "pathbench", "longroute");
        Files.createDirectories(dir);
        Path tripsCsv = dir.resolve(label + "-trips.csv"), searchCsv = dir.resolve(label + "-searches.csv"), progress = dir.resolve(label + "-progress.txt");
        Files.writeString(tripsCsv, "set,seed,family,mode,length,n,arm,ok,reason,travel,wait,total,searches,segments,firstMs,planMs,maxMs,nodes,allocMB,"
                + "escalations,aheadFails,sealed,stops,barrierHit,goalSwitches,replanMs,corridor,partial,fallback,simplified,endDist,straight,cpuMs\n");
        Files.writeString(searchCsv, "set,seed,family,mode,length,n,arm,idx,ahead,primary,failure,ms,nodes,abstract,type,toGoal,timedOut,exhausted,simplified,outcome,cpuMs,allocMB\n");
        Files.writeString(progress, "");
        String hw = String.format(Locale.ROOT, "cpus=%d heapMaxMB=%d java=%s", Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20, System.getProperty("java.version"));
        append(progress, label + " set=" + set + " seeds=" + Arrays.toString(seeds) + " trips=" + trips + " modes=" + modes + " arms=" + arms
                + " budgets=" + System.getenv().getOrDefault("OSTINATO_LONGROUTE_BUDGETS", "") + " " + hw + "\n");

        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream()));
        try {
            // warm-up (JIT): one mixed-terrain trip per arm on a world no measured trip uses, discarded
            SyntheticWorld warm = LongTerrain.MIXED.build(SIZE_X, SIZE_Z, 99);
            LongRouteSim.Route wr = LongRouteSim.routes(warm, LongTerrain.MIXED, 600, 1, new Random(99)).get(0);
            for (Object[] run : runs) {
                applyBudget((long[]) run[2]);
                new LongRouteSim(warm, (LongRouteSim.Arm) run[0], 0).run(-1, wr.start(), goal(wr.goal()), LongRouteSim.NO_EVENTS);
            }
            warm = null;

            for (long seed : seeds) {
                for (LongTerrain fam : LongTerrain.values()) {
                    if (!only.isEmpty() && !Arrays.asList(only.split(",")).contains(fam.name())) continue;
                    SyntheticWorld w = fam.build(SIZE_X, SIZE_Z, seed);
                    Random qr = new Random(seed * 1000 + fam.ordinal());
                    List<Object[]> plan = new ArrayList<>(); // {mode, route}
                    for (LongRouteSim.Route r : LongRouteSim.routes(w, fam, 500, trips, qr)) plan.add(new Object[]{"known", r});
                    for (LongRouteSim.Route r : LongRouteSim.routes(w, fam, 1000, trips, qr)) plan.add(new Object[]{"known", r});
                    for (LongRouteSim.Route r : LongRouteSim.routes(w, fam, 2000, trips, qr)) plan.add(new Object[]{"horizon", r});
                    if (fam == LongTerrain.OPEN || fam == LongTerrain.FOREST || fam == LongTerrain.MIXED)
                        for (LongRouteSim.Route r : LongRouteSim.routes(w, fam, 500, trips, qr)) plan.add(new Object[]{"change", r});
                    if (fam == LongTerrain.OPEN || fam == LongTerrain.MIXED)
                        for (LongRouteSim.Route r : LongRouteSim.routes(w, fam, 1000, trips, qr)) plan.add(new Object[]{"goal", r});
                    if (fam == LongTerrain.MAZE || fam == LongTerrain.BREAK_PLACE)
                        for (LongRouteSim.Route r : LongRouteSim.routes(w, fam, 1000, Math.min(trips, 2), qr)) plan.add(new Object[]{"cancel", r});
                    int k = 0;
                    for (Object[] p : plan) {
                        String mode = (String) p[0];
                        LongRouteSim.Route r = (LongRouteSim.Route) p[1];
                        if (!Arrays.asList(modes.split(",")).contains(mode)) continue;
                        List<Object[]> order = new ArrayList<>(runs);
                        if ((k++ & 1) == 1) Collections.reverse(order); // alternate which arm goes first
                        for (Object[] run : order) {
                            LongRouteSim.Arm arm = (LongRouteSim.Arm) run[0];
                            String bname = (String) run[1];
                            applyBudget((long[]) run[2]);
                            String key = String.join(",", set, Long.toString(seed), fam.name(), mode, Integer.toString(r.length()), Integer.toString(r.n()),
                                    bname.isEmpty() ? arm.name() : arm.name() + "@" + bname);
                            if (mode.equals("cancel")) {
                                LongRouteSim sim = new LongRouteSim(w, arm, 0);
                                for (int rep = 0; rep < 3; rep++) {
                                    double lat = sim.cancelLatency(r.start(), goal(r.goal()), 150);
                                    append(searchCsv, key + "," + rep + ",false,16000,16000," + fmt(lat) + ",-1,-1,CANCEL_LATENCY,false,false,false,false,\n");
                                    append(progress, key + " cancel rep" + rep + " latencyMs=" + fmt(lat) + "\n");
                                }
                                continue;
                            }
                            LongRouteSim sim = new LongRouteSim(w, arm, mode.equals("horizon") ? HORIZON : 0);
                            LongRouteSim.Events ev = switch (mode) {
                                case "change" -> new LongRouteSim.Barrier();
                                case "goal" -> new LongRouteSim.GoalSwitch(goal(LongRouteSim.spot(w, fam.underground(), r.start().x + r.length() / 4, 8 + qr.nextInt(SIZE_Z - 16))),
                                        0.4 * r.length() * ActionCosts.SPRINT_ONE_BLOCK_COST); // after 40% of the straight run's sprint time
                                default -> LongRouteSim.NO_EVENTS;
                            };
                            LongRouteSim.Trip t = sim.run(k, r.start(), goal(r.goal()), ev);
                            StringBuilder sb = new StringBuilder();
                            for (LongRouteSim.Search s : t.searches) {
                                sb.append(key).append(',').append(s.idx()).append(',').append(s.ahead()).append(',').append(s.primary()).append(',').append(s.failure()).append(',')
                                        .append(fmt(s.ms())).append(',').append(s.nodes()).append(',').append(s.abstractNodes()).append(',').append(s.type()).append(',')
                                        .append(s.toGoal()).append(',').append(s.timedOut()).append(',').append(s.exhausted()).append(',').append(s.simplified()).append(',')
                                        .append(s.outcome()).append(',').append(fmt(s.cpuMs())).append(',').append(fmt(s.alloc() / 1e6)).append('\n');
                            }
                            append(searchCsv, sb.toString());
                            double first = t.searches.isEmpty() ? Double.NaN : t.searches.get(0).ms();
                            double max = t.searches.stream().mapToDouble(LongRouteSim.Search::ms).max().orElse(Double.NaN);
                            long corridor = t.searches.stream().filter(s -> s.outcome().startsWith("corridor") && !s.outcome().equals("corridor-partial")).count();
                            long partial = t.searches.stream().filter(s -> s.outcome().equals("corridor-partial")).count();
                            long fallback = t.searches.stream().filter(s -> s.outcome().startsWith("fallback")).count();
                            long simplified = t.searches.stream().filter(LongRouteSim.Search::simplified).count();
                            double endDist = Math.hypot(t.end.x - r.goal().x, t.end.z - r.goal().z);
                            String row = String.join(",", key, Boolean.toString(t.ok), t.reason, fmt(t.travel), fmt(t.waitTicks), fmt(t.total()),
                                    Integer.toString(t.searches.size()), Integer.toString(t.segments), fmt(first), fmt(t.planMs()), fmt(max), Long.toString(t.nodes()),
                                    fmt(t.alloc() / 1e6), Integer.toString(t.escalations), Integer.toString(t.aheadFails), Integer.toString(t.sealed), Integer.toString(t.stops),
                                    Boolean.toString(t.barrierHit), Integer.toString(t.goalSwitches), fmt(t.replanMs), Long.toString(corridor), Long.toString(partial),
                                    Long.toString(fallback), Long.toString(simplified), fmt(endDist), fmt(Math.hypot(r.goal().x - r.start().x, r.goal().z - r.start().z)),
                                    fmt(t.cpuMs()));
                            append(tripsCsv, row + "\n");
                            append(progress, row + "\n");
                        }
                    }
                }
            }
            append(progress, "DONE\n");
        } finally {
            applyBudget(null);
            System.setOut(out);
        }
    }

    /** OAP: {primary, failure, aheadPrimary, aheadFailure, failureMax}, or null for the settings' defaults. */
    private static void applyBudget(long[] b) {
        Settings s = Baritone.settings();
        s.primaryTimeoutMS.value = b == null ? s.primaryTimeoutMS.defaultValue : b[0];
        s.failureTimeoutMS.value = b == null ? s.failureTimeoutMS.defaultValue : b[1];
        s.planAheadPrimaryTimeoutMS.value = b == null ? s.planAheadPrimaryTimeoutMS.defaultValue : b[2];
        s.planAheadFailureTimeoutMS.value = b == null ? s.planAheadFailureTimeoutMS.defaultValue : b[3];
        s.failureTimeoutMaxMS.value = b == null ? s.failureTimeoutMaxMS.defaultValue : b[4];
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static void append(Path p, String s) throws IOException {
        Files.writeString(p, s, StandardOpenOption.APPEND);
    }
}
