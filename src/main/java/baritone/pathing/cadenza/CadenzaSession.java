/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a process keeps between ticks to use Cadenza: the candidate memo, cells the live check refused, and the one
 * interaction in progress. One per process; main thread only.
 * <p>
 * Lifecycle of an interaction (see {@link CadenzaStats.Phase}): the process calls {@link #attempt} on every tick it
 * clicks a target, {@link #confirm} when the world shows it done, {@link #invalidate} when the target changed under
 * it; {@link #tick} turns a click that runs past its budget into FAILED and hands the target back so the caller can
 * blacklist it (never retried in a loop).
 */
public final class CadenzaSession {

    /** A cell the live reach check refused while standing in it is left out of goals for this long. */
    static final long BAD_CELL_MS = 30_000;
    /** Standing in a candidate this many ticks with nothing reachable marks the cell bad. */
    static final int POSE_MISS_TICKS = 10;
    /**
     * Test harness only (v2.1 A/B): false restores v2's memo behaviour, where a confirm leaves the sets near the
     * changed block in place until the TTL. Always true outside the harness.
     */
    static boolean invalidateOnConfirm = true;

    final CadenzaCache cache = new CadenzaCache(1000);
    private final Long2LongOpenHashMap badCells = new Long2LongOpenHashMap();
    private CadenzaGoal lastGoal;
    private int poseMiss;
    private BlockPos current;
    private long currentStart, currentBudget;
    private long ticks;
    java.util.function.LongSupplier clock = System::currentTimeMillis;

    /**
     * A goal for these targets. Targets Cadenza finds no cell for are reported to {@code noCell} (the caller usually
     * adds its old goal for them to {@code extras}, which ride along at terminal cost 0).
     */
    public CadenzaGoal goal(CalculationContext ctx, List<Cadenza.Target> targets, Cadenza.Forbidden support, boolean shortHorizon,
                            Consumer<Cadenza.Result> noCell, List<Goal> extras) {
        return goal(ctx, targets, support, Cadenza.CellFilter.ALL, shortHorizon, noCell, extras);
    }

    /** As above, keeping only the cells {@code accept} allows. */
    public CadenzaGoal goal(CalculationContext ctx, List<Cadenza.Target> targets, Cadenza.Forbidden support, Cadenza.CellFilter accept,
                            boolean shortHorizon, Consumer<Cadenza.Result> noCell, List<Goal> extras) {
        return goal(ctx, targets, support, accept, shortHorizon, CadenzaGoal.Options.OFF, noCell, extras);
    }

    /** As above, with the v2 turn and batch options. */
    public CadenzaGoal goal(CalculationContext ctx, List<Cadenza.Target> targets, Cadenza.Forbidden support, Cadenza.CellFilter accept,
                            boolean shortHorizon, CadenzaGoal.Options options, Consumer<Cadenza.Result> noCell, List<Goal> extras) {
        long now = clock.getAsLong();
        badCells.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        List<Cadenza.Result> raw = cache.resolveAll(ctx, targets, support);
        List<Cadenza.Result> kept = new ArrayList<>(raw.size());
        for (Cadenza.Result r : raw) {
            Cadenza.Result f = filtered(r, accept);
            if (!f.ok()) {
                CadenzaStats.phase(CadenzaStats.Phase.NO_CANDIDATE);
                noCell.accept(f);
            }
            kept.add(f);
        }
        CadenzaStats.goal();
        lastGoal = new CadenzaGoal(kept, shortHorizon ? SHARE_BONUS : 0, options, extras.toArray(new Goal[0]));
        return lastGoal;
    }

    /** The walk's held look pitch (live: 5 to 12 degrees down while walking flat). */
    static final double WALK_PITCH = 8;

    /**
     * The v2 options the settings ask for: {@code cadenzaTurnAware} (or {@code cadenzaBatch}, which implies it) at the
     * live per-tick turn cap, the aim processor's ({@code ostinato.aim.deg}, 32) under the human input gate's.
     */
    public static CadenzaGoal.Options liveOptions(CadenzaGoal.Ordering ordering) {
        boolean batch = baritone.Baritone.settings().cadenzaBatch.value;
        if (!batch && !baritone.Baritone.settings().cadenzaTurnAware.value) {
            return CadenzaGoal.Options.OFF;
        }
        Cadenza.TurnModel turn = new Cadenza.TurnModel(turnCap(), WALK_PITCH);
        return batch ? CadenzaGoal.Options.batched(turn, ordering) : CadenzaGoal.Options.turnAware(turn);
    }

    /** Degrees the camera turns per tick at most (yaw and pitch each, at the same time). */
    public static double turnCap() {
        double cap = 32;
        try {
            cap = Float.parseFloat(System.getProperty("ostinato.aim.deg", "32"));
        } catch (NumberFormatException ignored) {}
        return Math.max(1, Math.min(cap, baritone.utils.HumanInputCaps.maxYaw()));
    }

    /** Ticks taken off a cell's terminal cost for each extra target it reaches (short horizon). */
    static final double SHARE_BONUS = 4.0;

    /** Without the cells found unusable live and the ones the caller refuses. */
    private Cadenza.Result filtered(Cadenza.Result r, Cadenza.CellFilter accept) {
        if ((badCells.isEmpty() && accept == Cadenza.CellFilter.ALL) || !r.ok()) {
            return r;
        }
        List<Cadenza.Candidate> c = new ArrayList<>(r.candidates.size());
        for (Cadenza.Candidate k : r.candidates) {
            if (!badCells.containsKey(BetterBlockPos.longHash(k.x, k.y, k.z)) && accept.accept(r.target, k.x, k.y, k.z)) {
                c.add(k);
            }
        }
        if (c.size() == r.candidates.size()) {
            return r;
        }
        return new Cadenza.Result(r.target, c.isEmpty() ? Cadenza.Status.OCCLUDED : Cadenza.Status.OK, c, r.examined, r.standable, r.rays);
    }

    /**
     * Call each tick the process found nothing it could act on from where it stands. Standing in a candidate of the
     * last goal for {@link #POSE_MISS_TICKS} ticks means the planned cell does not work live: it is left out of goals
     * for a while, so the planner moves on instead of idling "in goal".
     */
    public void nothingReachable(BetterBlockPos feet, boolean onGround) {
        if (lastGoal == null || !onGround || !lastGoal.isCandidate(feet.x, feet.y, feet.z)) {
            poseMiss = 0;
            return;
        }
        if (++poseMiss >= POSE_MISS_TICKS) {
            poseMiss = 0;
            badCells.put(BetterBlockPos.longHash(feet.x, feet.y, feet.z), clock.getAsLong() + BAD_CELL_MS);
            CadenzaStats.phase(CadenzaStats.Phase.POSE_UNUSABLE);
        }
    }

    /** Whether the player stands in a candidate cell of the last goal. */
    public boolean inCandidate(BetterBlockPos feet) {
        return lastGoal != null && lastGoal.isCandidate(feet.x, feet.y, feet.z);
    }

    /**
     * The process is clicking {@code target} this tick. The first tick on a new target counts POSE_REACHED and
     * ATTEMPTED; {@code budgetTicks} is how long the click may take before it counts as failed.
     */
    public void attempt(BlockPos target, long budgetTicks) {
        poseMiss = 0;
        if (target.equals(current)) {
            return;
        }
        current = target.immutable();
        currentStart = ticks;
        currentBudget = budgetTicks;
        CadenzaStats.phase(CadenzaStats.Phase.POSE_REACHED);
        CadenzaStats.phase(CadenzaStats.Phase.ATTEMPTED);
    }

    public BlockPos current() {
        return current;
    }

    /** Diagnostics: ticks since the current interaction's first click, and its budget (-1 without one). */
    public long elapsed() {
        return current == null ? -1 : ticks - currentStart;
    }

    public long budget() {
        return current == null ? -1 : currentBudget;
    }

    /**
     * The world shows the current interaction done. The block it changed may hide or open other targets' faces, so
     * their memoised candidate sets are dropped (v2.1).
     */
    public void confirm() {
        if (current != null) {
            CadenzaStats.phase(CadenzaStats.Phase.CONFIRMED);
            if (invalidateOnConfirm) {
                cache.invalidateNear(current);
            }
            current = null;
        }
    }

    /** The current target changed under the interaction before it was done. */
    public void invalidate() {
        if (current != null) {
            CadenzaStats.phase(CadenzaStats.Phase.INVALIDATED);
            current = null;
        }
    }

    /** The current interaction was tried and gave up (the caller's own timeout). */
    public void fail() {
        if (current != null) {
            CadenzaStats.phase(CadenzaStats.Phase.FAILED);
            current = null;
        }
    }

    /** Once per tick. Returns the target whose click ran past its budget (now FAILED), else null. */
    public BlockPos tick() {
        ticks++;
        if (current != null && ticks - currentStart > currentBudget) {
            BlockPos failed = current;
            CadenzaStats.phase(CadenzaStats.Phase.FAILED);
            current = null;
            return failed;
        }
        return null;
    }

    /** Drop the current interaction without a verdict (the process stopped). */
    public void reset() {
        current = null;
        poseMiss = 0;
        lastGoal = null;
        cache.clear();
    }
}
