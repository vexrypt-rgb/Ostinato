/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * Ostinato: A* runs on a single daemon worker. The tick thread installs paths and owns execution.
 * The executor writes forced inputs and look targets exactly as on the client.
 */
package baritone.headless;

import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.pathing.calc.IPathFinder;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.path.PathExecutor;
import baritone.utils.ToolSet;
import baritone.utils.pathing.Favoring;

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

    /** Ostinato: queue A*; true means accepted, not that a path has already been found. */
    public boolean gotoGoal(Goal goal) {
        cancelEverything();
        this.finished = false;
        this.goal = goal;
        BetterBlockPos start = host.getPlayerContext().playerFeet();
        CalculationContext context = new CalculationContext(host, host.bsi(),
                new ToolSet(host.getPlayerContext().player()), false, host.bsi().minY(), host.bsi().height());
        host.setCalculationContext(context);
        finder = new AStarPathFinder(start, start.x, start.y, start.z, goal,
                new Favoring(null, context), context, 1);
        AStarPathFinder search = finder;
        long primary = Baritone.settings().primaryTimeoutMS.value;
        long failure = Baritone.settings().failureTimeoutMS.value;
        pending = planner.submit(() -> search.calculate(primary, failure));
        status = "planning " + goal;
        return true;
    }

    /** Ostinato: hand off a completed search and advance exactly one executor tick. */
    public void tick() {
        if (pending != null) {
            if (!pending.isDone()) return;
            try {
                Optional<IPath> path = pending.get().getPath();
                pending = null;
                finder = null;
                if (path.isEmpty() || path.get().length() < 2) {
                    status = goal.isInGoal(host.getPlayerContext().playerFeet()) ? "arrived" : "no path";
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
            boolean arrived = target.isInGoal(host.getPlayerContext().playerFeet());
            cancelEverything();
            if (!failed && !arrived) {
                gotoGoal(target); // continue a partial search segment, still on the worker
            } else {
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
