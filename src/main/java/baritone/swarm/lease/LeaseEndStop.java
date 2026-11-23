package baritone.swarm.lease;

import java.util.function.Consumer;

/**
 * Ostinato: Phase 2c part 2, slice 3 (p2c2 rerun 2, S5b). When a lease whose command had started ends - released,
 * revoked, expired, or refused at region entry - the leased process stops and the path segment it was executing is
 * cancelled before the bot's next action; until the bot is idle, the fence also keeps denying breaks and places inside
 * that lease's box (unless a current lease covers the spot). Live, the refusal ended the lease and stopped the tunnel,
 * but the segment already executing kept breaking blocks inside the region for about 2 s, unfenced: the fence only
 * acted while a leased process was in control.
 */
public final class LeaseEndStop {

    /** What this needs from the bot (the swarm behavior; a fake in tests). */
    public interface Bot {
        /** Stop the leased processes (never survival, never cancelEverything). */
        void stopLeasedProcesses();

        /** A process other than the leased ones holds control (its path is not ours to cancel). */
        boolean otherProcessInControl();

        /** Cancel the executing path segment if that is safe now (not mid-jump); true once it is cancelled. */
        boolean cancelSegmentIfSafe();

        /** No path executing or being calculated and no leased process active. */
        boolean idle();
    }

    private final BotLeaseGuard guard;
    private final Bot bot;
    private final Consumer<String> log;

    public LeaseEndStop(BotLeaseGuard guard, Bot bot, Consumer<String> log) {
        this.guard = guard;
        this.bot = bot;
        this.log = log == null ? s -> {} : log;
    }

    /** Before every fenced action and on every tick: carry out a pending lease-end stop. */
    public void settle() {
        if (!guard.endCancelPending()) {
            return;
        }
        bot.stopLeasedProcesses();
        if (bot.otherProcessInControl()) {
            guard.endCancelDone();
            log.accept("swarm-lease: lease ended; leased process stopped (another process holds control, its path kept)");
            return;
        }
        if (bot.cancelSegmentIfSafe()) {
            guard.endCancelDone();
            log.accept("swarm-lease: lease ended; leased process stopped and its in-flight path cancelled");
        }
        // not safe to cancel yet (mid-jump): retried before the next action and next tick; the fence holds meanwhile
    }

    /** For the fence: whether this break or place must be denied because a lease that covered it has just ended. */
    public boolean denied(LeaseFence.Kind kind, char dim, int x, int y, int z) {
        settle();
        if (kind != LeaseFence.Kind.BREAK && kind != LeaseFence.Kind.PLACE) {
            return false;
        }
        return guard.insideEndedBox(dim, x, y, z);
    }

    /** Every tick: settle, and once the bot is idle the ended boxes no longer need the backstop. */
    public void tick() {
        settle();
        if (!guard.endCancelPending() && bot.idle()) {
            guard.clearEndedBoxes();
        }
    }
}
