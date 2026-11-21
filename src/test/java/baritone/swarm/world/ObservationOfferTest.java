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

package baritone.swarm.world;

import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmRateLimiter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c. The "offered <op> #seq via chat|spool" line (owner, 20 Nov, gate row 12a) is log only: the same
 * messages leave in the same order at the same priority, refused offers stay refused, and the limiter ends in the same
 * state, with or without it.
 */
public class ObservationOfferTest {

    private static final long T0 = 1_000_000L;
    private static final long RUN = 1_700_000_000_000L;

    /** One scripted bot: a reporter, a real chat limiter, a link that queues into it, a log. */
    private static final class Bot {
        final ObservationReporter rep = new ObservationReporter(RUN);
        final SwarmRateLimiter lim = new SwarmRateLimiter(0.1, 1, false, 64, T0);
        final List<String> wire = new ArrayList<>();      // what left the limiter, in order
        final List<String> trace = new ArrayList<>();     // per tick: offer result and limiter state
        final List<String> log = new ArrayList<>();
        final List<String> calls = new ArrayList<>();     // every link.send call
        final Consumer<String> sink;
        boolean held;                                     // the script reached the gate and it held a report back

        Bot(boolean withLog) {
            assertTrue(rep.setWorld("w1"));
            sink = withLog ? log::add : null;
        }

        void tick(long now, boolean spool) {
            SwarmRateLimiter chat = spool ? null : lim;
            boolean r = rep.tick(now, spool, m -> ObservationOffer.offer(m, "Copper", chat, (to, body, p, d) -> {
                calls.add(to + " " + p + " " + body + (d == null ? "" : " deadline=" + d.expiresInMs()));
                return spool || lim.offer(p, body, to, d);
            }, sink, rep::withdrawn));
            String out;
            while ((out = lim.poll(now)) != null) {
                wire.add(out);
            }
            trace.add(r + " queued=" + lim.queued() + " sent=" + lim.sent() + " normal=" + lim.queued(SwarmPriority.NORMAL));
        }
    }

    /** The same script on two bots, one logging and one not. Chat ticks are a reporter chat gap apart. */
    private static void script(Bot b) {
        long gap = ObservationReporter.CHAT_GAP_MS + 1_000;
        long t = T0;
        b.rep.manual("b:o:1,150,1", "stone", 90, 0, t);
        b.tick(t, false);                                    // R #1 leaves (the one chat token)
        // Another NORMAL line waits on the chat link: the next report is held back by the gate.
        b.lim.offer(SwarmPriority.NORMAL, "filler", "Spelunker", null);
        b.rep.manual("b:o:2,150,2", "dirt", 90, 0, t + 1_000);
        int before = b.calls.size();
        b.tick(t += gap, false);
        b.held = b.calls.size() == before && b.trace.get(b.trace.size() - 1).startsWith("false");
        b.rep.correction("b:o:1,150,1", null, null, t + 1_000);
        for (int i = 0; i < 4; i++) {
            b.tick(t += gap, false);
        }
        // A lower-priority line does not hold it back.
        b.lim.offer(SwarmPriority.LOW, "status", "Spelunker", null);
        b.rep.manual("b:o:3,150,3", "gravel", 90, 0, t + 1_000);
        b.tick(t += gap, false);
        // Over the spool.
        b.rep.correction("b:o:2,150,2", null, null, t + 1_000);
        b.tick(t += ObservationReporter.SPOOL_GAP_MS + 1_000, true);
        for (int i = 0; i < 3; i++) {
            b.tick(t += gap, false);
        }
    }

    @Test
    public void theLogLineChangesNothingElse() {
        Bot logged = new Bot(true);
        Bot silent = new Bot(false);
        script(logged);
        script(silent);
        assertEquals(silent.calls, logged.calls);
        assertEquals(silent.wire, logged.wire);
        assertEquals(silent.trace, logged.trace);
        assertTrue(silent.log.isEmpty());
        assertFalse("the script hands messages over", logged.calls.isEmpty());
        assertTrue("the script reaches the gate and it holds a report back", silent.held);
        assertTrue(logged.held);
        assertTrue("several hand-overs, chat and spool", logged.calls.size() >= 4);
        assertEquals("one line per accepted hand-over", logged.calls.size(), logged.log.size());
        for (String c : logged.calls) {
            assertTrue(c, c.startsWith("Copper NORMAL "));
        }
    }

    @Test
    public void theLineNamesTheMessageAndThePath() {
        Bot b = new Bot(true);
        b.rep.manual("b:o:1,150,1", "stone", 90, 0, T0);
        b.tick(T0, false);
        b.rep.correction("b:o:1,150,1", null, null, T0 + 1_000);
        b.tick(T0 + 1_000 + ObservationReporter.SPOOL_GAP_MS, true);
        assertEquals(2, b.log.size());
        assertEquals("swarm-obs: offered R #1 run " + RUN + " via chat (1 entries)", b.log.get(0));
        assertEquals("swarm-obs: offered G #2 run " + RUN + " via spool (1 entries)", b.log.get(1));
    }

    @Test
    public void noLineWhenTheLinkRefusesOrTheGateHolds() {
        List<String> log = new ArrayList<>();
        ObservationMessage m = new ObservationMessage(ObservationMessage.Op.R, "w1", new ArrayList<>(), RUN, 1);
        assertFalse(ObservationOffer.offer(m, "Copper", null, (to, body, p, d) -> false, log::add, s -> { }));
        SwarmRateLimiter lim = new SwarmRateLimiter(0.1, 1, false, 64, T0);
        lim.offer(SwarmPriority.HIGH, "ack", "Copper", null);
        List<String> calls = new ArrayList<>();
        assertFalse(ObservationOffer.offer(m, "Copper", lim, (to, body, p, d) -> calls.add(body), log::add, s -> { }));
        assertTrue("held back before the link is called", calls.isEmpty());
        assertTrue(log.isEmpty());
    }
}
