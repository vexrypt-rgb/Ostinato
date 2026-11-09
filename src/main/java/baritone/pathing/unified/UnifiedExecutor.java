/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.unified;

import baritone.api.utils.input.Input;
import baritone.utils.InputArbiter;

/**
 * Ostinato 2.0 Phase 06: input ownership for the physics driver, used only while {@code unifiedExecutor} is on.
 * <p>
 * The driver takes a lease on movement, jump and sneak from the {@link InputArbiter} before it writes keys. Once it
 * has written in a pass, presses from anyone else are refused until the tick ends (releases are always let through).
 * Every way the driving can end hands the lease back and records how it ended, with the keys checked where that is
 * possible. Nothing here plans or simulates; the physics driver and its A* path stay as they are.
 * <p>
 * Game thread only. No allocation per tick: counters are one array, sized once.
 */
public final class UnifiedExecutor {

    /** The raw key map the gate sits in front of. {@link baritone.utils.InputOverrideHandler} in game. */
    public interface Keys {
        /** Whether {@code input} is forced down in the map, physical input aside. */
        boolean forcedRaw(Input input);

        /** Whether an analog movement vector is set. */
        boolean analogForced();

        /** Lift {@code input} without going through the gate. */
        void releaseRaw(Input input);
    }

    public static final String OWNER = "unified.physics";
    public static final String EMERGENCY_OWNER = "unified.emergency";
    /** Below the fall controller's 10: a fall beats the walk. */
    public static final int PRIORITY = 5;
    /** Ticks to wait before claiming again after a refused claim. */
    public static final int RETRY_TICKS = 20;

    private static final InputArbiter.Resource[] LEASE = {
            InputArbiter.Resource.MOVE, InputArbiter.Resource.JUMP, InputArbiter.Resource.SNEAK};
    private static final Input[] LEASE_INPUTS = {
            Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SPRINT, Input.JUMP, Input.SNEAK};

    /** How the last command stands. */
    public enum Outcome {
        REQUESTED,
        ACCEPTED,
        EXECUTING,
        /** Moved along the path and handed back with the lease and keys confirmed released. */
        COMPLETED_VERIFIED,
        FAILED,
        /** A cancel, pause or emergency was honoured, with the lease and keys confirmed released. */
        CANCELLED_CONFIRMED,
        /** Ended without proof of progress or of release. */
        UNKNOWN
    }

    /** Why the last command ended. */
    public enum Cause {
        DROP, YIELD, EMPTY_PLAN, M01, EXECUTOR_CANCEL, SEGMENT_CANCEL, EMERGENCY, PAUSE, SOFT_CANCEL, STALE,
        SEGMENT_END, FLAG_OFF, HOLDER_CHANGE, REFUSED, WORLD_CHANGE, IDLE, CANCEL_STOP,
        /** Every process was cancelled while the segment cancel was refused. */
        PROCESS_CANCEL
    }

    public enum Counter {
        ACQUIRES, ACQUIRE_REFUSED, COMPLETIONS, HANDOVERS, FAILURES, CANCELS, CANCEL_STOPS, CANCEL_REFUSALS,
        STALE_REJECTS, PREEMPTIONS, UNKNOWNS,
        /** Presses the gate refused. */
        CONFLICTS,
        /** Ticks a press from outside the gate window was standing when the driver wrote over it. */
        ERASED,
        /** Owned ticks that ended with a lease key pressed that the driver did not press. Zero while the gate holds. */
        OVERWRITTEN,
        /** Owned ticks that ended with a key the driver pressed already lifted by someone else. */
        RELEASED_EARLY,
        INTACT_TICKS, OWNED_TICKS,
        DIVERGENCE_CHECKS, DIVERGENCES, PREDICTION_UNAVAILABLE,
        CORRECTIONS, CORRECTIONS_ACCEPTED, ESCALATIONS,
        CANCEL_LATENCY_SUM, CANCEL_LATENCY_MAX,
        /** Leases given up, verified, because every process was about to be cancelled while the segment ran on. */
        PROCESS_CANCELS,
        /** Phase 06A: second, deeper searches after a correction-triggered replan found no path. One at most a replan. */
        CORRECTION_RETRIES,
        /** Phase 06A: the retries that found a plan. */
        CORRECTION_RETRY_PLANS
    }

    private final InputArbiter arbiter;
    private final Keys keys;
    private final long[] counts = new long[Counter.values().length];

