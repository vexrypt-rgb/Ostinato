package baritone.swarm.world;

import baritone.swarm.center.feed.CoordinatorFeed;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: Phase 3 read API. Shared observations as feed records, matching {@code status()}, read without change. */
public class FeedObservationsTest {

    private static final String VEIN = "v:o:330,150,300:331,151,300";
    private static final long T0 = 10_000_000L;

    @Test public void observationsMatchStatusAndCarryTheirBox() {
        AtomicLong mono = new AtomicLong(T0);
        List<String> log = new ArrayList<>();
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add, WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
        assertTrue(k.setWorld("w1"));
        k.startRebuild(Collections.emptyList());
        List<ObservationMessage.Entry> e = new ArrayList<>();
        e.add(new ObservationMessage.Entry(VEIN, "iron_ore.6", 2_000, 90));
        k.apply("Spelunker", new ObservationMessage(ObservationMessage.Op.R, "w1", e, 1, 1));
        mono.addAndGet(3_000);
        List<CoordinatorFeed.Observation> feed = k.feedObservations(10);
        assertEquals(1, feed.size());
        assertEquals(1, k.feedSize());
        CoordinatorFeed.Observation o = feed.get(0);
        assertEquals(VEIN, o.key());
        assertEquals("vein", o.kind());
        assertEquals('o', o.dim());
        assertArrayEquals(new int[]{330, 150, 300, 331, 151, 300}, o.box());
        assertEquals("Spelunker", o.bot());
        assertEquals(90, o.confidence());
        assertEquals("iron_ore.6", o.detail());
        assertEquals("confirmed", o.state());
        assertEquals(5_000, o.ageMs());
        String line = k.status(8).get(1);
        assertTrue(line, line.contains(VEIN) && line.contains("age=5s"));
    }
}
