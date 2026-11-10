/*
 * Ostinato: lattice versus PhysicsPathfinder on the same PlayerSim worlds. Not part of upstream Baritone.
 * Does not read Settings.latticeExperiment. Production pathing does not call this class.
 */
package baritone.lattice;

import baritone.contract.PlayerSimPhysics;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.physics.PhysicsPathfinder;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Three trials per case. Discrete columns are success, travel ticks, expansions, and divergence. */
public final class LatticeBench {
    public static final int TRIALS = 3;
    private static final double PROMISING = 0.7;
    private static final double DIVERGENCE_LIMIT = 0.25;
    private static final double TICK_RATIO = 1.15;
    private static final double EXPANSION_RATIO = 3.0;

    public record Trial(String split, String id, int trial, String method, boolean success, int travelTicks,
                        int searchTicks, int expansions, double latencyMs, long allocBytes, double divergence) {
    }

    public record Report(String decision, String reason, boolean heldOutRan, List<Trial> trials, String summary) {
    }

    private LatticeBench() {
    }

    public static Report run() {
        return run(TRIALS);
    }

    /** Review fix: more trials only re-sample latency and allocation; the discrete columns must repeat. */
    public static Report run(int trialCount) {
        List<Trial> trials = new ArrayList<>();
        measure(LatticeCase.development(), trials, trialCount);
        boolean promising = promising(trials);
        if (promising) {
            measure(LatticeCase.heldOut(), trials, trialCount);
        }
        String[] judged = decide(trials);
        String summary = summary(judged[0], judged[1], promising, trials, trialCount);
        return new Report(judged[0], judged[1], promising, List.copyOf(trials), summary);
    }

    /** Writes the raw trial rows and the summary. Latency is not a golden value. */
    public static void write(Report report, Path directory) throws IOException {
        Files.createDirectories(directory);
        StringBuilder csv = new StringBuilder();
        csv.append("split,id,trial,method,success,travelTicks,searchTicks,expansions,latencyMs,allocBytes,divergence\n");
        for (Trial trial : report.trials) {
            csv.append(trial.split).append(',').append(trial.id).append(',').append(trial.trial).append(',')
                    .append(trial.method).append(',').append(trial.success).append(',').append(trial.travelTicks)
                    .append(',').append(trial.searchTicks).append(',').append(trial.expansions).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", trial.latencyMs)).append(',')
                    .append(trial.allocBytes).append(',').append(divText(trial.divergence)).append('\n');
        }
        Files.writeString(directory.resolve("phase03-trials.csv"), csv.toString(), StandardCharsets.US_ASCII);
        Files.writeString(directory.resolve("phase03-summary.txt"), report.summary, StandardCharsets.US_ASCII);
    }