    private InputArbiter.Lease lease;
    private long epoch;
    /** The path being driven. A splice keeps the driver and restarts positions at 0, so the path is the identity. */
    private Object holder;
    private int startPosition, lastPosition;
    private boolean frameForward, frameSprint, frameJump;
    private long tick, ownedTick = -1, gateTick = -1, stoppedTick = -1, retryTick = -1;
    private boolean writing;
    private long cancelRequestTick = -1, lastRefusalTick = -1;
    private int refusalTicks;
    private Outcome state;
    private Cause lastCause;

    public UnifiedExecutor(InputArbiter arbiter, Keys keys) {
        this.arbiter = arbiter;
        this.keys = keys;
    }

    // ---- tick frame ----

    /** Start of a client tick, before any behaviour runs. */
    public void beginTick() {
        tick++;
        writing = false;
        gateTick = -1;
    }

    /** Start of one executor pass. A later pass in the same tick runs drivers ahead of physics again, so it opens the gate. */
    public void beginPass() {
        gateTick = -1;
    }

    /**
     * End of a client tick, after every behaviour. Checks an owned tick's keys, and hands back a lease nobody drove
     * this tick ({@code enabled} false means the switch went off while it was held). Never presses or restores keys.
     */
    public void endTick(boolean enabled) {
        if (lease != null && ownedTick == tick && held()) {
            boolean early = frameForward && !keys.forcedRaw(Input.MOVE_FORWARD)
                    || frameSprint && !keys.forcedRaw(Input.SPRINT)
                    || frameJump && !keys.forcedRaw(Input.JUMP);
            boolean over = keys.analogForced();
            for (Input in : LEASE_INPUTS) {
                if (!frameKey(in) && keys.forcedRaw(in)) {
                    over = true;
                }
            }
            bump(early ? Counter.RELEASED_EARLY : over ? Counter.OVERWRITTEN : Counter.INTACT_TICKS);
        }
        if (lease != null && ownedTick != tick) {
            if (!enabled) {
                // The flag went off: the legacy driver writes these keys now, so they are not ours to account for.
                frameForward = frameSprint = frameJump = false;
            }
            arbiter.confirmRelease(OWNER);
            bump(Counter.HANDOVERS);
            settle(lastPosition, enabled ? Cause.IDLE : Cause.FLAG_OFF);
        }
        gateTick = -1;
    }

    // ---- the driver's side ----

    /**
     * Before the physics driver writes keys for {@code path} at {@code pos}. Takes the lease if none is held.
     *
     * @return false if the driver must not write: the lease was lost or could not be had
     */
    public boolean beginWrite(Object path, int pos) {
        if (lease != null && holder != path) {
            handback(Cause.HOLDER_CHANGE, lastPosition);
        }
        if (lease != null && !held()) {
            bump(Counter.STALE_REJECTS);
            if (lease.alive()) {
                arbiter.confirmRelease(OWNER);
            }
            lease = null;
            end(Outcome.UNKNOWN, Cause.STALE, Counter.UNKNOWNS);
            return false;
        }
        if (lease == null) {
            if (tick < retryTick) {
                return false;
            }
            state = Outcome.REQUESTED;
            InputArbiter.Lease l = arbiter.claim(OWNER, PRIORITY, false, LEASE);
            if (!holdsAll(l)) {
                // Someone above us holds a key. A claim allocates, so do not ask again every tick.
                arbiter.confirmRelease(OWNER);
                retryTick = tick + RETRY_TICKS;
                end(Outcome.FAILED, Cause.REFUSED, Counter.ACQUIRE_REFUSED);
                return false;
            }
            lease = l;
            epoch = l.epoch();
            holder = path;
            startPosition = pos;
            frameForward = frameSprint = frameJump = false;
            state = Outcome.ACCEPTED;
            bump(Counter.ACQUIRES);
        } else if (ownedTick == tick - 1) {
            for (Input in : LEASE_INPUTS) {
                if (!frameKey(in) && keys.forcedRaw(in)) {
                    bump(Counter.ERASED);
                    break;
                }
            }
        }
        if (ownedTick != tick) {
            bump(Counter.OWNED_TICKS);
        }
        writing = true;
        ownedTick = tick;
        gateTick = tick;
        lastPosition = pos;
        state = Outcome.EXECUTING;
        return true;
    }

    /** After the driver's writes. The keys it pressed this tick. */
    public void endWrite(boolean forward, boolean sprint, boolean jump) {
        frameForward = forward;
        frameSprint = sprint;
        frameJump = jump;
        writing = false;
    }

    // ---- the gate, from InputOverrideHandler ----

