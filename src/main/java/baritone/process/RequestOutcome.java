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
 * Ostinato: the outcome of one process's requests, by the rule mine requests set (release gate, 17 Nov): a request
 * succeeds only if it did at least one verified unit of work (a target block broken, a crop harvested or planted, a
 * chunk explored, a goal reached, a build left with nothing wrong that had something wrong); one that ended itself with
 * none failed; one whose control was taken away first was cancelled. A process going idle is never a success by
 * itself. Thread-safe: the swarm executor reads {@link #outcome()} while the process ticks.
 */
class RequestOutcome {

    private ProcessOutcome outcome = ProcessOutcome.NONE;
    private int done;
    private ProcessOutcome.Reason endReason;

    synchronized ProcessOutcome outcome() {
        return outcome;
    }

    synchronized int done() {
        return done;
    }

    /** A new request; closes the previous one first. */
    synchronized void start() {
        finish();
        outcome = outcome.accept();
        done = 0;
        endReason = null;
    }

    synchronized void running() {
        outcome = outcome.running();
    }

    /** Whether a request is open (accepted or running, not yet ended). */
    synchronized boolean open() {
        return outcome.request() != 0 && !outcome.terminal();
    }

    /** One verified unit of this request's work was done. */
    synchronized void didOne() {
        if (open()) {
            done++;
        }
    }

    /** The process is ending this request itself for {@code why} ({@link ProcessOutcome.Reason#NONE}: its goal was met). */
    synchronized void endFor(ProcessOutcome.Reason why) {
        endReason = why;
    }

    /** Ends the open request now for {@code why} (the process keeps running; a later finish keeps this result). */
    synchronized void endNow(ProcessOutcome.Reason why) {
        endFor(why);
        finish();
    }

    /** Closes the running request, if any. */
    synchronized void finish() {
        if (!open()) {
            return;
        }
        if (endReason == null) {
            outcome = outcome.end(ProcessOutcome.State.CANCELLED, ProcessOutcome.Reason.CANCELLED);
        } else if (done > 0) {
            outcome = outcome.end(ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE);
        } else {
            outcome = outcome.end(ProcessOutcome.State.FAILED,
                    endReason == ProcessOutcome.Reason.NONE ? ProcessOutcome.Reason.MISSING_RESOURCE : endReason);
        }
        endReason = null;
    }
}
