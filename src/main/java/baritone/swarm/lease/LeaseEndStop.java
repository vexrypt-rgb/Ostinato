package baritone.swarm.lease;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 2c part 2, slice 3 (p2c2 reruns 2-3, S5b). When a lease whose command had started ends - released,
 * revoked, expired, or refused at region entry - the forced inputs are released the way #stop releases them, the leased
 * process stops and the path segment it was executing is cancelled, all before the bot's next action. Until the bot is
 * idle, no click or use is forced and {@link #QUIET_MS} have passed with no break or place attempt, the fence also keeps
 * denying breaks and places inside that lease's box (unless a current lease covers the spot). Live, rerun 2: the
 * segment kept breaking after the refusal; rerun 3: with the segment cancelled, the builder's forced left click was still
 * held and BlockBreakHelper broke the block in view once the box had been cleared a tick later.
 */
public final class LeaseEndStop {

    /** How long without a break or place attempt before an ended box stops being fenced. */
    public static final long QUIET_MS = 1_000L;

    /** What this needs from the bot (the swarm behavior; a fake in tests). */
    public interface Bot {
        /** Release the forced keys and clicks as #stop does (movement only when safe; the clicks always). */
        void releaseForcedInputs();

        /** Stop the leased processes (never survival, never cancelEverything). */
        void stopLeasedProcesses();

        /** A process other than the leased ones holds control (its path and keys are not ours to touch). */
        boolean otherProcessInControl();

        /** Cancel the executing path segment if that is safe now (not mid-jump); true once it is cancelled. */
        boolean cancelSegmentIfSafe();

        /** No path executing or being calculated and no leased process active. */
        boolean idle();

        /** A click or use (left or right) is forced down. */
        boolean clickForced();
    }

    private final BotLeaseGuard guard;
    private final Bot bot;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private long lastAttempt = Long.MIN_VALUE;

    public LeaseEndStop(BotLeaseGuard guard, Bot bot, Consumer<String> log, LongSupplier clock) {
        this.guard = guard;
        this.bot = bot;
        this.log = log == null ? s -> {} : log;
        this.clock = clock;
    }

    /** Before every fenced action and on every tick: carry out a pending lease-end stop. */
    public void settle() {
        if (!guard.endCancelPending()) {
            return;
        }
        if (bot.otherProcessInControl()) {
            bot.stopLeasedProcesses();
            guard.endCancelDone();
            log.accept("swarm-lease: lease ended; leased process stopped (another process holds control, its path and keys kept)");
            return;
        }
        bot.releaseForcedInputs();
        bot.stopLeasedProcesses();
        if (bot.cancelSegmentIfSafe()) {
            guard.endCancelDone();
            log.accept("swarm-lease: lease ended; forced inputs released, leased process stopped, in-flight path cancelled");
        }
        // not safe to cancel yet (mid-jump): retried before the next action and next tick; the fence holds meanwhile
    }

    /** For the fence: whether this break or place must be denied because a lease that covered it has just ended. */
    public boolean denied(LeaseFence.Kind kind, char dim, int x, int y, int z) {
        settle();
        if (kind != LeaseFence.Kind.BREAK && kind != LeaseFence.Kind.PLACE) {
            return false;
        }
        if (guard.hasEndedBoxes()) {
            lastAttempt = clock.getAsLong(); // any break or place attempt while a box is held restarts the quiet time
        }
        return guard.insideEndedBox(dim, x, y, z);
    }

    /**
     * Every tick: settle; the ended boxes are released only when the bot is idle, no click or use is forced, and
     * {@link #QUIET_MS} have passed since the last break or place attempt.
     */
    public void tick() {
        settle();
        if (!guard.hasEndedBoxes() || guard.endCancelPending() || !bot.idle() || bot.clickForced()) {
            return;
        }
        long now = clock.getAsLong();
        if (lastAttempt == Long.MIN_VALUE) {
            lastAttempt = now; // the quiet time counts from when the bot first looks done
            return;
        }
        if (now - lastAttempt < QUIET_MS) {
            return;
        }
        guard.clearEndedBoxes();
        lastAttempt = Long.MIN_VALUE;
    }
}