    /** Whether a press of {@code input} from anyone but the driver is refused now. */
    public boolean refusesPress(Input input) {
        if (gateTick != tick || writing || input == null || input == Input.CLICK_LEFT || input == Input.CLICK_RIGHT || !held()) {
            return false;
        }
        bump(Counter.CONFLICTS);
        return true;
    }

    /** Whether setting an analog movement vector is refused now. */
    public boolean refusesAnalog() {
        if (gateTick != tick || writing || !held()) {
            return false;
        }
        bump(Counter.CONFLICTS);
        return true;
    }

    // ---- endings ----

    /** The driver let go: it declined the spot, or the path or a pause took the controls. Lifts its keys. */
    public void handback(Cause cause, int pos) {
        if (lease == null) {
            return;
        }
        releaseFrame();
        arbiter.confirmRelease(OWNER);
        settle(pos, cause);
    }

    /** Another driver took this tick after physics declined it. Its keys are that driver's now, so none are touched. */
    public void yield(int pos) {
        if (lease == null) {
            return;
        }
        if (!lease.alive() || lease.epoch() != epoch) {
            bump(Counter.PREEMPTIONS);
        }
        // The driver that took over this tick owns the keys now: a forward it pressed is not one of ours left down.
        frameForward = frameSprint = frameJump = false;
        arbiter.confirmRelease(OWNER);
        bump(Counter.HANDOVERS);
        settle(pos, Cause.YIELD);
    }

    /** The driver failed (an empty plan, M01, an executor cancel). */
    public void fail(Cause cause) {
        if (lease == null) {
            return;
        }
        releaseFrame();
        arbiter.fail(OWNER);
        lease = null;
        end(Outcome.FAILED, cause, Counter.FAILURES);
    }

    /**
     * Pathing went into emergency mode. An emergency claim takes the lease from the driver (bumping its epoch, so
     * anything still queued under the old one is stale), then lets go. The emergency branch clears the keys itself.
     */
    public void emergency() {
        if (lease == null) {
            return;
        }
        if (held()) {
            arbiter.claim(EMERGENCY_OWNER, PRIORITY, true, LEASE);
            arbiter.confirmRelease(EMERGENCY_OWNER);
        } else if (lease.alive()) {
            arbiter.confirmRelease(OWNER);
        }
        lease = null;
        bump(Counter.PREEMPTIONS);
        end(verifiedKeys() ? Outcome.CANCELLED_CONFIRMED : Outcome.UNKNOWN, Cause.EMERGENCY,
                verifiedKeys() ? Counter.CANCELS : Counter.UNKNOWNS);
        clearStreak();
    }

    /** A cancel was refused because the driver was not at a safe point. Counts once a tick. */
    public void cancelRefused() {
        if (lease == null || lastRefusalTick == tick) {
            return;
        }
        if (lastRefusalTick < tick - 1) {
            cancelRequestTick = tick;
            refusalTicks = 0;
        }
        refusalTicks++;
        lastRefusalTick = tick;
        bump(Counter.CANCEL_REFUSALS);
    }

    /** Whether a cancel asked for this tick and the last has waited long enough, and the driver can stop here. */
    public boolean cancelDue(boolean onGround) {
        if (lastRefusalTick < tick - 1) {
            clearStreak();
            return false;
        }
        return refusalTicks >= 2 && onGround;
    }

    /** The driver stopped for a pending cancel and cleared its keys. Reported safe to cancel this tick. */
    public void stopForCancel() {
        if (lease == null) {
            return;
        }
        releaseFrame();
        arbiter.confirmRelease(OWNER);
        boolean ok = verified();
        lease = null;
        end(ok ? Outcome.CANCELLED_CONFIRMED : Outcome.UNKNOWN, Cause.CANCEL_STOP, ok ? Counter.CANCEL_STOPS : Counter.UNKNOWNS);
        latency();
        stoppedTick = tick;
        clearStreak();
    }

    /** Whether the driver stopped for a cancel this tick. */
    public boolean stoppedForCancel() {
        return stoppedTick == tick;
    }

    /** A cancel, pause or world change went through while the driver held the lease. */
    public void cancelConfirmed(Cause cause) {
        if (lease != null) {
            releaseFrame();
            arbiter.confirmRelease(OWNER);
            boolean ok = verified();
            lease = null;
            end(ok ? Outcome.CANCELLED_CONFIRMED : Outcome.UNKNOWN, cause, ok ? Counter.CANCELS : Counter.UNKNOWNS);
            if (cancelRequestTick >= 0) {
                latency();
            }
        }
        clearStreak();
    }