    static String[] decide(List<Trial> trials) {
        double maxDiv = maxDivergence(trials);
        if (maxDiv > DIVERGENCE_LIMIT) {
            return new String[]{"REJECT", String.format(Locale.ROOT,
                    "max prediction divergence %.6f is above 0.25", maxDiv)};
        }
        if (!reproducible(trials)) {
            return new String[]{"REVISE", "trials disagree on success, travel ticks, expansions, or divergence"};
        }
        int devN = ids(trials, "dev").size();
        int beam = successes(trials, "dev", "beam");
        int weighted = successes(trials, "dev", "weighted");
        int physics = successes(trials, "dev", "physics");
        String method = choose(trials, beam, weighted);
        int lattice = Math.max(beam, weighted);
        int unique = uniqueSolves(trials, "dev", method);
        if (lattice * 2 < devN && physics - lattice >= 3) {
            return new String[]{"REJECT", String.format(Locale.ROOT,
                    "%s solves %d/%d and physics solves %d/%d", method, lattice, devN, physics, devN)};
        }
        if (physics > lattice && unique == 0) {
            return new String[]{"PREFER SIMULATED A*", String.format(Locale.ROOT,
                    "physics solves %d/%d, %s solves %d/%d, and the lattice uniquely solves 0 dev cases",
                    physics, devN, method, lattice, devN)};
        }
        boolean heldDrop = heldOutDrop(trials, method, successes(trials, "dev", method), devN);
        double[] shared = sharedMedians(trials, method);
        boolean ticksOk = shared != null && shared[0] <= TICK_RATIO * shared[1];
        double[] expansions = sharedExpansionMedians(trials, method);
        boolean expansionsOk = expansions != null && expansions[0] <= EXPANSION_RATIO * expansions[1];
        if (ticksOk && expansionsOk && !heldDrop && successes(trials, "dev", method) >= physics) {
            return new String[]{"CONTINUE", "lattice matches physics on shared dev solves inside the tick and expansion bounds"};
        }
        StringBuilder reason = new StringBuilder();
        reason.append(String.format(Locale.ROOT,
                "beam %d/%d, weighted %d/%d, physics %d/%d, chosen %s, dev unique %d",
                beam, devN, weighted, devN, physics, devN, method, unique));
        if (shared == null) {
            reason.append("; no shared dev solves");
        } else {
            reason.append(String.format(Locale.ROOT,
                    "; shared median ticks %s %.1f vs physics %.1f (ratio %.3f, bound %.2f)",
                    method, shared[0], shared[1], shared[0] / shared[1], TICK_RATIO));
        }
        if (expansions == null) {
            reason.append("; baseline expansions are UNKNOWN, so the 3x expansion clause cannot pass");
        } else if (!expansionsOk) {
            reason.append(String.format(Locale.ROOT, "; shared median expansions %s %.1f vs physics %.1f exceed %.0fx",
                    method, expansions[0], expansions[1], EXPANSION_RATIO));
        }
        if (!ticksOk) {
            reason.append("; tick bound missed");
        }
        if (heldDrop) {
            reason.append("; held-out success dropped more than 0.25 below dev");
        }
        int heldN = ids(trials, "held").size();
        if (heldN > 0) {
            reason.append(String.format(Locale.ROOT, "; held-out %s %d/%d, physics %d/%d",
                    method, successes(trials, "held", method), heldN, successes(trials, "held", "physics"), heldN));
        }
        return new String[]{"REVISE", reason.toString()};
    }

    /** Equal success uses the lower median travel ticks. */
    private static String choose(List<Trial> trials, int beam, int weighted) {
        if (beam != weighted) {
            return beam > weighted ? "beam" : "weighted";
        }
        double beamTicks = medianTravel(trials, "dev", "beam");
        double weightedTicks = medianTravel(trials, "dev", "weighted");
        if (Double.isNaN(beamTicks) && Double.isNaN(weightedTicks)) {
            return "beam";
        }
        if (Double.isNaN(beamTicks)) {
            return "weighted";
        }
        if (Double.isNaN(weightedTicks)) {
            return "beam";
        }
        return beamTicks <= weightedTicks ? "beam" : "weighted";
    }

    private static double[] sharedMedians(List<Trial> trials, String method) {
        List<Integer> latticeTicks = new ArrayList<>();
        List<Integer> physicsTicks = new ArrayList<>();
        for (String id : ids(trials, "dev")) {
            Trial lattice = trial0(trials, "dev", id, method);
            Trial physics = trial0(trials, "dev", id, "physics");
            if (lattice != null && physics != null && lattice.success && physics.success) {
                latticeTicks.add(lattice.travelTicks);
                physicsTicks.add(physics.travelTicks);
            }
        }
        if (latticeTicks.isEmpty()) {
            return null;
        }
        return new double[]{medianInt(latticeTicks), medianInt(physicsTicks)};
    }

    private static double medianTravel(List<Trial> trials, String split, String method) {
        List<Integer> ticks = new ArrayList<>();
        for (String id : ids(trials, split)) {
            Trial trial = trial0(trials, split, id, method);
            if (trial != null && trial.success) {
                ticks.add(trial.travelTicks);
            }
        }
        if (ticks.isEmpty()) {
            return Double.NaN;
        }
        return medianInt(ticks);
    }

