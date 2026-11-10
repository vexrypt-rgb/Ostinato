/*
 * Ostinato 2.0 Phase 07: headless segmented-trip simulator. New file, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.pathing.RouteReuse;
import baritone.pathing.calc.bench.LongTerrain;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.calc.bench.Terrain;
import baritone.pathing.calc.hierarchy.SectionGraph;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * Ostinato 2.0 Phase 07: drives a whole trip the way {@link PathingBehavior} does, with no client. Every search is the
 * production {@link AbstractNodeCostSearch#calculate} (route reuse, cutoffs, the hold at drops) on a finder built as
 * {@code createPathfinder} builds it (goal simplification, far-segment weight, the hierarchical branch), and with the
 * budgets {@code findPathInNewThread} picks: first-search or plan-ahead base budgets, doubled per repeated start up to
 * {@code failureTimeoutMaxMS}. A segment is walked in its movement costs (planner ticks, not live ticks); the
 * plan-ahead starts once the movements after the current one cost less than {@code planningTickLookahead} and its
 * planning time is hidden behind the walk that is left. Planning the bot has to stand still for is the wait.
 * <p>
 * Not modelled: the executor itself (a segment always walks in its cost unless a world change stops it), the dead set
 * ({@code markDead}: an exhausted plan-ahead ends the trip as "sealed"), mobs, and the soft-cancel timing of a goal change.
 */
public final class LongRouteSim {

    /**
     * PROD: createPathfinder as shipped (A*, far segments weighted). HPA: the same with pathingHierarchical on. IDS: PROD
     * with pathingStateIdCache on (Phase 07's slice).
     */
    public enum Arm { PROD, HPA, IDS }

    private static final IBaritone NONE = (IBaritone) Proxy.newProxyInstance(IBaritone.class.getClassLoader(), new Class<?>[]{IBaritone.class}, (p, m, a) -> null);
    private static final Field BARITONE;

