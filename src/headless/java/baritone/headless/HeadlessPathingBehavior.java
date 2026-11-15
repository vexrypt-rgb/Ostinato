/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * Ostinato: A* runs on a single daemon worker. The tick thread installs paths and owns execution.
 * The executor writes forced inputs and look targets exactly as on the client.
 */
package baritone.headless;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.path.PathExecutor;
import baritone.utils.ToolSet;
import baritone.utils.pathing.Favoring;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;

public final class HeadlessPathingBehavior implements IPathingBehavior {

    private final HeadlessBaritone host;

    private volatile Goal goal;
    private PathExecutor executor;
    private volatile boolean finished;

    HeadlessPathingBehavior(HeadlessBaritone host) {
        this.host = host;
    }

    // Ostinato: only the bot tick owns goal/executor/context; the worker publishes a path, never packets.
    private final java.util.concurrent.ExecutorService planner = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "headless-astar");
        thread.setDaemon(true);
        return thread;
    });
    private java.util.concurrent.Future<PathCalculationResult> pending;
    private AStarPathFinder finder;
    private volatile String status = "idle";

    // Ostinato: same start-retry map and timeout policy as client PathingBehavior.findPathInNewThread.
    private final HashMap<BetterBlockPos, Long> plannedStarts = new HashMap<>();
    private Goal plannedStartsGoal;
    private boolean mayEscalate;
    private boolean planAhead;

    /** Ostinato: queue A*; true means accepted, not that a path has already been found. */
    public boolean gotoGoal(Goal goal) {
        cancelEverything();
        startSearch(goal, false);
        return true;
    }

    /**
     * Ostinato: start A* without clearing keys. A partial sneak-bridge must keep sneak
     * held through the wait; cancelEverything() would drop the bot off the 1-wide edge.
     *
     * Ostinato: first search uses primaryTimeoutMS/failureTimeoutMS (defaults 500/2000),
     * unchanged. A later segment uses planAhead timeouts. A timeout with nodes still
     * open retries from the same start with the client's doubling cap (failureTimeoutMaxMS).
     */
    private void startSearch(Goal goal, boolean planAhead) {
        this.finished = false;
        this.goal = goal;
        this.planAhead = planAhead;
        BetterBlockPos start = host.getPlayerContext().playerFeet();
        boolean allowPlace = Baritone.settings().allowPlace.value;
        boolean hasThrowaway = allowPlace && host.hasGenericThrowaway();
        int throwawayCount = host.countGenericThrowaway();
        String hotbar = host.describeHotbar();
        CalculationContext context = new CalculationContext(host, host.bsi(),
                new ToolSet(host.getPlayerContext().player()), hasThrowaway, host.bsi().minY(), host.bsi().height());
        host.setCalculationContext(context);

        long primary;
        long failure;
        if (planAhead) {
            primary = Baritone.settings().planAheadPrimaryTimeoutMS.value;
            failure = Baritone.settings().planAheadFailureTimeoutMS.value;
        } else {
            primary = Baritone.settings().primaryTimeoutMS.value;
            failure = Baritone.settings().failureTimeoutMS.value;
        }
        if (!Objects.equals(plannedStartsGoal, goal) || plannedStarts.size() > 512) {
            plannedStartsGoal = goal;
            plannedStarts.clear();
        }
        Long lastHere = plannedStarts.get(start);
        long failureCap = Math.max(failure, Baritone.settings().failureTimeoutMaxMS.value);
        if (lastHere != null) {
            failure = Math.min(failureCap, Math.max(failure, Math.min(lastHere, failureCap) * 2));
            if (primary < failure) {
                System.out.println("[HeadlessPathing] A* retry from " + start + ", allowing the full " + failure + "ms");
                primary = failure;
            }
        }
        plannedStarts.put(start, primary);
        mayEscalate = failure < failureCap;

        finder = new AStarPathFinder(start, start.x, start.y, start.z, goal,
                new Favoring(null, context), context, 1);
        if (mayEscalate && goal instanceof IGoalRenderPos render && context.bsi != null
                && context.bsi.worldContainsLoadedChunk(render.getGoalPos().getX(), render.getGoalPos().getZ())) {
            finder.holdAtDrops = true;
        }
        AStarPathFinder search = finder;
        final long primaryMs = primary;
        final long failureMs = failure;
        System.out.println("[HeadlessPathing] A* start start=" + start + " goal=" + goal
                + " primaryMs=" + primaryMs + " failureMs=" + failureMs
                + " mayEscalate=" + mayEscalate + " planAhead=" + planAhead
                + " allowPlace=" + allowPlace + " hasThrowaway=" + hasThrowaway
                + " throwawayCount=" + throwawayCount + " " + hotbar);
        pending = planner.submit(() -> search.calculate(primaryMs, failureMs));
        status = "planning " + goal;
    }

    /** Ostinato: hand off a completed search and advance exactly one executor tick. */
    public void tick() {
        if (pending != null) {
            if (!pending.isDone()) return;
            try {
                PathCalculationResult result = pending.get();
                AStarPathFinder done = finder;
                boolean timedOut = done != null && done.timedOut();
                boolean escalate = mayEscalate;
                Optional<IPath> path = result.getPath();
                pending = null;
                finder = null;
                PathCalculationResult.Type type = result.getType();
                int length = path.isPresent() ? path.get().length() : 0;
                String dest = path.isPresent() ? String.valueOf(path.get().getDest()) : "none";
                int nodes = path.isPresent() ? path.get().getNumNodesConsidered() : -1;
                System.out.println("[HeadlessPathing] A* result type=" + type
                        + " timedOut=" + timedOut
                        + " pathLength=" + length
                        + " dest=" + dest
                        + " nodes=" + nodes
                        + " start=" + host.getPlayerContext().playerFeet()
                        + " goal=" + goal
                        + " mayEscalate=" + escalate
                        + " planAhead=" + planAhead
                        + " allowPlace=" + Baritone.settings().allowPlace.value
                        + " hasThrowaway=" + (goal != null && host.hasGenericThrowaway())
                        + " throwawayCount=" + host.countGenericThrowaway()
                        + " " + host.describeHotbar());
                if (path.isEmpty() || length < 2) {
                    // Ostinato: match the client. A timeout with nodes still open is not a final
                    // failure while a longer search from the same start is still allowed.
                    if (type == PathCalculationResult.Type.FAILURE && timedOut && escalate && goal != null) {
                        Goal target = goal;
                        startSearch(target, planAhead);
                        return;
                    }
                    String reason;
                    if (type == PathCalculationResult.Type.FAILURE && timedOut) {
                        reason = "no path (timeout)";
                    } else if (type == PathCalculationResult.Type.FAILURE) {
                        reason = "no path";
                    } else if (type == PathCalculationResult.Type.CANCELLATION) {
                        reason = "cancelled";
                    } else {
                        reason = "no path (" + type + ")";
                    }
                    status = goal != null && goal.isInGoal(host.getPlayerContext().playerFeet()) ? "arrived" : reason;
                    cancelEverything();
                    finished = true;
                    return;
                }
                executor = new PathExecutor(host, path.get());
                status = "executing " + goal;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "planning interrupted";
                cancelEverything();
                finished = true;
                return;
            } catch (Exception e) {
                status = "planning failed: " + e;
                cancelEverything();
                finished = true;
                System.err.println("[HeadlessPathing] " + status);
                return;
            }
        }
        PathExecutor current = executor;
        if (current == null) return;
        current.onTick(); // returns safe-to-cancel, NOT path completion
        if (current.finished() || current.failed()) {
            boolean failed = current.failed();
            Goal target = goal;
            boolean arrived = target != null && target.isInGoal(host.getPlayerContext().playerFeet());
            if (!failed && !arrived && target != null) {
                // Ostinato: drop only the finished executor. Keep sneak/keys and the goal
                // while A* plans the next segment so a 1-wide sneak-bridge does not fall.
                // Use the client's plan-ahead timeouts for this next-segment search.
                executor = null;
                startSearch(target, true);
            } else {
                cancelEverything();
                finished = true;
                status = arrived ? "arrived" : "execution failed";
            }
        }
    }

    // Ostinato: cancel also invalidates unpublished results, including searches already completing.
    public void close() {
        cancelEverything();
        planner.shutdownNow();
    }

    public String status() {
        return status;
    }

    /** Ostinato: true while A* is running (including the wait between partial path segments). */
    public boolean planning() {
        return pending != null && !pending.isDone();
    }

    public boolean finished() {
        return finished;
    }

    // ------------------------------------------------------------------ IPathingBehavior

    @Override
    public BetterBlockPos pathStart() {
        return host.getPlayerContext().playerFeet();
    }

    @Override
    public Optional<Double> estimatedTicksToGoal() {
        return Optional.empty();
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public boolean isPathing() {
        return goal != null;
    }

    @Override
    public boolean cancelEverything() {
        boolean was = goal != null;
        if (finder != null) finder.cancel();
        if (pending != null) pending.cancel(false);
        pending = null;
        finder = null;
        executor = null;
        goal = null;
        plannedStarts.clear();
        plannedStartsGoal = null;
        mayEscalate = false;
        planAhead = false;
        host.getInputOverrideHandler().clearAllKeys();
        host.getPlayerContext().playerController().resetBlockRemoving();
        return was;
    }

    /** Ostinato: operator cancel. Planning failures set their own status after {@link #cancelEverything()}. */
    void noteCancelled() {
        status = "idle";
        finished = false;
    }

    @Override
    public void forceCancel() {
        cancelEverything();
    }

    @Override
    public void requestPause() {
    }

    @Override
    public boolean isSafeToCancel() {
        return true;
    }

    @Override
    public Optional<? extends IPathFinder> getInProgress() {
        return Optional.ofNullable(finder);
    }

    @Override
    public IPathExecutor getCurrent() {
        return executor;
    }

    @Override
    public IPathExecutor getNext() {
        return null;
    }
}
