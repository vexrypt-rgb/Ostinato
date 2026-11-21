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

import baritone.swarm.lease.ReservationKey;
import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmRateLimiter;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c, from the p2ch live gate (17 Nov). (1) A bot's scan caught a vein half-mined, the bot emptied it
 * and left before scanning again, and that half-mined report stayed in the shared store and was picked again. A mine
 * that finds its leased box empty now withdraws the bot's own reports inside it. (2) A report's age was read when the
 * frame was queued, so a frame that waited ~24 s in the chat queue looked 24 s newer. Reports now wait for a free link
 * and ages are read when they leave; dump parts add the time they waited.
 */
public class ReportTimingAndMinedOutTest {

    private static final long T0 = 1_000_000L;
    /** The leased 3-block vein of the live gate, and the 2-block state a scan saw mid-mine. */
    private static final String VEIN = "v:o:330,150,300:331,151,300";
    private static final String HALF = "v:o:330,150,300:331,150,300";

    private ObservationReporter rep;
    private final List<ObservationMessage> sent = new ArrayList<>();
    private boolean linkFree = true;
    private final ObservationReporter.Sender sender = m -> {
        if (!linkFree) {
            return false;
        }
        sent.add(m);
        return true;
    };

    @Before
    public void setUp() {
        rep = new ObservationReporter();
        assertTrue(rep.setWorld("w1"));
    }

    private static ObservationReporter.Seen s(String key, String detail) {
        return new ObservationReporter.Seen(key, detail, ObservationReporter.SCAN_CONFIDENCE);
    }

    private ObservationMessage last() {
        return sent.get(sent.size() - 1);
    }

    private static List<String> keys(ObservationMessage m) {
        List<String> out = new ArrayList<>();
        for (ObservationMessage.Entry e : m.entries) {
            out.add(e.key);
        }
        return out;
    }

