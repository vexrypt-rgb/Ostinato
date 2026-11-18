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

import baritone.swarm.lease.CoordinatorStore;
import baritone.swarm.lease.ReservationKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c, from the p2ch live gate run 4 (17 Nov). Azalea's report of vein A, queued before its own gone
 * report for that vein, was overtaken on the chat link and reached the coordinator second; with "newer wins" it
 * brought back a vein the server had already confirmed gone. Each observation message now carries the bot's run
 * (epoch) and a number (seq); the coordinator applies a bot's messages only in that order, and keeps the last one
 * durably so the rule holds across a coordinator relaunch.
 */
public class MessageOrderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String VEIN = "v:o:330,150,300:331,151,300";
    private static final long T0 = 1_000_000L;
    private static final long RUN1 = 1_700_000_000_000L;

    private final AtomicLong mono = new AtomicLong(5_000_000L);
    private final List<String> log = new ArrayList<>();

    private WorldKnowledge store() {
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add, WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
        assertTrue(k.setWorld("w1"));
        k.startRebuild(Collections.emptyList());
        return k;
    }

    /** The bot's messages in the order it sent them (what the coordinator would receive without a reorder). */
    private static List<ObservationMessage> sendAll(ObservationReporter rep, long from, int ticks) {
        List<ObservationMessage> sent = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            rep.tick(from + i * ObservationReporter.SPOOL_GAP_MS, true, m -> {
                sent.add(m);
                return true;
            });
        }
        return sent;
    }

    /** Run 4's sequence: a full-vein report while walking up, then the bot's own gone report after the mine. */
    private static List<ObservationMessage> reportThenGone(long epoch) {
        ObservationReporter rep = new ObservationReporter(epoch);
        assertTrue(rep.setWorld("w1"));
        rep.observe(Collections.singletonList(new ObservationReporter.Seen(VEIN, "emerald_ore.3",
                ObservationReporter.SCAN_CONFIDENCE)), null, T0);
        List<ObservationMessage> first = sendAll(rep, T0, 1);
        rep.minedOut(ReservationKey.parse(VEIN), new HashSet<>(Collections.singletonList("emerald_ore")), T0 + 10_000);
        first.addAll(sendAll(rep, T0 + 10_000, 1));
        assertEquals(2, first.size());
        return first;
    }

    @Test
    public void theBotNumbersItsMessagesInSendOrder() {
        List<ObservationMessage> m = reportThenGone(RUN1);
        assertEquals(ObservationMessage.Op.R, m.get(0).op);
        assertEquals(ObservationMessage.Op.G, m.get(1).op);
        assertEquals(RUN1, m.get(0).epoch);
        assertEquals(1, m.get(0).seq);
        assertEquals(2, m.get(1).seq);
    }

    @Test
    public void aRefusedSendDoesNotUseUpANumber() {
        ObservationReporter rep = new ObservationReporter(RUN1);
        assertTrue(rep.setWorld("w1"));
        rep.manual("b:o:1,2,3", "stone", 90, 0, T0);
        assertFalse(rep.tick(T0, false, m -> false)); // link busy
        List<ObservationMessage> sent = sendAll(rep, T0 + ObservationReporter.CHAT_GAP_MS, 1);
        assertEquals(1, sent.get(0).seq);
    }

    @Test
    public void anOvertakenReportIsDroppedAndTheVeinStaysGone() {
        List<ObservationMessage> m = reportThenGone(RUN1);
        WorldKnowledge k = store();
        k.apply("Azalea", m.get(1)); // the gone report arrives first
        mono.addAndGet(20_000);
        assertEquals(Collections.singletonList(WorldKnowledge.Outcome.OUT_OF_ORDER), k.apply("Azalea", m.get(0)));
        assertTrue("still gone", k.get(VEIN).gone());
        assertNull(k.pick("emerald_ore", 'o', key -> false).record);
        assertEquals(1, k.outOfOrder());
    }

    @Test
    public void aDuplicateIsDropped() {
        List<ObservationMessage> m = reportThenGone(RUN1);
        WorldKnowledge k = store();
        k.apply("Azalea", m.get(0));
        assertEquals(Collections.singletonList(WorldKnowledge.Outcome.OUT_OF_ORDER), k.apply("Azalea", m.get(0)));
        assertEquals(1, k.outOfOrder());
    }

    @Test
    public void aRelaunchedBotStartsANewRunAndItsOldRunIsDropped() {
        List<ObservationMessage> run1 = reportThenGone(RUN1);
        WorldKnowledge k = store();
        k.apply("Azalea", run1.get(0));
        // The bot relaunches: its counter starts at 1 again, under a later run.
        ObservationReporter rep2 = new ObservationReporter(RUN1 + 60_000);
        assertTrue(rep2.setWorld("w1"));
        rep2.manual("b:o:9,9,9", "dirt", 90, 0, T0 + 60_000);
        List<ObservationMessage> run2 = sendAll(rep2, T0 + 60_000, 1);
        assertEquals(1, run2.get(0).seq);
        assertEquals(Collections.singletonList(WorldKnowledge.Outcome.NEW), k.apply("Azalea", run2.get(0)));
        // A message of the first run that was still on its way arrives now: dropped.
        assertEquals(Collections.singletonList(WorldKnowledge.Outcome.OUT_OF_ORDER), k.apply("Azalea", run1.get(1)));
        assertFalse("the late gone report of the old run did not apply", k.get(VEIN).gone());
    }

    @Test
    public void otherBotsKeepTheirOwnOrder() {
        WorldKnowledge k = store();
        ObservationMessage spelunker = new ObservationMessage(ObservationMessage.Op.R, "w1",
                Collections.singletonList(new ObservationMessage.Entry("b:o:1,1,1", "stone", 0, 90)), RUN1, 1);
        ObservationMessage azalea = new ObservationMessage(ObservationMessage.Op.R, "w1",
                Collections.singletonList(new ObservationMessage.Entry("b:o:2,2,2", "stone", 0, 90)), RUN1, 1);
        assertEquals(WorldKnowledge.Outcome.NEW, k.apply("Spelunker", spelunker).get(0));
        assertEquals("same numbers from another bot are fine", WorldKnowledge.Outcome.NEW, k.apply("Azalea", azalea).get(0));
    }

    @Test
    public void theMarkSurvivesACoordinatorRelaunch() throws Exception {
        List<ObservationMessage> m = reportThenGone(RUN1);
        Path file = tmp.getRoot().toPath().resolve("coordinator.state");
        CoordinatorStore cs = new CoordinatorStore(file);
        WorldKnowledge before = store();
        before.setMarkListener((bot, epoch, seq) -> {
            try {
                cs.save(Collections.emptyMap(), Collections.emptyList(), before.marks());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        before.apply("Azalea", m.get(1)); // the gone report, then the coordinator is killed

        Map<String, long[]> loaded = cs.load().obsMarks;
        assertArrayEquals(new long[]{RUN1, 2}, loaded.get("azalea"));
        WorldKnowledge after = store(); // relaunched coordinator, empty store
        after.restoreMarks(loaded);
        assertEquals("the overtaken report is still dropped after the relaunch",
                Collections.singletonList(WorldKnowledge.Outcome.OUT_OF_ORDER), after.apply("Azalea", m.get(0)));
        assertNull(after.get(VEIN));
    }

    @Test
    public void aV1StoreStillLoads() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("old.state");
        java.nio.file.Files.write(file, (CoordinatorStore.HEADER_V1 + "\nhw|ore1|3\nend\n").getBytes());
        CoordinatorStore.Snapshot s = new CoordinatorStore(file).load();
        assertEquals(Long.valueOf(3), s.highWater.get("ore1"));
        assertTrue(s.obsMarks.isEmpty());
    }

    @Test
    public void sequencedBodiesRoundTripAndBadOnesAreDropped() {
        ObservationMessage m = new ObservationMessage(ObservationMessage.Op.G, "w1",
                Collections.singletonList(new ObservationMessage.Entry(VEIN, "-", 5, 100)), RUN1, 42);
        String body = m.encode();
        assertTrue(body.startsWith("2|G|w1|" + RUN1 + ".42|"));
        ObservationMessage back = ObservationMessage.decode(body);
        assertEquals(RUN1, back.epoch);
        assertEquals(42, back.seq);
        assertEquals("v1 bodies still parse, unsequenced", 0,
                ObservationMessage.decode("1|R|w1|b:o:1,2,3~stone~0~90").seq);
        assertNull(ObservationMessage.decode("2|R|w1|0.1|b:o:1,2,3~stone~0~90"));
        assertNull(ObservationMessage.decode("2|R|w1|5|b:o:1,2,3~stone~0~90"));
        assertNull(ObservationMessage.decode("2|R|w1|5.x|b:o:1,2,3~stone~0~90"));
    }

    @Test
    public void runNumbersOnlyGrowEvenWhenTheClockStepsBack() {
        Path f = tmp.getRoot().toPath().resolve("swarm").resolve("obs-run-epoch");
        long a = RunEpoch.next(f, RUN1);
        long b = RunEpoch.next(f, RUN1 - 3_600_000); // clock one hour back at the next launch
        long c = RunEpoch.next(f, RUN1);
        assertEquals(RUN1, a);
        assertTrue(b > a);
        assertTrue(c > b);
    }
}