    private static void measure(List<LatticeCase> cases, List<Trial> out, int trialCount) {
        for (LatticeCase item : cases) {
            for (int trial = 0; trial < trialCount; trial++) {
                out.add(lattice(item, trial, "beam", LatticeSearch.Method.BEAM));
                out.add(lattice(item, trial, "weighted", LatticeSearch.Method.WEIGHTED));
                out.add(physics(item, trial));
            }
        }
    }

    private static Trial lattice(LatticeCase item, int trial, String method, LatticeSearch.Method kind) {
        PlayerSimPhysics start = settled(item);
        long before = allocated();
        long t0 = System.nanoTime();
        LatticeSearch.Hit hit = LatticeSearch.search(start, item.goalX(), item.goalY(), item.goalZ(), kind);
        long nanos = System.nanoTime() - t0;
        long bytes = deltaBytes(before);
        int replay = LatticeSearch.replayTicks(start, hit.primitives, item.goalX(), item.goalY(), item.goalZ());
        double divergence = hit.primitives.isEmpty()
                ? Double.NaN
                : LatticeSearch.divergence(start, hit.primitives);
        boolean success = replay >= 0;
        return new Trial(item.split(), item.id(), trial, method, success, success ? replay : -1,
                hit.reached ? hit.travelTicks : -1, hit.expansions, nanos / 1_000_000d, bytes, divergence);
    }

    private static Trial physics(LatticeCase item, int trial) {
        PlayerSimPhysics start = settled(item);
        PlayerSim body = new PlayerSim(item.world()).copyFrom(start.sim());
        long before = allocated();
        long t0 = System.nanoTime();
        PhysicsPathfinder finder = new PhysicsPathfinder(item.world(), LatticeSearch.NODE_BUDGET);
        List<PhysicsPathfinder.Action> plan = finder.plan(body, item.goalX(), item.goalY(), item.goalZ(), LatticeSearch.RADIUS);
        long nanos = System.nanoTime() - t0;
        long bytes = deltaBytes(before);
        int ticks = -1;
        if (plan != null) {
            PlayerSim replay = new PlayerSim(item.world()).copyFrom(start.sim());
            int n = 0;
            for (PhysicsPathfinder.Action action : plan) {
                replay.tick(action.yaw, action.forward, action.sprint, action.jump);
                n++;
                if (LatticeSearch.reached(replay.x, replay.y, replay.z, replay.onGround,
                        item.goalX(), item.goalY(), item.goalZ())) {
                    ticks = n;
                    break;
                }
            }
        }
        boolean success = ticks >= 0;
        return new Trial(item.split(), item.id(), trial, "physics", success, success ? ticks : -1,
                success ? ticks : -1, finder.lastExpansions(), nanos / 1_000_000d, bytes, Double.NaN);
    }

    private static PlayerSimPhysics settled(LatticeCase item) {
        PlayerSimPhysics body = PlayerSimPhysics.settled(item.world(), item.startX(), item.startZ());
        body.face(item.startYaw());
        return body;
    }

    private static boolean promising(List<Trial> trials) {
        int n = ids(trials, "dev").size();
        if (n == 0) {
            return false;
        }
        int best = Math.max(successes(trials, "dev", "beam"), successes(trials, "dev", "weighted"));
        return best / (double) n >= PROMISING;
    }

    /**
     * Review fix: median expansions on shared dev solves, lattice then physics. Null when any shared physics row has
     * no counter (-1), so old rows still read UNKNOWN. "Within 3x" is read one-sided: the lattice may not need more
     * than 3x the baseline's expansions. A lattice expansion is one primitive node; a physics expansion is one popped
     * node with HOLD ticks per child. The units are close but not identical.
     */
    static double[] sharedExpansionMedians(List<Trial> trials, String method) {
        List<Integer> lattice = new ArrayList<>();
        List<Integer> physics = new ArrayList<>();
        for (String id : ids(trials, "dev")) {
            Trial l = trial0(trials, "dev", id, method);
            Trial p = trial0(trials, "dev", id, "physics");
            if (l != null && p != null && l.success && p.success) {
                if (p.expansions < 0 || l.expansions < 0) {
                    return null;
                }
                lattice.add(l.expansions);
                physics.add(p.expansions);
            }
        }
        if (lattice.isEmpty()) {
            return null;
        }
        return new double[]{medianInt(lattice), medianInt(physics)};
    }

