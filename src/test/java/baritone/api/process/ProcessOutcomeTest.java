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

package baritone.api.process;

import baritone.api.process.ProcessOutcome.Reason;
import baritone.api.process.ProcessOutcome.State;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProcessOutcomeTest {

    @Test
    public void noRequestIsTerminalAndNotSuccess() {
        assertEquals(0, ProcessOutcome.NONE.request());
        assertTrue(ProcessOutcome.NONE.terminal());
        assertFalse(ProcessOutcome.NONE.succeeded());
        // a stray end before any request changes nothing
        assertSame(ProcessOutcome.NONE, ProcessOutcome.NONE.end(State.SUCCEEDED, Reason.NONE));
    }

    @Test
    public void lifecycle() {
        ProcessOutcome a = ProcessOutcome.NONE.accept();
        assertEquals(1, a.request());
        assertEquals(State.ACCEPTED, a.state());
        assertFalse(a.terminal());
        ProcessOutcome r = a.running();
        assertEquals(State.RUNNING, r.state());
        assertSame(r, r.running());
        ProcessOutcome done = r.end(State.SUCCEEDED, Reason.NONE);
        assertTrue(done.succeeded());
        assertEquals(1, done.request());
    }

    @Test
    public void firstResultSticks() {
        ProcessOutcome failed = ProcessOutcome.NONE.accept().running().end(State.FAILED, Reason.TIMEOUT);
        // the onLostControl that follows a failure must not turn it into CANCELLED, and nothing turns it into success
        assertSame(failed, failed.end(State.CANCELLED, Reason.OWNERSHIP_LOST));
        assertSame(failed, failed.end(State.SUCCEEDED, Reason.NONE));
        assertSame(failed, failed.running());
        assertEquals(Reason.TIMEOUT, failed.reason());
    }

    @Test
    public void newRequestDoesNotInheritTheOldResult() {
        ProcessOutcome first = ProcessOutcome.NONE.accept().end(State.SUCCEEDED, Reason.NONE);
        ProcessOutcome second = first.accept();
        assertEquals(2, second.request());
        assertEquals(State.ACCEPTED, second.state());
        assertFalse(second.succeeded());
        assertEquals(Reason.NONE, second.reason());
        // a caller holding request 1 can tell the current outcome is not its own
        assertNotEquals(first.request(), second.request());
    }

    @Test
    public void lostControlBeforeAResult() {
        ProcessOutcome running = ProcessOutcome.NONE.accept().running();
        assertEquals(State.CANCELLED, running.end(State.CANCELLED, Reason.OWNERSHIP_LOST).state());
        ProcessOutcome unknown = running.end(State.UNKNOWN, Reason.OWNERSHIP_LOST);
        assertTrue(unknown.terminal());
        assertFalse(unknown.succeeded());
    }

    @Test(expected = IllegalArgumentException.class)
    public void endNeedsATerminalState() {
        ProcessOutcome.NONE.accept().end(State.RUNNING, Reason.NONE);
    }

    @Test
    public void terminalStates() {
        for (State s : State.values()) {
            boolean expected = s == State.SUCCEEDED || s == State.FAILED || s == State.CANCELLED || s == State.UNKNOWN;
            assertEquals(s.name(), expected, s.terminal());
        }
    }
}