    /**
     * Every process is about to lose control while the segment cancel was refused. A process's {@code onLostControl}
     * may clear every key, the driver's included, so the driver gives up its lease first: it lifts its own keys and the
     * lease ends here, acknowledged. If the segment runs on, the driver takes a new lease on its next write, and a
     * cancel request starts its count over. Records no cancel latency: this ends the lease, not the segment.
     */
    public void processesCancelled() {
        if (lease == null) {
            return;
        }
        releaseFrame();
        arbiter.confirmRelease(OWNER);
        boolean ok = verified();
        lease = null;
        end(ok ? Outcome.CANCELLED_CONFIRMED : Outcome.UNKNOWN, Cause.PROCESS_CANCEL, ok ? Counter.PROCESS_CANCELS : Counter.UNKNOWNS);
        clearStreak();
    }

    /** The segment finished or failed, after pathing cleared the keys. */
    public void segmentEnded(boolean finished) {
        if (lease == null) {
            return;
        }
        arbiter.confirmRelease(OWNER);
        boolean ok = verified();
        lease = null;
        if (!finished) {
            end(Outcome.FAILED, Cause.SEGMENT_END, Counter.FAILURES);
        } else {
            end(ok ? Outcome.COMPLETED_VERIFIED : Outcome.UNKNOWN, Cause.SEGMENT_END, ok ? Counter.COMPLETIONS : Counter.UNKNOWNS);
        }
        clearStreak();
    }

    /** The world changed under the driver. */
    public void worldChanged() {
        cancelConfirmed(Cause.WORLD_CHANGE);
    }

    // ---- reads ----

    /** Whether the driver's lease is alive, current and whole. */
    public boolean held() {
        return lease != null && lease.alive() && lease.epoch() == epoch && holdsAll(lease);
    }

    public boolean leased() {
        return lease != null;
    }

    /** The last command's outcome; null before the first request. */
    public Outcome state() {
        return state;
    }

    public Cause lastCause() {
        return lastCause;
    }

    public long count(Counter c) {
        return counts[c.ordinal()];
    }

    /** A copy of every counter, in {@link Counter} order. Allocates; for logs and tests. */
    public long[] snapshot() {
        return counts.clone();
    }

    // ---- inside ----

    /** Count one event. Public so the physics driver can count its checks. */
    public void bump(Counter c) {
        counts[c.ordinal()]++;
    }

    private static boolean holdsAll(InputArbiter.Lease l) {
        return l.holds(InputArbiter.Resource.MOVE) && l.holds(InputArbiter.Resource.JUMP) && l.holds(InputArbiter.Resource.SNEAK);
    }

    private boolean frameKey(Input in) {
        return in == Input.MOVE_FORWARD ? frameForward : in == Input.SPRINT ? frameSprint : in == Input.JUMP && frameJump;
    }

    private void releaseFrame() {
        if (frameForward) {
            keys.releaseRaw(Input.MOVE_FORWARD);
        }
        if (frameSprint) {
            keys.releaseRaw(Input.SPRINT);
        }
        if (frameJump) {
            keys.releaseRaw(Input.JUMP);
        }
    }

    /** None of the keys the driver pressed last is still down. */
    private boolean verifiedKeys() {
        return !(frameForward && keys.forcedRaw(Input.MOVE_FORWARD) || frameSprint && keys.forcedRaw(Input.SPRINT)
                || frameJump && keys.forcedRaw(Input.JUMP));
    }

    /** The arbiter shows the lease ended as a release, and none of the driver's keys is still down. */
    private boolean verified() {
        return lease != null && !lease.alive() && lease.end() == InputArbiter.End.RELEASED && verifiedKeys();
    }

    /** After a release: progress along the path with a verified release completes; anything else is unknown. */
    private void settle(int pos, Cause cause) {
        boolean ok = pos > startPosition && verified();
        lease = null;
        end(ok ? Outcome.COMPLETED_VERIFIED : Outcome.UNKNOWN, cause, ok ? Counter.COMPLETIONS : Counter.UNKNOWNS);
    }

    private void end(Outcome outcome, Cause cause, Counter counter) {
        state = outcome;
        lastCause = cause;
        bump(counter);
    }

    private void latency() {
        if (cancelRequestTick < 0) {
            return;
        }
        long l = tick - cancelRequestTick;
        counts[Counter.CANCEL_LATENCY_SUM.ordinal()] += l;
        if (l > counts[Counter.CANCEL_LATENCY_MAX.ordinal()]) {
            counts[Counter.CANCEL_LATENCY_MAX.ordinal()] = l;
        }
    }

    private void clearStreak() {
        cancelRequestTick = -1;
        lastRefusalTick = -1;
        refusalTicks = 0;
    }
}
