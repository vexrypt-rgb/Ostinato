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

package baritone.process;

import baritone.api.process.ProcessOutcome;

/**
 * Ostinato: the outcome of mine requests. A request succeeds only if it broke at least one target block; one that
 * ended itself having broken none failed (nothing found, nothing reachable); one whose control was taken away first
 * was cancelled. The release gate (17 Nov) found a mine with nothing to mine reported "succeeded (verified)" because
 * the swarm executor took a process going idle as success.
 */
final class MineOutcome {

    private ProcessOutcome outcome = ProcessOutcome.NONE;
    private int mined;
    private ProcessOutcome.Reason endReason;

    ProcessOutcome outcome() {
        return outcome;
    }

    int mined() {
        return mined;
    }

    /** A new request; closes the previous one first. */
    void start() {
        finish();
        outcome = outcome.accept();
        mined = 0;
        endReason = null;
    }

    void running() {
        outcome = outcome.running();
    }

    /** One target block of this request was broken. */
    void minedOne() {
        mined++;
    }

    /** The mine is ending itself for {@code why} ({@link ProcessOutcome.Reason#NONE}: its goal was met). */
    void endFor(ProcessOutcome.Reason why) {
        endReason = why;
    }

    /** Closes the running request, if any. */
    void finish() {
        if (outcome.request() == 0 || outcome.terminal()) {
            return;
        }
        if (endReason == null) {
            outcome = outcome.end(ProcessOutcome.State.CANCELLED, ProcessOutcome.Reason.CANCELLED);
        } else if (mined > 0) {
            outcome = outcome.end(ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE);
        } else {
            outcome = outcome.end(ProcessOutcome.State.FAILED,
                    endReason == ProcessOutcome.Reason.NONE ? ProcessOutcome.Reason.MISSING_RESOURCE : endReason);
        }
        endReason = null;
    }
}