    private static boolean heldOutDrop(List<Trial> trials, String method, int devSolved, int devN) {
        int heldN = ids(trials, "held").size();
        if (heldN == 0 || devN == 0) {
            return false;
        }
        double devRate = devSolved / (double) devN;
        double heldRate = successes(trials, "held", method) / (double) heldN;
        return heldRate < devRate - DIVERGENCE_LIMIT;
    }

    private static int uniqueSolves(List<Trial> trials, String split, String method) {
        int count = 0;
        for (String id : ids(trials, split)) {
            Trial lattice = trial0(trials, split, id, method);
            Trial physics = trial0(trials, split, id, "physics");
            if (lattice != null && physics != null && lattice.success && !physics.success) {
                count++;
            }
        }
        return count;
    }

    private static int successes(List<Trial> trials, String split, String method) {
        int count = 0;
        for (String id : ids(trials, split)) {
            Trial trial = trial0(trials, split, id, method);
            if (trial != null && trial.success) {
                count++;
            }
        }
        return count;
    }

    private static List<String> ids(List<Trial> trials, String split) {
        List<String> ids = new ArrayList<>();
        for (Trial trial : trials) {
            if (trial.split.equals(split) && trial.trial == 0 && trial.method.equals("physics") && !ids.contains(trial.id)) {
                ids.add(trial.id);
            }
        }
        return ids;
    }

    private static Trial trial0(List<Trial> trials, String split, String id, String method) {
        for (Trial trial : trials) {
            if (trial.split.equals(split) && trial.id.equals(id) && trial.method.equals(method) && trial.trial == 0) {
                return trial;
            }
        }
        return null;
    }

    static boolean reproducible(List<Trial> trials) {
        Map<String, List<Trial>> groups = new LinkedHashMap<>();
        for (Trial trial : trials) {
            groups.computeIfAbsent(trial.split + "|" + trial.id + "|" + trial.method, key -> new ArrayList<>()).add(trial);
        }
        for (List<Trial> group : groups.values()) {
            Trial first = group.get(0);
            for (Trial trial : group) {
                if (trial.success != first.success || trial.travelTicks != first.travelTicks
                        || trial.expansions != first.expansions || !sameDiv(trial.divergence, first.divergence)) {
                    return false;
                }
            }
        }
        return true;
    }

    static double maxDivergence(List<Trial> trials) {
        double max = 0;
        for (Trial trial : trials) {
            if (!trial.method.equals("physics") && trial.success && Double.isNaN(trial.divergence)) {
                return Double.POSITIVE_INFINITY;
            }
            if (!Double.isNaN(trial.divergence) && trial.divergence > max) {
                max = trial.divergence;
            }
        }
        return max;
    }

    private static boolean sameDiv(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        return Double.compare(a, b) == 0;
    }

