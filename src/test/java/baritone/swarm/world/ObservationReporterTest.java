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
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static baritone.swarm.world.FakeWorld.IRON_L;
import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c. The bot side: incremental reports (only what changed), G only for what a full scan no
 * longer sees, corrections first, chat kept to two entries a line every 15 s, and the rebuild dump.
 */
public class ObservationReporterTest {

    private static final long T0 = 1_000_000L;
    private static final String CHEST = "c:o:50,64,50";

    private ObservationReporter rep;
    private final List<ObservationMessage> sent = new ArrayList<>();
    private boolean accept = true;
    private final ObservationReporter.Sender sender = m -> {
        if (!accept) {
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

    private static List<String> keys(ObservationMessage m) {
        List<String> out = new ArrayList<>();
        for (ObservationMessage.Entry e : m.entries) {
            out.add(e.key);
        }
        return out;
    }

    private ObservationMessage last() {
        return sent.get(sent.size() - 1);
    }

    @Test
    public void newKeysGoOutTogetherOverTheSpoolAndRespectTheGap() {
        rep.observe(Arrays.asList(s(IRON_L, "iron_ore.4"), s(CHEST, "chest"), s("v:o:9,9,9:9,9,9", "coal_ore.1")),
                null, T0);
        assertTrue(rep.tick(T0, true, sender));
        assertEquals(ObservationMessage.Op.R, last().op);
        assertEquals(Arrays.asList(IRON_L, CHEST, "v:o:9,9,9:9,9,9"), keys(last()));
        assertEquals("w1", last().worldId);
        assertEquals(0, rep.pending());
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.3")), null, T0 + 100);
        assertFalse("inside the spool gap", rep.tick(T0 + ObservationReporter.SPOOL_GAP_MS - 1, true, sender));
        assertTrue(rep.tick(T0 + ObservationReporter.SPOOL_GAP_MS, true, sender));
        assertEquals("iron_ore.3", last().entries.get(0).detail);
        assertEquals(2, rep.sentMessages());
        assertEquals(4, rep.sentEntries());
    }

    @Test
    public void chatCarriesTwoEntriesALineEveryFifteenSeconds() {
        rep.observe(Arrays.asList(s(IRON_L, "iron_ore.4"), s(CHEST, "chest"), s("v:o:9,9,9:9,9,9", "coal_ore.1")),
                null, T0);
        assertTrue(rep.tick(T0, false, sender));
        assertEquals(2, last().entries.size());
        assertFalse(rep.tick(T0 + ObservationReporter.CHAT_GAP_MS - 1, false, sender));
        assertTrue(rep.tick(T0 + ObservationReporter.CHAT_GAP_MS, false, sender));
        assertEquals(Collections.singletonList("v:o:9,9,9:9,9,9"), keys(last()));
        assertEquals(0, rep.pending());
    }

    @Test
    public void unchangedFactsAreNotResentUntilTheRefreshIsDue() {
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0);
        rep.tick(T0, true, sender);
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0 + 60_000);
        assertEquals("no full-chunk resend", 0, rep.pending());
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0 + ObservationReporter.RESEND_MS);
        assertEquals(1, rep.pending());
    }

    @Test
    public void goneOnlyForKeysInsideAFullyScannedBox() {
        rep.observe(Arrays.asList(s(IRON_L, "iron_ore.4"), s(CHEST, "chest")), null, T0);
        rep.manual("w:o:base", "base", 80, 0, T0);
        rep.tick(T0, true, sender);
        rep.tick(T0 + 5000, true, sender);
        assertEquals(0, rep.pending());

        rep.observe(Collections.emptyList(), null, T0 + 6000);
        assertEquals("a partial scan never calls anything gone", 0, rep.pending());

        rep.observe(Collections.emptyList(), ReservationKey.region('o', -4, 8, -4, 4, 12, 4), T0 + 7000);
        assertEquals(1, rep.pending());
        assertTrue(rep.tick(T0 + 9000, true, sender));
        assertEquals(ObservationMessage.Op.G, last().op);
        assertEquals(Collections.singletonList(IRON_L), keys(last()));
        assertEquals("-", last().entries.get(0).detail);
        assertFalse(rep.reported(IRON_L));
        assertTrue("outside the box", rep.reported(CHEST));
        assertTrue("manual records are never scanned away", rep.reported("w:o:base"));
    }

    @Test
    public void insideNeedsTheWholeKeyInTheBoxAndTheSameDimension() {
        ReservationKey box = ReservationKey.region('o', -4, 8, -4, 4, 12, 4);
        assertTrue(ObservationReporter.inside(IRON_L, box));
        assertFalse(ObservationReporter.inside("v:o:0,10,0:5,11,1", box));
        assertFalse(ObservationReporter.inside("v:n:0,10,0:1,11,1", box));
        assertFalse(ObservationReporter.inside("b:o:0,10,0", box));
    }

    @Test
    public void correctionsGoBeforeOrdinaryChanges() {
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0);
        rep.tick(T0, true, sender);
        rep.observe(Collections.singletonList(s(CHEST, "chest")), null, T0 + 100);
        rep.correction(IRON_L, "v:o:0,10,0:1,11,0", "iron_ore.3", T0 + 200);
        assertEquals(1, rep.corrections());

        assertTrue(rep.tick(T0 + 3000, true, sender));
        assertEquals(ObservationMessage.Op.G, last().op);
        assertEquals(Collections.singletonList(IRON_L), keys(last()));
        assertEquals(ObservationReporter.VERIFIED_CONFIDENCE, last().entries.get(0).confidence);

        assertTrue(rep.tick(T0 + 6000, true, sender));
        assertEquals(ObservationMessage.Op.R, last().op);
        assertEquals(Arrays.asList("v:o:0,10,0:1,11,0", CHEST), keys(last()));
        assertEquals("iron_ore.3", last().entries.get(0).detail);
        assertEquals(0, rep.pending());
    }

    @Test
    public void sameBoxCorrectionIsOneReplacingReport() {
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0);
        rep.tick(T0, true, sender);
        rep.correction(IRON_L, IRON_L, "iron_ore.5", T0 + 100);
        assertEquals(1, rep.pending());
        assertTrue(rep.tick(T0 + 3000, true, sender));
        assertEquals(ObservationMessage.Op.R, last().op);
        assertEquals("iron_ore.5", last().entries.get(0).detail);
        assertTrue(rep.reported(IRON_L));
    }

    @Test
    public void goneCorrectionWithNothingLeft() {
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0);
        rep.tick(T0, true, sender);
        rep.correction(IRON_L, null, null, T0 + 100);
        assertEquals(1, rep.pending());
        rep.tick(T0 + 3000, true, sender);
        assertEquals(ObservationMessage.Op.G, last().op);
        assertFalse(rep.reported(IRON_L));
    }

    @Test
    public void aFailedSendKeepsEverything() {
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0);
        accept = false;
        assertFalse(rep.tick(T0, true, sender));
        assertEquals(1, rep.pending());
        assertEquals(0, rep.sentMessages());
        accept = true;
        assertTrue("a failure does not start the gap", rep.tick(T0 + 1, true, sender));
        assertEquals(0, rep.pending());
    }

    @Test
    public void manualRecordsCarryTheirAge() {
        rep.manual("b:o:1,2,3", "gold_ore", 40, 5000, T0);
        assertTrue(rep.tick(T0 + 1000, false, sender));
        ObservationMessage.Entry e = last().entries.get(0);
        assertEquals(6000, e.ageMs);
        assertEquals(40, e.confidence);
    }

    @Test
    public void rebuildDumpGoesNewestFirstThenEnd() {
        rep.observe(Collections.singletonList(s("v:o:1,1,1:1,1,1", "coal_ore.1")), null, T0);
        rep.observe(Collections.singletonList(s("v:o:2,2,2:2,2,2", "coal_ore.1")), null, T0 + 10);
        rep.observe(Collections.singletonList(s("v:o:3,3,3:3,3,3", "coal_ore.1")), null, T0 + 20);
        rep.correction("v:o:9,9,9:9,9,9", null, null, T0 + 30);
        rep.onQuery(true, T0 + 1000);
        assertEquals(1, rep.queries());
        assertTrue(rep.tick(T0 + 1000, true, sender));
        assertEquals(ObservationMessage.Op.R, last().op);
        assertEquals(Arrays.asList("v:o:3,3,3:3,3,3", "v:o:2,2,2:2,2,2", "v:o:1,1,1:1,1,1"), keys(last()));
        assertEquals(1000, last().entries.get(2).ageMs);
        assertTrue(rep.tick(T0 + 3000, true, sender));
        assertEquals(ObservationMessage.Op.E, last().op);
        assertTrue("the correction is still owed", rep.tick(T0 + 5000, true, sender));
        assertEquals(ObservationMessage.Op.G, last().op);
        assertEquals(0, rep.pending());
    }

    @Test
    public void rebuildOverChatSendsOnlyTheNewestTwo() {
        rep.observe(Collections.singletonList(s("v:o:1,1,1:1,1,1", "coal_ore.1")), null, T0);
        rep.observe(Collections.singletonList(s("v:o:2,2,2:2,2,2", "coal_ore.1")), null, T0 + 10);
        rep.observe(Collections.singletonList(s("v:o:3,3,3:3,3,3", "coal_ore.1")), null, T0 + 20);
        rep.onQuery(false, T0 + 1000);
        assertTrue(rep.tick(T0 + 1000, false, sender));
        assertEquals(Arrays.asList("v:o:3,3,3:3,3,3", "v:o:2,2,2:2,2,2"), keys(last()));
        assertTrue(rep.tick(T0 + 1000 + ObservationReporter.CHAT_GAP_MS, false, sender));
        assertEquals(ObservationMessage.Op.E, last().op);
        assertEquals("the dump replaced the pending reports", 0, rep.pending());
    }

    @Test
    public void worldChangeForgetsEverything() {
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0);
        assertFalse(rep.setWorld("w1"));
        assertTrue(rep.setWorld("w2"));
        assertEquals(0, rep.pending());
        assertEquals(0, rep.reportedSize());
        assertTrue(rep.setWorld(null));
        rep.observe(Collections.singletonList(s(IRON_L, "iron_ore.4")), null, T0);
        assertEquals("no world, no reports", 0, rep.pending());
        assertFalse(rep.tick(T0, true, sender));
    }

    @Test
    public void scanFindsVeinsAndContainersAsKeys() {
        FakeWorld w = FakeWorld.ironL().set(3, 10, 3, "minecraft:chest");
        ObservationReporter.Scan sc = ObservationReporter.scan(w, 'o', 0, 10, 0, 4, 2);
        assertNotNull("everything loaded", sc.box);
        assertEquals(2, sc.seen.size());
        assertEquals(IRON_L, sc.seen.get(0).key);
        assertEquals("iron_ore.4", sc.seen.get(0).detail);
        assertEquals("c:o:3,10,3", sc.seen.get(1).key);
        assertEquals("chest", sc.seen.get(1).detail);
        assertTrue(ObservationReporter.inside(IRON_L, sc.box));
    }

    @Test
    public void partlyUnloadedScanCoversNothing() {
        FakeWorld w = FakeWorld.ironL();
        w.loadedRadius = 3;
        ObservationReporter.Scan sc = ObservationReporter.scan(w, 'o', 0, 10, 0, 4, 2);
        assertNull(sc.box);
        assertEquals("what was seen is still reported", 1, sc.seen.size());

        FakeWorld edge = FakeWorld.ironL();
        edge.loadedRadius = 1; // The scan box is loaded but the vein's flood fill is not.
        ObservationReporter.Scan e = ObservationReporter.scan(edge, 'o', 0, 10, 0, 1, 1);
        assertNull(e.box);
        assertTrue(e.seen.isEmpty());
    }

    @Test
    public void scanStopsAtTheCapAndThenCoversNothing() {
        FakeWorld w = new FakeWorld();
        for (int x = -10; x <= 10; x += 2) {
            for (int z = -10; z <= 10; z += 2) {
                w.set(x, 10, z, "minecraft:diamond_ore");
            }
        }
        ObservationReporter.Scan sc = ObservationReporter.scan(w, 'o', 0, 10, 0, 10, 0);
        assertEquals(ObservationReporter.SCAN_MAX, sc.seen.size());
        assertNull(sc.box);
    }
}