    private static WorldKnowledge store(AtomicLong mono) {
        WorldKnowledge k = new WorldKnowledge(mono::get, x -> {}, WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
        assertTrue(k.setWorld("w1"));
        k.startRebuild(Collections.emptyList());
        return k;
    }

    @Test
    public void aMinedOutVeinWithdrawsTheBotsOwnHalfMinedReport() {
        rep.observe(Collections.singletonList(s(HALF, "emerald_ore.2")), null, T0); // the scan mid-mine
        assertTrue(rep.tick(T0, true, sender));
        assertTrue(rep.reported(HALF));

        rep.minedOut(ReservationKey.parse(VEIN), new HashSet<>(Collections.singletonList("emerald_ore")), T0 + 1_000);
        assertFalse("no longer reported", rep.reported(HALF));
        assertTrue(rep.tick(T0 + ObservationReporter.SPOOL_GAP_MS, true, sender));
        assertEquals(ObservationMessage.Op.G, last().op);
        assertTrue("the leased key and the half-mined one", keys(last()).containsAll(Arrays.asList(VEIN, HALF)));
        for (ObservationMessage.Entry e : last().entries) {
            assertEquals("a verified fact", ObservationReporter.VERIFIED_CONFIDENCE, e.confidence);
        }
        assertEquals(2, rep.corrections());
    }

    @Test
    public void otherOresAndVeinsOutsideTheBoxAreKept() {
        String coalInside = "v:o:330,150,300:330,150,300";
        String emeraldOutside = "v:o:330,150,310:330,151,310";
        rep.observe(Arrays.asList(s(coalInside, "coal_ore.1"), s(emeraldOutside, "emerald_ore.2"),
                s("v:o:331,150,300:332,150,300", "emerald_ore.2")), null, T0); // crosses the box edge
        assertTrue(rep.tick(T0, true, sender));
        rep.minedOut(ReservationKey.parse(VEIN), new HashSet<>(Collections.singletonList("emerald_ore")), T0 + 1_000);
        assertTrue("other ore", rep.reported(coalInside));
        assertTrue("outside the box", rep.reported(emeraldOutside));
        assertTrue("not wholly inside: ore may remain outside the box", rep.reported("v:o:331,150,300:332,150,300"));
        assertTrue(rep.tick(T0 + ObservationReporter.SPOOL_GAP_MS, true, sender));
        assertEquals(Collections.singletonList(VEIN), keys(last()));
    }

    @Test
    public void theCoordinatorStopsOfferingTheHalfMinedVein() {
        AtomicLong mono = new AtomicLong(5_000_000L);
        WorldKnowledge k = store(mono);
        k.apply("Spelunker", new ObservationMessage(ObservationMessage.Op.R, "w1",
                Collections.singletonList(new ObservationMessage.Entry(VEIN, "emerald_ore.3", 0, 90))));
        rep.observe(Collections.singletonList(s(HALF, "emerald_ore.2")), null, T0);
        assertTrue(rep.tick(T0, true, sender));
        k.apply("Azalea", last());
        assertNotNull("picked before the fix", k.pick("emerald_ore", 'o', key -> false).record);

        rep.minedOut(ReservationKey.parse(VEIN), new HashSet<>(Collections.singletonList("emerald_ore")), T0 + 1_000);
        assertTrue(rep.tick(T0 + ObservationReporter.SPOOL_GAP_MS, true, sender));
        mono.addAndGet(2_000);
        k.apply("Azalea", last());
        assertTrue(k.get(HALF).gone());
        assertTrue(k.get(VEIN).gone());
        assertNull("nothing left to offer", k.pick("emerald_ore", 'o', key -> false).record);
    }

    @Test
    public void aReportThatWaitsForTheLinkLeavesWithItsTrueAge() {
        rep.observe(Collections.singletonList(s(HALF, "emerald_ore.2")), null, T0);
        linkFree = false; // lines are queued ahead on the chat link
        assertFalse(rep.tick(T0, false, sender));
        assertFalse(rep.tick(T0 + ObservationReporter.CHAT_GAP_MS, false, sender));
        assertEquals("still pending, not built", 1, rep.pending());
        linkFree = true;
        long out = T0 + 24_000;
        assertTrue(rep.tick(out, false, sender));
        assertEquals("age as of leaving", 24_000, last().entries.get(0).ageMs);

        AtomicLong mono = new AtomicLong(9_000_000L); // the coordinator's clock when it arrives
        WorldKnowledge k = store(mono);
        k.apply("Azalea", last());
        assertEquals("observed when the scan ran, not when the line left", mono.get() - 24_000, k.get(HALF).observedAt);
    }

    @Test
    public void aDumpPartThatWaitsAddsTheWait() {
        rep.observe(Collections.singletonList(s(HALF, "emerald_ore.2")), null, T0);
        assertTrue(rep.tick(T0, true, sender));
        rep.onQuery(false, T0 + 5_000); // ages read at 5 s
        linkFree = false;
        assertFalse(rep.tick(T0 + 20_000, false, sender));
        linkFree = true;
        assertTrue(rep.tick(T0 + 25_000, false, sender));
        assertEquals(ObservationMessage.Op.R, last().op);
        assertEquals("5 s at the query plus 20 s waiting", 25_000, last().entries.get(0).ageMs);
    }

    @Test
    public void theLimiterCountsWhatANewLineWouldWaitBehind() {
        SwarmRateLimiter lim = new SwarmRateLimiter(0.5, 1, false, 32, 0L);
        assertEquals(0, lim.queuedAtOrAbove(SwarmPriority.NORMAL));
        lim.offer(SwarmPriority.NORMAL, "a", "Copper", null);
        lim.offer(SwarmPriority.NORMAL, "b", "Copper", null);
        lim.offer(SwarmPriority.HIGH, "c", "Copper", null);
        lim.offer(SwarmPriority.LOW, "d", "Copper", null);
        assertEquals("high and normal, not the bulk line", 3, lim.queuedAtOrAbove(SwarmPriority.NORMAL));
        assertEquals("a gone report waits only behind urgent and high", 1, lim.queuedAtOrAbove(SwarmPriority.HIGH));
    }
}
