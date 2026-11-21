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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c, from the p2ch live gate run 7 (20 Nov). Azalea's full-vein report R #8 was offered over chat at
 * 20:01:19 and reached the coordinator at 20:01:41: higher-priority lines kept overtaking it, and its entry age was
 * read when the frame was built, so it looked 22 s newer than it was. It beat Spelunker's real "gone", then Azalea's
 * own later gone report G #9 lost to it under newer-wins. Two fixes: a bot's later message always replaces its own
 * record; and a chat frame still queued after {@link ObservationOffer#CHAT_DEADLINE_MS} is withdrawn and its entries
 * re-queued with their own observation times.
 */
public class ChatAgeBoundTest {

    private static final String VEIN = "v:o:330,150,300:331,151,300";
    private static final long T0 = 10_000_000L;
    private static final long AZALEA_RUN = 1_795_229_462_679L;
    private static final long SPELUNKER_RUN = 1_795_229_400_000L;

    private final AtomicLong mono = new AtomicLong(T0);
    private final List<String> log = new ArrayList<>();

    private WorldKnowledge store() {
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add, WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
        assertTrue(k.setWorld("w1"));
        k.startRebuild(Collections.emptyList());
        return k;
    }

    private static ObservationMessage msg(ObservationMessage.Op op, String detail, long ageMs, int conf, long run, long seq) {
        List<ObservationMessage.Entry> e = new ArrayList<>();
        e.add(new ObservationMessage.Entry(VEIN, detail, ageMs, conf));
        return new ObservationMessage(op, "w1", e, run, seq);
    }

    private void at(long t) {
        mono.set(t);
    }

    // ---- part 1: a bot's later message replaces its own record -----------------------------------------------

    @Test
    public void aBotsOwnLaterGoneReplacesItsInflatedReport() {
        WorldKnowledge k = store();
        at(T0 - 5_000);
        k.apply("Spelunker", msg(ObservationMessage.Op.R, "emerald_ore.3", 0, 90, SPELUNKER_RUN, 1));
        at(T0 + 10_000);
        k.apply("Spelunker", msg(ObservationMessage.Op.G, "-", 0, 90, SPELUNKER_RUN, 2));
        // Azalea's R #8: seen at T0, built at T0+8 s with age 8 s, waited 22 s in the chat queue.
        at(T0 + 30_000);
        k.apply("Azalea", msg(ObservationMessage.Op.R, "emerald_ore.3", 8_000, 90, AZALEA_RUN, 8));
        assertFalse("the inflated report beats the other bot's gone (part 2 bounds this)", k.get(VEIN).gone());
        // Azalea's own G #9, stamped when the vein was mined out (T0+11 s), arrives later with its honest age.
        at(T0 + 38_000);
        k.apply("Azalea", msg(ObservationMessage.Op.G, "-", 27_000, 100, AZALEA_RUN, 9));
        ObservationRecord r = k.get(VEIN);
        assertTrue("Azalea's later message wins over its own record", r.gone());
        assertEquals("Azalea", r.bot);
        assertEquals("Spelunker's own gone over its report, then Azalea's", 2, k.ownReplaced());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("swarm-obs: conflict " + VEIN + " winner=Azalea (-) loser=Azalea")
                && l.contains("rule=later-message run " + AZALEA_RUN + " #9 over #8")));
    }

    @Test
    public void unnumberedMessagesKeepNewerWins() {
        WorldKnowledge k = store();
        at(T0 + 30_000);
        k.apply("Azalea", new ObservationMessage(ObservationMessage.Op.R, "w1",
                Collections.singletonList(new ObservationMessage.Entry(VEIN, "emerald_ore.3", 0, 90))));
        at(T0 + 38_000);
        k.apply("Azalea", new ObservationMessage(ObservationMessage.Op.G, "w1",
                Collections.singletonList(new ObservationMessage.Entry(VEIN, "-", 27_000, 100))));
        assertFalse("v1 has no order, so the timestamps still decide", k.get(VEIN).gone());
        assertEquals(0, k.ownReplaced());
    }

    // ---- part 2: a chat frame that waits too long is withdrawn and its entries re-queued --------------------------

    /** Azalea's reporter on a real chat limiter, delivering what leaves the limiter to the coordinator's store. */
    private final class AzaleaOnChat {
        final ObservationReporter rep = new ObservationReporter(AZALEA_RUN);
        final SwarmRateLimiter lim = new SwarmRateLimiter(0.5, 1, false, 64, T0);
        final WorldKnowledge k;
        final List<ObservationMessage> arrived = new ArrayList<>();
        boolean busy; // a HIGH line is offered every second (pongs, acks, lease traffic)

        AzaleaOnChat(WorldKnowledge k) {
            this.k = k;
            assertTrue(rep.setWorld("w1"));
        }

        void tick(long now) {
            rep.tick(now, false, m -> ObservationOffer.offer(m, "Copper", lim,
                    (to, body, p, d) -> lim.offer(p, body, to, d), null, rep::withdrawn));
        }

        void second(long now) {
            if (busy) {
                lim.offer(SwarmPriority.HIGH, "pong@" + now, "Copper", null);
            }
            String out = lim.poll(now);
            if (out != null && !out.startsWith("pong@") && !out.equals("spent")) {
                ObservationMessage m = ObservationMessage.decode(out);
                assertNotNull(out, m);
                at(now);
                k.apply("Azalea", m);
                arrived.add(m);
            }
            tick(now);
        }

        /** Spend the one token so the next line waits for a refill. */
        void spendToken(long now) {
            lim.offer(SwarmPriority.LOW, "spent", "Copper", null);
            assertEquals("spent", lim.poll(now));
        }
    }

    @Test
    public void aReportThatWouldWaitTooLongLeavesLaterWithItsOwnTimeAndLosesToTheRealGone() {
        WorldKnowledge k = store();
        at(T0 - 5_000);
        k.apply("Spelunker", msg(ObservationMessage.Op.R, "emerald_ore.3", 0, 90, SPELUNKER_RUN, 1));
        AzaleaOnChat a = new AzaleaOnChat(k);
        a.rep.manual(VEIN, "emerald_ore.3", 90, 0, T0); // Azalea sees the full vein at T0
        a.spendToken(T0);
        a.tick(T0);                                         // R #1 offered: nothing NORMAL+ queued, no token
        a.busy = true;
        for (long t = T0 + 1_000; t <= T0 + 22_000; t += 1_000) {
            if (t == T0 + 11_000) {
                at(t);
                k.apply("Spelunker", msg(ObservationMessage.Op.G, "-", 0, 90, SPELUNKER_RUN, 2)); // mined out
            }
            a.second(t);
        }
        a.busy = false;
        for (long t = T0 + 23_000; t <= T0 + 60_000; t += 1_000) {
            a.second(t);
        }
        assertEquals("the waiting frame was taken back once", 1, a.lim.withdrawnExpired());
        assertEquals(1, a.rep.requeued());
        assertEquals("the report left once, re-numbered", 1, a.arrived.size());
        assertEquals(2, a.arrived.get(0).seq);
        ObservationRecord r = k.get(VEIN);
        assertTrue("the report carries its own time and loses to Spelunker's later gone", r.gone());
        assertEquals("Spelunker", r.bot);
    }

    @Test
    public void aWithdrawnReportIsNotSentAgainOnceTheBotsOwnGoneWent() {
        WorldKnowledge k = store();
        AzaleaOnChat a = new AzaleaOnChat(k);
        a.rep.manual(VEIN, "emerald_ore.3", 90, 0, T0);
        a.spendToken(T0);
        a.tick(T0);                                         // R #1 waits in the chat queue
        a.busy = true;
        for (long t = T0 + 1_000; t <= T0 + 3_000; t += 1_000) {
            a.second(t);
        }
        // The spool comes back: Azalea's gone G #2 goes there and is applied while R #1 still waits on chat.
        a.rep.correction(VEIN, null, null, T0 + 3_500);
        List<ObservationMessage> spool = new ArrayList<>();
        assertTrue(a.rep.tick(T0 + 3_500 + ObservationReporter.SPOOL_GAP_MS, true, m -> {
            at(T0 + 5_600);
            k.apply("Azalea", m);
            spool.add(m);
            return true;
        }));
        assertEquals(ObservationMessage.Op.G, spool.get(0).op);
        assertEquals(2, spool.get(0).seq);
        for (long t = T0 + 6_000; t <= T0 + 60_000; t += 1_000) {
            if (t == T0 + 20_000) {
                a.busy = false;
            }
            a.second(t);
        }
        assertEquals(1, a.lim.withdrawnExpired());
        assertEquals("R #1 is no longer the bot's word on the vein: not re-queued", 0, a.rep.requeued());
        assertTrue("nothing else reached the coordinator over chat", a.arrived.isEmpty());
        assertTrue(k.get(VEIN).gone());
    }

    @Test
    public void aFrameThatHasStartedToLeaveIsNotWithdrawn() {
        SwarmRateLimiter lim = new SwarmRateLimiter(0.5, 1, false, 64, T0);
        List<String> withdrawn = new ArrayList<>();
        SwarmRateLimiter.Deadline d = new SwarmRateLimiter.Deadline(5_000, "obs R #1", () -> withdrawn.add("x"));
        lim.offer(SwarmPriority.NORMAL, "part1", "Copper", d);
        lim.offer(SwarmPriority.NORMAL, "part2", "Copper", d);
        assertEquals("part1", lim.poll(T0));
        for (long t = T0 + 1_000; t <= T0 + 8_000; t += 1_000) {
            lim.offer(SwarmPriority.HIGH, "pong@" + t, "Copper", null);
            lim.poll(t);
        }
        assertTrue("no half frames", withdrawn.isEmpty());
        assertEquals(0, lim.withdrawnExpired());
    }
}
