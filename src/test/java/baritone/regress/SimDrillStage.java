/*
 * Ostinato: regression suite, sim drill stage. New file, replaces no Baritone code.
 */
package baritone.regress;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.RegressPlan;
import baritone.pathing.calc.bench.HeadlessToolSet;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the parkour drills, with no client. Each course in {@code regress/courses.txt} is built headlessly (its
 * {@code fill}/{@code setblock} commands, or the map save's region files) and every goal is planned by the real A*
 * (far-segment weighting as PathingBehavior uses it, the jump movers whose costs run PlayerSim against the course's
 * real collision boxes) with the settings PkTest sets for that mode. Per goal: reached / partial / timeout / no plan,
 * cost, nodes, plan time and the step shapes (gaps, rises, drops) of the plan. It checks the plans the bot would get, not their execution in
 * the game, which is the live-client stage's job; a goal planned here that fails live points at the executor.
 */
public class SimDrillStage {

    record Entry(String name, String file, String world, List<String> modes) {}

    static List<Entry> manifest() throws IOException {
        List<Entry> out = new ArrayList<>();
        try (InputStream in = SimDrillStage.class.getResourceAsStream("/regress/courses.txt")) {
            if (in == null) return out;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] a = line.split("\\s+");
                out.add(new Entry(a[0], a[1], a[2], Arrays.asList(a[3].split(","))));
            }
        }
        return out;
    }

    /** What PkTest.setup sets before a course, minus the client-only parts. */
    static void apply(String mode) {
        Settings s = Baritone.settings();
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.allowWaterBucketFall.value = false;
        s.allowSprint.value = true;
        s.experimentalMovement.value = false;
        s.allowParkour.value = true;
        s.allowParkourAscend.value = true;
        s.allowNeos.value = true;
        s.allowClimbJumps.value = true;
        s.allowTasJumps.value = true;
        s.allowMomentumJumps.value = true;
        s.allowDiagonalAscend.value = true;
        s.allowDiagonalDescend.value = true;
        s.slowKinematic.value = false;
        boolean phys = mode.equals("physics");
        s.physicsTravel.value = phys;
        s.kinematicTravel.value = !phys;
        s.chatDebug.value = false;
    }

    static long budgetMs() {
        return Long.getLong("ostinato.regress.planMs", 4000L);
    }

    @Test
    public void simDrills() throws Exception {
        Assume.assumeTrue("set -Dostinato.regress=true (./gradlew regress)", Regress.stage("simdrill"));
        PathBench.boot();
        List<String> only = Regress.list("ostinato.regress.courses");
        List<String> modes = Regress.list("ostinato.regress.modes");
        Optional<Path> worlds = Regress.worlds();
        int ran = 0;
        for (Entry e : manifest()) {
            if (!only.isEmpty() && !only.contains(e.name())) continue;
            for (String mode : modes.isEmpty() ? e.modes() : modes) {
                StageResult r = new StageResult("simdrill:" + e.name() + ":" + mode, "simdrill");
                r.commit = Regress.commit();
                Nbt.Regions regions = null;
                if (!e.world().equals("-")) {
                    Path save = worlds.map(w -> w.resolve(e.world())).orElse(null);
                    if (save == null || !(regions = new Nbt.Regions(save)).exists()) {
                        r.status = "skipped";
                        r.note = "map save " + e.world() + " not found (pass -Pregress_worlds=<folder of saves>)";
                        r.write(Regress.out());
                        System.out.println("[regress] " + r.stage + " skipped: " + r.note);
                        continue;
                    }
                }
                var snap = Regress.snapshot();
                long t0 = System.nanoTime();
                try {
                    apply(mode);
                    runCourse(e, regions, r);
                } catch (Throwable t) {
                    r.status = "error";
                    r.note = t.toString();
                    t.printStackTrace();
                } finally {
                    Regress.restore(snap);
                }
                r.write(Regress.out());
                ran++;
                var ok = r.metric("goals_ok");
                System.out.printf(Locale.ROOT, "[regress] %s %s: %s/%s goals planned, %.1f s%n", r.stage, r.status,
                        ok == null ? "?" : (long) ok.value, r.metric("goals") == null ? "?" : (long) r.metric("goals").value, (System.nanoTime() - t0) / 1e9);
            }
        }
        assertTrue("no course in regress/courses.txt matched", ran > 0 || !only.isEmpty() || worlds.isEmpty());
    }

    /**
     * Step shapes of a raw path (Movement objects need a live player, so moves are classed by geometry): {@code gapN}
     * a jump over N air blocks, {@code up} a 1 block rise, {@code dropN} a fall of N, {@code climb} a vertical rise.
     */
    static Map<String, Integer> shape(List<BetterBlockPos> pos) {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i + 1 < pos.size(); i++) {
            BetterBlockPos a = pos.get(i), b = pos.get(i + 1);
            int dx = Math.abs(b.x - a.x), dz = Math.abs(b.z - a.z), dy = b.y - a.y, h = Math.max(dx, dz);
            String k = h >= 2 ? "gap" + (h - 1) : dx == 0 && dz == 0 ? (dy > 0 ? "climb" : dy < 0 ? "drop" + (-dy) : null)
                    : dy > 0 ? "up" : dy < -1 ? "drop" + (-dy) : null;
            if (k != null) m.merge(k, 1, Integer::sum);
        }
        return m;
    }

    private static BetterBlockPos land(CourseWorld w, int x, int y, int z) {
        for (int dy = 0; dy <= 8; dy++) if (w.standable(x, y - dy, z)) return new BetterBlockPos(x, y - dy, z);
        for (int dy = 1; dy <= 2; dy++) if (w.standable(x, y + dy, z)) return new BetterBlockPos(x, y + dy, z);
        return new BetterBlockPos(x, y, z);
    }

    static void runCourse(Entry e, Nbt.Regions regions, StageResult r) throws IOException {
        Course c;
        try (InputStream in = SimDrillStage.class.getResourceAsStream("/regress/courses/" + e.file())) {
            if (in == null) throw new IOException("missing course file regress/courses/" + e.file());
            c = Course.read(e.name(), Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
        }
        CourseWorld w = new CourseWorld(regions);
        long budget = budgetMs();
        BetterBlockPos at = null;
        String tag = null;
        int goals = 0, ok = 0, timeouts = 0, jumps = 0, errors = 0;
        long nodes = 0;
        double costOk = 0;
        List<Double> times = new ArrayList<>();
        for (Course.Step st : c.steps) {
            switch (st.kind()) {
                case "cmd" -> c.apply(w, st.text());
                case "set" -> {
                    String[] kv = st.text().split("\\s+", 2);
                    try {
                        SettingsUtil.parseAndApply(Baritone.settings(), kv[0].toLowerCase(Locale.ROOT), kv.length > 1 ? kv[1] : "");
                    } catch (Exception ex) {
                        r.note += "bad set line " + st.line() + "; ";
                    }
                }
                case "tag" -> tag = st.text();
                case "tp" -> at = land(w, st.x(), st.y(), st.z());
                case "goal", "near" -> {
                    goals++;
                    String id = (tag != null ? tag : "goal") + "@" + st.x() + "," + st.y() + "," + st.z();
                    tag = null;
                    Goal goal = st.kind().equals("goal") ? new GoalBlock(st.x(), st.y(), st.z()) : new GoalNear(new BetterBlockPos(st.x(), st.y(), st.z()), st.r());
                    if (at == null) {
                        r.add(id, "nostart").detail = "no tp before the first goal";
                        continue;
                    }
                    StageResult.Case cs;
                    try {
                        CalculationContext ctx = new CalculationContext(w, new HeadlessToolSet(), Baritone.settings().allowPlace.value, CourseWorld.MIN_Y, CourseWorld.HEIGHT);
                        BetterBlockPos start = at;
                        double weight = PathingBehavior.farSegmentWeight(goal, start);
                        long t0 = System.nanoTime();
                        Optional<IPath> p = Regress.quiet(() -> RegressPlan.plan(new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, ctx), ctx, weight), ctx, budget));
                        double ms = (System.nanoTime() - t0) / 1e6;
                        times.add(ms);
                        boolean late = ms >= budget * 0.95;
                        String outcome = p.isEmpty() ? (late ? "timeout" : "noplan")
                                : goal.isInGoal(p.get().getDest()) ? "ok" : late ? "timeout" : "partial";
                        cs = r.add(id, outcome);
                        cs.ms = Math.round(ms * 10) / 10.0;
                        if (p.isPresent()) {
                            cs.nodes = (long) p.get().getNumNodesConsidered();
                            nodes += cs.nodes;
                            double cost = RegressPlan.cost(p.get());
                            cs.cost = Math.round(cost * 1000) / 1000.0;
                            Map<String, Integer> kinds = shape(RegressPlan.positions(p.get()));
                            cs.detail = (RegressPlan.positions(p.get()).size() - 1) + " steps" + (kinds.isEmpty() ? "" : " " + kinds);
                            if (outcome.equals("ok")) {
                                costOk += cost;
                                jumps += kinds.entrySet().stream().filter(k -> k.getKey().startsWith("gap") || k.getKey().equals("up")).mapToInt(Map.Entry::getValue).sum();
                            }
                        }
                        if (outcome.equals("ok")) ok++;
                        if (outcome.equals("timeout")) timeouts++;
                    } catch (Throwable t) {
                        cs = r.add(id, "error");
                        StackTraceElement[] tr = t.getStackTrace();
                        cs.detail = t + (tr.length > 0 ? " at " + tr[0] : "");
                        if (errors++ == 0) t.printStackTrace();
                    }
                    // as in game after a reached or skipped goal (PkTest teleports to a skipped jump's landing)
                    at = land(w, st.x(), st.y(), st.z());
                }
                default -> { }
            }
        }
        r.metric("goals", goals, "info", "warn", 0, 0, "");
        r.metric("goals_ok", ok, "higher", "fail", 0, 0, "");
        r.metric("goals_timeout", timeouts, "lower", "warn", 0, 0, "");
        r.metric("cost_ok_total", Math.round(costOk * 10) / 10.0, "info", "warn", 0, 0, "ticks");
        r.metric("jumps_in_ok_plans", jumps, "info", "warn", 0, 0, "");
        r.metric("nodes_total", nodes, "info", "warn", 0, 0, "");
        r.metric("plan_ms_median", Math.round(Regress.median(times) * 10) / 10.0, "lower", "warn", 20, 0.5, "ms");
        r.metric("plan_ms_p95", Math.round(Regress.pct(times, 0.95) * 10) / 10.0, "lower", "warn", 50, 0.5, "ms");
        r.metric("plan_ms_total", Math.round(times.stream().mapToDouble(Double::doubleValue).sum()), "lower", "warn", 500, 0.5, "ms");
        if (c.ignoredCommands > 0) r.note += c.ignoredCommands + " commands not modelled headlessly; ";
        if (!w.unknownBlocks.isEmpty()) r.note += "unknown blocks read as stone: " + w.unknownBlocks + "; ";
    }
}
