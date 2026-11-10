/*
 * Ostinato OAP scheduling study: a harness instrument. Not part of upstream Baritone.
 */
package baritone.harness;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Controlled planner load for the live contention study (docs/performance/OAP_SCHEDULING_HANDOFF.md). Keeps up to
 * {@code n} production A* searches running on the production planner pool ({@link Baritone#getExecutor()}), each built
 * the way {@code PathingBehavior.findPathInNewThread} builds a plan-ahead search: a threaded {@link CalculationContext}
 * made on the client thread, {@link Favoring}, the far-segment weight, the plan-ahead budgets. Results are thrown away.
 * Only LegatoLiveTest's {@code arms=planload} constructs one.
 */
public final class PlanLoad {
    private static final int SLOTS = 8;

    private final Baritone baritone;
    private final AtomicReferenceArray<AbstractNodeCostSearch> slots = new AtomicReferenceArray<>(SLOTS);
    /** Bumped by {@link #stop()}: a search queued before the stop does not start. */
    private volatile int generation;
    private BetterBlockPos start;
    private Goal goal;
    public final AtomicLong started = new AtomicLong(), finished = new AtomicLong(), wallNs = new AtomicLong();
    /** Client-thread time spent building searches (context, favoring, finder), as findPathInNewThread spends it. */
    public final AtomicLong buildNs = new AtomicLong();

    public PlanLoad(Baritone baritone) {
        this.baritone = baritone;
    }

    /** Client thread: where the searches start (a standing position) and the goal they head for. */
    public void aim(BlockPos start, Goal goal) {
        this.start = new BetterBlockPos(start);
        this.goal = goal;
    }

    public boolean aimed() {
        return start != null;
    }

    /** Client thread, every lap tick: tops the running searches up to {@code n}. */
    public void tick(int n) {
        for (int i = 0; i < Math.min(n, SLOTS); i++) {
            if (slots.get(i) != null) continue;
            long b0 = System.nanoTime();
            CalculationContext context = new CalculationContext(baritone, true);
            AStarPathFinder f = new AStarPathFinder(start, start.x, start.y, start.z, goal,
                    new Favoring(baritone.getPlayerContext(), null, context), context, PathingBehavior.farSegmentWeight(goal, start));
            buildNs.addAndGet(System.nanoTime() - b0);
            slots.set(i, f);
            started.incrementAndGet();
            final int slot = i, gen = generation;
            final long primary = Baritone.settings().planAheadPrimaryTimeoutMS.value, failure = Baritone.settings().planAheadFailureTimeoutMS.value;
            Baritone.getExecutor().execute(() -> {
                long t0 = System.nanoTime();
                try {
                    if (gen == generation) f.calculate(primary, failure);
                } finally {
                    wallNs.addAndGet(System.nanoTime() - t0);
                    finished.incrementAndGet();
                    slots.compareAndSet(slot, f, null);
                }
            });
        }
    }

    /** Client thread: cancels every running search; each stops at its next node. */
    public void stop() {
        generation++;
        for (int i = 0; i < SLOTS; i++) {
            AbstractNodeCostSearch f = slots.get(i);
            if (f != null) f.cancel();
        }
    }

    /** Searches submitted and not yet returned. */
    public int running() {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) if (slots.get(i) != null) n++;
        return n;
    }

    /**
     * CPU time and GC so far, for per-lap deltas: [0] planner workers ns, [1] client (Render thread) ns, [2] integrated
     * server thread ns, [3] whole process ns, [4] GC pauses, [5] GC pause ms, [6] concurrent GC ms. Threads that ended
     * since the last call are not counted (only non-core workers end, after 60 s idle).
     */
    public static long[] cpu() {
        long[] out = new long[7];
        ThreadMXBean t = ManagementFactory.getThreadMXBean();
        for (ThreadInfo info : t.getThreadInfo(t.getAllThreadIds())) {
            if (info == null) continue;
            String name = info.getThreadName();
            int k = name.startsWith("Ostinato worker") ? 0 : name.equals("Render thread") ? 1 : name.equals("Server thread") ? 2 : -1;
            if (k < 0) continue;
            long ns = t.getThreadCpuTime(info.getThreadId());
            if (ns > 0) out[k] += ns;
        }
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            out[3] = os.getProcessCpuTime();
        }
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().contains("Concurrent")) {
                out[6] += gc.getCollectionTime();
            } else {
                out[4] += gc.getCollectionCount();
                out[5] += gc.getCollectionTime();
            }
        }
        return out;
    }

    /** GC pauses so far (every collector but the concurrent one): cheap enough to read every tick. */
    public static long gcPauses() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!gc.getName().contains("Concurrent")) n += gc.getCollectionCount();
        }
        return n;
    }

    /** One line: processors, GC names, and the Java priority of the threads that matter, for the run log. */
    public static String environment() {
        StringBuilder b = new StringBuilder("cpus=").append(Runtime.getRuntime().availableProcessors()).append(" gc=");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) b.append(gc.getName().replace(' ', '_')).append(',');
        b.append(" prio=");
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            String n = th.getName();
            if (n.startsWith("Ostinato worker") || n.equals("Render thread") || n.equals("Server thread")) {
                b.append(n.replace(' ', '_')).append(':').append(th.getPriority()).append(',');
            }
        }
        b.append(" jvm=").append(String.join("|", ManagementFactory.getRuntimeMXBean().getInputArguments()).replace(' ', '_'));
        return b.toString();
    }
}
