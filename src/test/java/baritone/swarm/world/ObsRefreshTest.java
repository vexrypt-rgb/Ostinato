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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c part 2, slice 2 (refresh before reuse). A suspect or out-of-date record is re-checked by a bot
 * that has it loaded before the coordinator gives up on the ore: confirmed -> the refreshed record is offered; gone ->
 * nothing is offered; no answer -> decided at the wait's end, still unusable.
 */
public class ObsRefreshTest {

    private static final String VEIN = FakeWorld.IRON_L; // v:o:0,10,0:1,11,1, iron_ore.4
    private static final String OTHER = "v:o:40,10,40:41,10,40";
    private static final long T0 = 50_000_000L;

    private final AtomicLong mono = new AtomicLong(T0);
    private final List<String> log = new ArrayList<>();

    private WorldKnowledge store() {
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add, WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
        assertTrue(k.setWorld("w1"));
        k.startRebuild(Collections.emptyList());
        return k;
    }

    private static ObservationMessage report(String key, String detail, long seq) {
        return new ObservationMessage(ObservationMessage.Op.R, "w1",
                Collections.singletonList(new ObservationMessage.Entry(key, detail, 0, 90)), 1_700_000_000_000L, seq);
    }

    /** Spelunker reported the iron vein; Azalea's lease on it ended obs-unknown: suspect. */
    private WorldKnowledge suspectVein() {
        WorldKnowledge k = store();
        k.apply("Spelunker", report(VEIN, "iron_ore.4", 1));
        k.leaseReport(VEIN, "obs-unknown", "Azalea");
        assertTrue(k.get(VEIN).suspect);
        assertNull("not pickable while suspect", k.pick("iron", 'o', null).record);
        return k;
    }

    /** Spelunker's reporter, answering through its normal tick, delivered straight to the coordinator's store. */
    private static int deliver(ObservationReporter rep, WorldKnowledge k, long now) {
        int[] n = {0};
        rep.tick(now, true, m -> {
            k.apply("Spelunker", m);
            n[0]++;
            return true;
        });
        return n[0];
    }

    @Test
    public void suspectAndOutOfDateRecordsAreTheCandidates() {
        WorldKnowledge k = suspectVein();
        k.apply("Spelunker", report(OTHER, "iron_ore.2", 2));
        k.apply("Spelunker", report("v:o:60,10,60:60,10,60", "coal_ore.1", 3));
        List<ObservationRecord> c = k.refreshCandidates("iron", 'o', null, ObsRefresh.MAX_KEYS);
        assertEquals("only the suspect iron vein; the fresh one is pickable, coal is another ore", 1, c.size());
        assertEquals(VEIN, c.get(0).key);
        assertTrue("a busy key is never asked about", k.refreshCandidates("iron", 'o', key -> key.equals(VEIN), 4).isEmpty());
        mono.addAndGet(WorldKnowledge.TERRAIN_FRESH_MS + 1_000L);
        assertEquals("out of date now too", 2, k.refreshCandidates("iron", 'o', null, 4).size());
        assertEquals(1, k.refreshCandidates("iron", 'o', null, 1).size());
    }

    @Test
    public void aConfirmingAnswerRefreshesTheRecordAndItIsOfferedAfterTheRefresh() {
        WorldKnowledge k = suspectVein();
        ObsRefresh hold = new ObsRefresh();
        List<String> keys = new ArrayList<>();
        for (ObservationRecord r : k.refreshCandidates("iron", 'o', null, ObsRefresh.MAX_KEYS)) {
            keys.add(r.key);
        }
        hold.hold("Azalea", "iron", "obs:iron", "mine iron_ore", keys, mono.get());
        assertTrue(hold.due(k::refreshed, mono.get()).isEmpty());
        assertFalse(k.refreshed(VEIN));

        ObservationReporter spel = new ObservationReporter(1_700_000_000_001L);
        assertTrue(spel.setWorld("w1"));
        mono.addAndGet(2_000L);
        ObservationVerifier.Result r = ObservationVerifier.verify(VEIN, "iron_ore.4", 'o', FakeWorld.ironL());
        assertEquals("confirmed", spel.refreshAnswer(VEIN, "iron_ore.4", r, mono.get()));
        assertEquals(1, deliver(spel, k, mono.get()));

        assertTrue(k.refreshed(VEIN));
        List<ObsRefresh.Pending> due = hold.due(k::refreshed, mono.get());
        assertEquals("decided as soon as it was answered, not at the deadline", 1, due.size());
        WorldKnowledge.Pick p = k.pick(due.get(0).ore, 'o', null);
        assertNotNull(p.record);
        assertEquals(VEIN, p.record.key);
        assertFalse(p.record.suspect);
        assertEquals(0, hold.size());
    }