    private static double medianInt(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String summary(String decision, String reason, boolean heldOutRan, List<Trial> trials, int trialCount) {
        StringBuilder text = new StringBuilder();
        text.append("decision: ").append(decision).append('\n');
        text.append("status: PROVISIONAL. Tier-S on hand-built PlayerSim worlds; divergence replays the same model, so it is self-consistency only\n");
        text.append("reason: ").append(reason).append('\n');
        text.append("held-out ran: ").append(heldOutRan).append('\n');
        text.append("semantics: ").append(PlayerSimPhysics.SEMANTICS).append('\n');
        text.append("equivalence: same PlayerSim world, settled body, goal, radius ")
                .append(LatticeSearch.RADIUS).append(", node budget ").append(LatticeSearch.NODE_BUDGET)
                .append(". Lattice also stops at ").append(LatticeSearch.TICK_BUDGET)
                .append(" travel ticks. Yaw is an instantaneous PlayerSim input.\n");
        text.append("beam width: ").append(LatticeSearch.BEAM_WIDTH)
                .append(" weight: ").append(LatticeSearch.WEIGHT).append('\n');
        text.append("trials: ").append(trialCount).append('\n');
        text.append("max divergence: ").append(divText(maxDivergence(trials))).append('\n');
        text.append("reproducible: ").append(reproducible(trials)).append('\n');
        text.append("baseline expansions: PhysicsPathfinder.lastExpansions (nodes popped; -1 means native search or no counter)\n");
        text.append("memory: thread allocated bytes, or -1 when ThreadMXBean allocation is unavailable\n");
        for (String split : List.of("dev", "held")) {
            if (ids(trials, split).isEmpty()) {
                continue;
            }
            text.append(split).append(" beam ").append(successes(trials, split, "beam")).append('/')
                    .append(ids(trials, split).size());
            text.append(" weighted ").append(successes(trials, split, "weighted")).append('/')
                    .append(ids(trials, split).size());
            text.append(" physics ").append(successes(trials, split, "physics")).append('/')
                    .append(ids(trials, split).size()).append('\n');
            for (String id : ids(trials, split)) {
                text.append(line(trials, split, id, "beam")).append('\n');
                text.append(line(trials, split, id, "weighted")).append('\n');
                text.append(line(trials, split, id, "physics")).append('\n');
            }
        }
        if (!heldOutRan) {
            text.append("held-out skipped: better dev success was below 0.7\n");
        }
        text.append(LatticeStats.block(trials));
        return text.toString();
    }

    private static String line(List<Trial> trials, String split, String id, String method) {
        Trial trial = trial0(trials, split, id, method);
        List<Double> latency = new ArrayList<>();
        List<Long> bytes = new ArrayList<>();
        for (Trial row : trials) {
            if (row.split.equals(split) && row.id.equals(id) && row.method.equals(method)) {
                latency.add(row.latencyMs);
                if (row.allocBytes >= 0) {
                    bytes.add(row.allocBytes);
                }
            }
        }
        return String.format(Locale.ROOT,
                "%s %s %s success=%s ticks=%d searchTicks=%d expansions=%d medianLatencyMs=%.3f medianAlloc=%s divergence=%s",
                split, id, method, trial.success, trial.travelTicks, trial.searchTicks, trial.expansions,
                medianDouble(latency), bytes.isEmpty() ? "UNKNOWN" : Long.toString(medianLong(bytes)),
                divText(trial.divergence));
    }

    private static double medianDouble(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n == 0) {
            return Double.NaN;
        }
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static long medianLong(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(sorted.size() / 2);
    }

    private static String divText(double divergence) {
        if (Double.isNaN(divergence)) {
            return "na";
        }
        if (divergence == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        return String.format(Locale.ROOT, "%.6f", divergence);
    }

    private static long deltaBytes(long before) {
        long after = allocated();
        if (before == Long.MIN_VALUE || after == Long.MIN_VALUE) {
            return -1;
        }
        return after - before;
    }

    /** Cumulative allocated bytes, or {@link Long#MIN_VALUE} when the JVM does not expose the counter. */
    private static long allocated() {
        try {
            Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
            Object bean = ManagementFactory.getThreadMXBean();
            if (!type.isInstance(bean)) {
                return Long.MIN_VALUE;
            }
            Method supported = type.getMethod("isThreadAllocatedMemorySupported");
            if (!(Boolean) supported.invoke(bean)) {
                return Long.MIN_VALUE;
            }
            type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
            Method read = type.getMethod("getThreadAllocatedBytes", long.class);
            return (Long) read.invoke(bean, Thread.currentThread().threadId());
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }
}