    static {
        try {
            BARITONE = CalculationContext.class.getDeclaredField("baritone");
            BARITONE.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Item components are bound when a world's registries load, which a headless test never does, so an ItemStack
     * cannot be made (MovementFall keeps two in static fields and is built by Path.postProcess). Bind empty maps to the
     * items still unbound. Test JVM only; planning reads no item components.
     */
    public static synchronized void bindItemComponents() {
        for (Item item : BuiltInRegistries.ITEM) {
            Holder.Reference<Item> h = item.builtInRegistryHolder();
            if (!h.areComponentsBound()) {
                h.bindComponents(DataComponentMap.EMPTY);
            }
        }
    }

    /**
     * The headless context, with a stand-in IBaritone so {@link Path#postProcess} can build movements (their
     * constructors ask it for a player context and keep the null it returns; nothing on the planning path reads it).
     */
    public static CalculationContext context(SyntheticWorld w) {
        CalculationContext c = PathBench.context(w);
        try {
            BARITONE.set(c, NONE);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    /** One planner call as findPathInNewThread makes it. */
    public record Search(int trip, int idx, boolean ahead, BetterBlockPos start, long primary, long failure, boolean mayEscalate,
                         double ms, long alloc, int nodes, int abstractNodes, PathCalculationResult.Type type, IPath path,
                         boolean toGoal, boolean timedOut, boolean exhausted, boolean simplified, String outcome, double cpuMs) {
    }

    /** Hooks for the world-change and goal-change families. */
    public interface Events {
        /** Called once per walked segment before it is walked; the movement index where the executor stops, or -1. */
        default int stopIndex(LongRouteSim sim, Trip t, IPath segment) {
            return -1;
        }

        /** Called once per walked segment before it is walked; where along it the goal changes, or null. */
        default Change goalChange(LongRouteSim sim, Trip t, IPath segment) {
            return null;
        }

        /** Undo any world edits. */
        default void restore(LongRouteSim sim) {
        }
    }

    public static final Events NO_EVENTS = new Events() {
    };

    /** A goal change at movement index {@code at} of a segment. */
    public record Change(int at, Goal next) {
    }

    /** The outcome of one trip. */
    public static final class Trip {
        public final List<Search> searches = new ArrayList<>();
        public boolean ok;
        public String reason = "";
        public double travel, waitTicks;
        public int segments, escalations, aheadFails, sealed, stops, goalSwitches;
        public double replanMs = Double.NaN; // the first search after a goal switch
        public boolean barrier, barrierHit;
        public BetterBlockPos end;

        Trip fail(String why) {
            ok = false;
            reason = why;
            return this;
        }

        public double planMs() {
            return searches.stream().mapToDouble(Search::ms).sum();
        }

        public long nodes() {
            return searches.stream().mapToLong(s -> Math.max(0, s.nodes) + Math.max(0, s.abstractNodes)).sum();
        }

        public long alloc() {
            return searches.stream().mapToLong(Search::alloc).sum();
        }

        /** OAP: planner thread CPU time over the trip's searches. */
        public double cpuMs() {
            return searches.stream().mapToDouble(Search::cpuMs).sum();
        }

        public double total() {
            return travel + waitTicks;
        }
    }

    public final SyntheticWorld world;
    public final Arm arm;
    /** Blocks of terrain known ahead of the bot along +x (whole chunks); 0 = the whole world is loaded. */
    public final int horizon;
    /** This arm's SectionGraph.INSTANCE; a fresh one per trip, so every section priced is paid for in the trip. */
    public SectionGraph graph = new SectionGraph();
    public Goal goal;
    private final Map<BetterBlockPos, Long> plannedStarts = new HashMap<>();
    private Goal plannedStartsGoal;
    private int knownChunks = Integer.MIN_VALUE;
    private int tripId;

    // trip guards
    public int maxSearches = 400;
    public double maxPlanMs = 240_000;
    public double maxTravelPerBlock = 100;
    public int maxStallSegments = 25;

    public LongRouteSim(SyntheticWorld world, Arm arm, int horizon) {
        this.world = world;
        this.arm = arm;
        this.horizon = horizon;
    }

    /** As the client's chunk loading: everything up to {@code horizon} blocks past {@code x}, in whole chunks. New chunks invalidate the graph, as the populate hook does. */
    void reveal(int x) {
        if (horizon <= 0) {
            world.knownMaxX = Integer.MAX_VALUE;
            return;
        }
        int want = ((x + horizon) >> 4) + 1;
        if (want <= knownChunks) {
            return;
        }
        if (knownChunks != Integer.MIN_VALUE) {
            for (int cx = knownChunks; cx < want; cx++) {
                for (int cz = 0; cz <= (world.sizeZ - 1) >> 4; cz++) {
                    graph.invalidateChunk(cx, cz, SyntheticWorld.MIN_Y >> 4, (SyntheticWorld.MIN_Y + SyntheticWorld.HEIGHT - 1) >> 4);
                }
            }
        }
        knownChunks = want;
        world.knownMaxX = (want << 4) >= world.sizeX ? Integer.MAX_VALUE : want << 4;
    }

    /** createPathfinder, for this arm, with no player (realStart is the start) and no dead set. */
    AbstractNodeCostSearch create(BetterBlockPos start, CalculationContext ctx, IPath previous) {
        Goal transformed = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos rp) {
            BlockPos pos = rp.getGoalPos();
            if (!ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        Favoring favoring = new Favoring(previous, ctx);
        double weight = PathingBehavior.farSegmentWeight(transformed, start);
        if (arm == Arm.HPA) {
            return new HierarchicalPathFinder(start, start.x, start.y, start.z, transformed, favoring, ctx, graph, weight);
        }
        return new AStarPathFinder(start, start.x, start.y, start.z, transformed, favoring, ctx, weight);
    }

    /**
     * findPathInNewThread's budgets: {primary, failure, mayEscalate (1/0)}. The base pair is the first-search or the
     * plan-ahead one; a start searched before for this goal gets double its last budget, up to failureTimeoutMaxMS.
     */
    long[] budgets(BetterBlockPos start, boolean ahead) {
        Settings s = Baritone.settings();
        long primary = ahead ? s.planAheadPrimaryTimeoutMS.value : s.primaryTimeoutMS.value;
        long failure = ahead ? s.planAheadFailureTimeoutMS.value : s.failureTimeoutMS.value;
        if (!Objects.equals(plannedStartsGoal, goal) || plannedStarts.size() > 512) {
            plannedStartsGoal = goal;
            plannedStarts.clear();
        }
        Long lastHere = plannedStarts.get(start);
        long cap = Math.max(failure, s.failureTimeoutMaxMS.value);
        if (lastHere != null) {
            failure = Math.min(cap, Math.max(failure, Math.min(lastHere, cap) * 2));
            if (primary < failure) {
                primary = failure;
            }
        }
        plannedStarts.put(start, primary);
        return new long[]{primary, failure, failure < cap ? 1 : 0};
    }

    /** findPathInNewThread: budgets, the drop hold, then calculate(). */
    Search search(Trip t, BetterBlockPos start, boolean ahead, IPath previous) {
        long[] b = budgets(start, ahead);
        long primary = b[0], failure = b[1];
        boolean mayEscalate = b[2] == 1;
        CalculationContext ctx = context(world);
        AbstractNodeCostSearch f = create(start, ctx, previous);
        f.holdAtDrops = mayEscalate && goal instanceof IGoalRenderPos rp
                && ctx.bsi.worldContainsLoadedChunk(rp.getGoalPos().getX(), rp.getGoalPos().getZ());
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long a0 = mx.getThreadAllocatedBytes(tid);
        long c0 = mx.getCurrentThreadCpuTime();
        long t0 = System.nanoTime();
        PathCalculationResult r = f.calculate(primary, failure);
        double ms = (System.nanoTime() - t0) / 1e6;
        double cpuMs = (mx.getCurrentThreadCpuTime() - c0) / 1e6;
        long alloc = mx.getThreadAllocatedBytes(tid) - a0;
        IPath p = r.getPath().orElse(null);
        String outcome = f instanceof HierarchicalPathFinder h ? h.outcome : "";
        int abstractNodes = f instanceof HierarchicalPathFinder h ? h.abstractExpanded : 0;
        int nodes = p != null ? p.getNumNodesConsidered() : f.mapSize();
        Search out = new Search(tripId, t.searches.size(), ahead, start, primary, failure, mayEscalate, ms, alloc, nodes, abstractNodes,
                r.getType(), p, p != null && goal.isInGoal(p.getDest()), f.timedOut(), f.exhausted(), !Objects.equals(f.getGoal(), goal), outcome, cpuMs);
        t.searches.add(out);
        return out;
    }

    static double cost(IPath p, int from, int to) {
        List<IMovement> mv = p.movements();
        double c = 0;
        for (int i = Math.max(0, from); i < Math.min(to, mv.size()); i++) {
            c += mv.get(i).getCost();
        }
        return c;
    }

    /** The movement at whose start the plan-ahead fires: the first one after which less than the lookahead is left. */
    static int trigger(IPath p) {
        List<IMovement> mv = p.movements();
        double rest = cost(p, 0, mv.size());
        int look = Baritone.settings().planningTickLookahead.value;
        for (int i = 0; i < mv.size(); i++) {
            rest -= mv.get(i).getCost();
            if (rest < look) {
                return i;
            }
        }
        return Math.max(0, mv.size() - 1);
    }

    /** Where the bot is {@code ticks} into the walk that starts at movement {@code from}. */
    static BetterBlockPos reached(IPath p, int from, double ticks) {
        List<IMovement> mv = p.movements();
        double c = 0;
        for (int i = Math.max(0, from); i < mv.size(); i++) {
            c += mv.get(i).getCost();
            if (c > ticks) {
                return p.positions().get(i);
            }
        }
        return p.getDest();
    }

    /** The executor's look-ahead: the movement index it stands at when it sees movement {@code bad} turn impossible. */
    static int detectedAt(int bad) {
        return Math.max(0, bad - (Baritone.settings().costVerificationLookahead.value - 1));
    }

    /** The first movement of {@code p} that is impossible in the world as it is now, or -1. */
    public int firstImpossible(IPath p) {
        CalculationContext ctx = context(world);
        List<IMovement> mv = p.movements();
        for (int i = 0; i < mv.size(); i++) {
            if (((Movement) mv.get(i)).calculateCost(ctx) >= ActionCosts.COST_INF) {
                return i;
            }
        }
        return -1;
    }

    private static double flat(BetterBlockPos a, Goal g) {
        if (g instanceof IGoalRenderPos rp) {
            BlockPos b = rp.getGoalPos();
            return Math.hypot(a.x - b.getX(), a.z - b.getZ());
        }
        return g.heuristic(a.x, a.y, a.z);
    }

    /** One whole trip from {@code start} to {@code goal0}. */
    public Trip run(int tripId, BetterBlockPos start, Goal goal0, Events ev) {
        this.tripId = tripId;
        Baritone.settings().pathingStateIdCache.value = arm == Arm.IDS; // read by each search's context
        RouteReuse.clear();
        graph = new SectionGraph();
        goal = goal0;
        plannedStarts.clear();
        plannedStartsGoal = null;
        knownChunks = Integer.MIN_VALUE;
        Trip t = new Trip();
        try {
            return walk(t, start, ev);
        } finally {
            ev.restore(this);
            world.knownMaxX = Integer.MAX_VALUE;
        }
    }

    private boolean guard(Trip t, double straight) {
        if (t.searches.size() >= maxSearches) {
            t.fail("search-cap");
            return true;
        }
        if (t.planMs() >= maxPlanMs) {
            t.fail("plan-cap");
            return true;
        }
        if (t.travel >= maxTravelPerBlock * Math.max(64, straight)) {
            t.fail("travel-cap");
            return true;
        }
        return false;
    }

    private Trip walk(Trip t, BetterBlockPos start, Events ev) {
        double straight = flat(start, goal);
        double best = straight;
        int stall = 0;
        boolean replanPending = false;
        BetterBlockPos pos = start;
        outer:
        while (true) {
            t.end = pos;
            if (goal.isInGoal(pos)) {
                t.ok = true;
                return t;
            }
            if (guard(t, straight)) {
                return t;
            }
            // no segment to walk: the bot stands at pos until a search gives it one
            reveal(pos.x);
            Search s = search(t, pos, false, null);
            t.waitTicks += s.ms / 50;
            if (replanPending) {
                t.replanMs = s.ms;
                replanPending = false;
            }
            if (s.path == null) {
                if (s.type == PathCalculationResult.Type.FAILURE && s.mayEscalate && s.timedOut) {
                    t.escalations++;
                    continue;
                }
                return t.fail("calc-failed");
            }
            IPath current = s.path;
            while (true) {
                t.segments++;
                int n = current.movements().size();
                int stopAt = ev.stopIndex(this, t, current);
                Change change = ev.goalChange(this, t, current);
                if (change != null) { // the bot stops where the goal changes and searches afresh (a plan-ahead in flight is dropped)
                    t.travel += cost(current, 0, change.at());
                    t.goalSwitches++;
                    goal = change.next();
                    pos = current.positions().get(change.at());
                    straight = flat(pos, goal);
                    best = straight;
                    stall = 0;
                    replanPending = true;
                    continue outer;
                }
                if (goal.isInGoal(current.getDest()) && stopAt < 0) {
                    t.travel += cost(current, 0, n);
                    t.end = current.getDest();
                    t.ok = true;
                    return t;
                }
                int trig = trigger(current);
                if (stopAt >= 0 && stopAt <= trig) { // the executor stops before the plan-ahead fires
                    t.travel += cost(current, 0, stopAt);
                    t.stops++;
                    pos = current.positions().get(stopAt);
                    continue outer;
                }
                double hidden = cost(current, trig, n); // ticks of walking left when the plan-ahead fires
                reveal(current.positions().get(trig).x);
                BetterBlockPos d = current.getDest();
                if (stopAt >= 0) { // stops after it: the bot waits for that plan-ahead, which no longer starts where it stands
                    Search a = search(t, d, true, current);
                    double walked = cost(current, trig, stopAt);
                    t.waitTicks += Math.max(0, a.ms / 50 - walked);
                    t.travel += cost(current, 0, stopAt);
                    t.stops++;
                    pos = current.positions().get(stopAt);
                    continue outer;
                }
                double spent = 0; // planning ms since the plan-ahead fired
                IPath next = null;
                while (next == null) {
                    if (guard(t, straight)) {
                        return t;
                    }
                    boolean walking = spent / 50 < hidden; // current is still being walked: plan-ahead budgets
                    reveal(walking ? reached(current, trig, spent / 50).x : d.x); // chunks keep loading as the bot walks on
                    Search a = search(t, d, walking, current);
                    spent += a.ms;
                    boolean stillWalking = spent / 50 < hidden; // which branch of the result handler runs
                    if (stillWalking) {
                        if (a.exhausted && a.type != PathCalculationResult.Type.CANCELLATION && !a.toGoal) {
                            t.travel += cost(current, 0, n);
                            t.sealed++;
                            return t.fail("sealed"); // production bans the pocket (markDead) and replans; not modelled
                        }
                        if (a.path != null && a.path.getSrc().equals(d)) {
                            next = a.path;
                        } else {
                            t.aheadFails++; // NEXT_CALC_FAILED: the plan-ahead fires again on the next tick
                            spent = Math.ceil(spent / 50 + 1e-9) * 50;
                        }
                    } else {
                        if (a.path != null) {
                            next = a.path;
                        } else if (a.type == PathCalculationResult.Type.FAILURE && a.mayEscalate && a.timedOut) {
                            t.escalations++;
                        } else {
                            t.travel += cost(current, 0, n);
                            return t.fail("calc-failed");
                        }
                    }
                }
                t.travel += cost(current, 0, n);
                t.waitTicks += Math.max(0, spent / 50 - hidden);
                double dist = flat(d, goal);
                if (dist < best - 2) {
                    best = dist;
                    stall = 0;
                } else if (++stall >= maxStallSegments) {
                    t.end = d;
                    return t.fail("no-progress");
                }
                if (guard(t, straight)) {
                    t.end = d;
                    return t;
                }
                current = next;
            }
        }
    }

    // ---- corpus ----

    /** A spot to stand near column x0, z near zWant: inside a tunnel underground, else on the surface. */
    public static BetterBlockPos spot(SyntheticWorld w, boolean underground, int x0, int zWant) {
        for (int dx = 0; dx < 80; dx++) {
            int x = x0 + ((dx & 1) == 0 ? dx / 2 : -(dx + 1) / 2);
            if (x < 2 || x >= w.sizeX - 2) continue;
            for (int d = 0; d < w.sizeZ; d++) {
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    int z = zWant + d * sgn;
                    if (z < 2 || z >= w.sizeZ - 2) continue;
                    if (underground) {
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
        }
        throw new IllegalStateException("no spot near column " + x0);
    }

    private static boolean standable(SyntheticWorld w, int x, int y, int z) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState below = w.get0(x, y - 1, z);
        return w.get0(x, y, z) == air && w.get0(x, y + 1, z) == air && below != air && below.getFluidState().isEmpty()
                && below.getBlock() != Blocks.OAK_LEAVES && below.getBlock() != Blocks.OAK_LOG;
    }

    /** A trip: start and goal {@code length} blocks apart along +x, at random z, drawn from {@code r}. */
    public record Route(LongTerrain family, int length, int n, BetterBlockPos start, BetterBlockPos goal) {
    }

    public static List<Route> routes(SyntheticWorld w, LongTerrain family, int length, int count, Random r) {
        List<Route> out = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            int x0 = 16 + r.nextInt(Math.max(1, w.sizeX - length - 32));
            int z0 = 8 + r.nextInt(w.sizeZ - 16), z1 = 8 + r.nextInt(w.sizeZ - 16);
            out.add(new Route(family, length, n, spot(w, family.underground(), x0, z0), spot(w, family.underground(), x0 + length, z1)));
        }
        return out;
    }

    /** World change: a bedrock wall dropped across the first segment ahead of the bot, right after it was planned. */
    public static final class Barrier implements Events {
        private final List<long[]> undo = new ArrayList<>();
        private final List<BlockState> old = new ArrayList<>();
        private boolean placed;

        @Override
        public int stopIndex(LongRouteSim sim, Trip t, IPath segment) {
            if (placed) {
                return -1;
            }
            placed = true;
            List<BetterBlockPos> pos = segment.positions();
            if (pos.size() < 16) {
                return -1;
            }
            BetterBlockPos at = pos.get(Math.max(10, pos.size() * 6 / 10));
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = 0; dy <= 5; dy++) {
                    int x = at.x, y = at.y + dy, z = at.z + dz;
                    if (!sim.world.inside(x, y, z)) continue;
                    undo.add(new long[]{x, y, z});
                    old.add(sim.world.real(x, y, z));
                    sim.world.set(x, y, z, Blocks.BEDROCK.defaultBlockState());
                    sim.graph.invalidateBlock(x, y, z); // the block-change hook
                }
            }
            t.barrier = true;
            int bad = sim.firstImpossible(segment);
            if (bad < 0) {
                return -1;
            }
            t.barrierHit = true;
            return detectedAt(bad);
        }

        @Override
        public void restore(LongRouteSim sim) {
            for (int i = undo.size() - 1; i >= 0; i--) {
                long[] p = undo.get(i);
                sim.world.set((int) p[0], (int) p[1], (int) p[2], old.get(i));
            }
            undo.clear();
            old.clear();
            placed = false;
        }
    }

    /**
     * Goal change: once the bot has walked {@code atTicks} (planner ticks, counted across segments), the goal moves to
     * {@code next}. A fixed walking time, not a point on the arm's own segment, so both arms switch after the same
     * amount of travel.
     */
    public record GoalSwitch(Goal next, double atTicks) implements Events {
        @Override
        public Change goalChange(LongRouteSim sim, Trip t, IPath segment) {
            if (t.goalSwitches > 0) {
                return null;
            }
            List<IMovement> mv = segment.movements();
            double c = t.travel;
            for (int i = 0; i < mv.size(); i++) {
                c += mv.get(i).getCost();
                if (c >= atTicks) {
                    return new Change(i + 1, next);
                }
            }
            return null; // later in the trip, if it lasts that long
        }
    }

    /**
     * Cancel latency: a search started on its own thread with a long budget, cancelled after {@code delayMs}; the time
     * from cancel() to calculate() returning.
     */
    public double cancelLatency(BetterBlockPos start, Goal goal0, long delayMs) throws InterruptedException {
        goal = goal0;
        Baritone.settings().pathingStateIdCache.value = arm == Arm.IDS;
        graph = new SectionGraph();
        RouteReuse.clear();
        AbstractNodeCostSearch f = create(start, context(world), null);
        long[] done = new long[1];
        Thread th = new Thread(() -> {
            f.calculate(16_000, 16_000);
            done[0] = System.nanoTime();
        });
        th.start();
        Thread.sleep(delayMs);
        long c = System.nanoTime();
        f.cancel();
        th.join(30_000);
        return (done[0] - c) / 1e6;
    }
}