    @Test
    public void aGoneAnswerMeansNoOffer() {
        WorldKnowledge k = suspectVein();
        ObsRefresh hold = new ObsRefresh();
        hold.hold("Azalea", "iron", "obs:iron", "mine iron_ore", Collections.singletonList(VEIN), mono.get());
        ObservationReporter spel = new ObservationReporter(1_700_000_000_001L);
        assertTrue(spel.setWorld("w1"));
        FakeWorld mined = FakeWorld.ironL().set(0, 10, 0, "minecraft:stone").set(1, 10, 0, "minecraft:stone")
                .set(1, 11, 0, "minecraft:stone").set(1, 11, 1, "minecraft:stone");
        ObservationVerifier.Result r = ObservationVerifier.verify(VEIN, "iron_ore.4", 'o', mined);
        assertTrue(spel.refreshAnswer(VEIN, "iron_ore.4", r, mono.get()).startsWith("stale"));
        assertEquals(1, deliver(spel, k, mono.get()));
        assertTrue(k.get(VEIN).gone());
        assertEquals(1, hold.due(k::refreshed, mono.get()).size());
        assertNull("nothing to offer", k.pick("iron", 'o', null).record);
    }

    @Test
    public void noAnswerIsDecidedAtTheDeadlineAndStaysUnusable() {
        WorldKnowledge k = suspectVein();
        ObsRefresh hold = new ObsRefresh();
        hold.hold("Azalea", "iron", "obs:iron", "mine iron_ore", Collections.singletonList(VEIN), mono.get());
        ObservationReporter far = new ObservationReporter(1_700_000_000_002L);
        assertTrue(far.setWorld("w1"));
        FakeWorld nothingLoaded = new FakeWorld();
        nothingLoaded.loadedRadius = -1;
        ObservationVerifier.Result r = ObservationVerifier.verify(VEIN, "iron_ore.4", 'o', nothingLoaded);
        assertTrue(far.refreshAnswer(VEIN, "iron_ore.4", r, mono.get()).startsWith("cannot check here"));
        assertEquals("a bot without the blocks loaded sends nothing", 0, deliver(far, k, mono.get()));
        assertTrue(hold.due(k::refreshed, mono.get() + ObsRefresh.WAIT_MS - 1).isEmpty());
        assertEquals(1, hold.due(k::refreshed, mono.get() + ObsRefresh.WAIT_MS).size());
        assertNull(k.pick("iron", 'o', null).record);
        assertTrue(k.get(VEIN).suspect);
    }

    @Test
    public void oneHeldRequestPerBotAndResource() {
        ObsRefresh hold = new ObsRefresh();
        hold.hold("Azalea", "iron", "obs:iron", "mine iron_ore", Collections.singletonList(VEIN), 0L);
        assertTrue(hold.holding("azalea", "obs:iron"));
        assertFalse(hold.holding("Azalea", "obs:coal"));
    }

    @Test
    public void refreshRequestsCarryTheirKeys() {
        ObservationMessage f = new ObservationMessage(ObservationMessage.Op.F, "w1",
                Collections.singletonList(new ObservationMessage.Entry(VEIN, "iron_ore.4", 0, 0)));
        ObservationMessage back = ObservationMessage.decode(f.encode());
        assertNotNull(back);
        assertEquals(ObservationMessage.Op.F, back.op);
        assertEquals(VEIN, back.entries.get(0).key);
        assertNull("a refresh request without records is malformed",
                ObservationMessage.decode(new ObservationMessage(ObservationMessage.Op.F, "w1", new ArrayList<>()).encode()));
    }
}
