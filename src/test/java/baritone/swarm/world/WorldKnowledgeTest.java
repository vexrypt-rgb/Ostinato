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

import baritone.swarm.world.WorldKnowledge.Outcome;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Ostinato: Phase 2c. The coordinator's shared world store: conflict rule, freshness, caps, invalidation, rebuild. */
public class WorldKnowledgeTest {

    private final AtomicLong mono = new AtomicLong(1_000_000L);
    private final List<String> log = new ArrayList<>();

    private WorldKnowledge store() {
        return store(WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
    }

    /** A store bound to world w1 whose (initial) rebuild is over. */
    private WorldKnowledge store(int terrainCap, int entityCap) {
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add, terrainCap, entityCap);
        assertTrue(k.setWorld("w1"));
        k.startRebuild(Collections.emptyList());
        assertFalse(k.rebuilding());
        return k;
    }

    private static ObservationMessage msg(ObservationMessage.Op op, String world, ObservationMessage.Entry... es) {
        return new ObservationMessage(op, world, Arrays.asList(es));
    }

    private static ObservationMessage r(String key, String detail, long age, int conf) {
        return msg(ObservationMessage.Op.R, "w1", new ObservationMessage.Entry(key, detail, age, conf));
    }

    private static ObservationMessage g(String key) {
        return msg(ObservationMessage.Op.G, "w1", new ObservationMessage.Entry(key, "-", 0, 100));
    }

    private static Outcome one(List<Outcome> o) {
        assertEquals(1, o.size());
        return o.get(0);
    }

    private boolean logged(String part) {
        for (String l : log) {
            if (l.contains(part)) {
                return true;
            }
        }
        return false;
    }

    // ---- the conflict rule --------------------------------------------------------------------------------

    private static ObservationRecord rec(String bot, long at, int conf, String detail) {
        return new ObservationRecord("b:o:1,2,3", "w1", at, at, bot, conf, detail, ObservationRecord.State.CONFIRMED);
    }

    @Test
    public void ruleTiersInOrder() {
        // Newer beats higher confidence.
        assertTrue(ObservationRecord.beats(rec("Zed", 200, 10, "z"), rec("Amy", 100, 100, "a")));
        assertEquals("newer", WorldKnowledge.rule(rec("Zed", 200, 10, "z"), rec("Amy", 100, 100, "a")));
        // Same time: higher confidence beats the smaller name.
        assertTrue(ObservationRecord.beats(rec("Zed", 100, 90, "z"), rec("Amy", 100, 80, "a")));
        assertEquals("confidence", WorldKnowledge.rule(rec("Zed", 100, 90, "z"), rec("Amy", 100, 80, "a")));
        // Same time and confidence: the smaller name, ignoring case.
        assertTrue(ObservationRecord.beats(rec("amy", 100, 90, "z"), rec("Bob", 100, 90, "a")));
        assertFalse(ObservationRecord.beats(rec("Bob", 100, 90, "a"), rec("amy", 100, 90, "z")));
        assertEquals("bot-name", WorldKnowledge.rule(rec("amy", 100, 90, "z"), rec("Bob", 100, 90, "a")));
        // All equal but the detail: the smaller detail.
        assertTrue(ObservationRecord.beats(rec("Amy", 100, 90, "coal_ore"), rec("amy", 100, 90, "iron_ore")));
        assertFalse(ObservationRecord.beats(rec("amy", 100, 90, "iron_ore"), rec("Amy", 100, 90, "coal_ore")));
        assertEquals("detail", WorldKnowledge.rule(rec("Amy", 100, 90, "coal_ore"), rec("amy", 100, 90, "iron_ore")));
    }

    /** The winner of a conflict does not depend on which report arrived first. */
    private String winner(ObservationMessage first, String firstBot, ObservationMessage second, String secondBot) {
        WorldKnowledge k = store();
        k.apply(firstBot, first);
        k.apply(secondBot, second);
        return k.get("b:o:1,2,3").bot + "/" + k.get("b:o:1,2,3").detail;
    }

    @Test
    public void arrivalOrderNeverDecides() {
        ObservationMessage[][] cases = {
                {r("b:o:1,2,3", "stone", 0, 50), r("b:o:1,2,3", "iron_ore", 500, 100)},  // newer
                {r("b:o:1,2,3", "stone", 0, 80), r("b:o:1,2,3", "iron_ore", 0, 90)},     // confidence
                {r("b:o:1,2,3", "stone", 0, 90), r("b:o:1,2,3", "iron_ore", 0, 90)},     // bot name
        };
        String[] expect = {"Zed/stone", "Amy/iron_ore", "Amy/iron_ore"};
        for (int i = 0; i < cases.length; i++) {
            assertEquals("case " + i, expect[i], winner(cases[i][0], "Zed", cases[i][1], "Amy"));
            assertEquals("case " + i + " reversed", expect[i], winner(cases[i][1], "Amy", cases[i][0], "Zed"));
        }
    }

    @Test
    public void conflictIsCountedAndLoggedWithTheRule() {
        WorldKnowledge k = store();
        assertEquals(Outcome.NEW, one(k.apply("Zed", r("b:o:1,2,3", "stone", 0, 90))));
        assertEquals(Outcome.WON, one(k.apply("Amy", r("b:o:1,2,3", "iron_ore", 0, 90))));
        assertEquals(1, k.conflictsWon());
        assertTrue(log.toString(), logged("swarm-obs: conflict b:o:1,2,3 winner=Amy (iron_ore) loser=Zed"));
        assertTrue(logged("rule=bot-name"));
        assertEquals(Outcome.LOST, one(k.apply("Zed", r("b:o:1,2,3", "stone", 0, 90))));
        assertEquals(1, k.conflictsLost());
        assertEquals("iron_ore", k.get("b:o:1,2,3").detail);
    }

    @Test
    public void aBotUpdatingItsOwnRecordIsNoConflict() {
        WorldKnowledge k = store();
        k.apply("Amy", r("b:o:1,2,3", "stone", 0, 90));
        mono.addAndGet(1000);
        assertEquals(Outcome.WON, one(k.apply("Amy", r("b:o:1,2,3", "air", 0, 90))));
        assertEquals(0, k.conflictsWon());
        assertFalse(logged("swarm-obs: conflict"));
    }

    @Test
    public void aBotsOwnOlderReportLosesWithoutAConflict() {
        // Ostinato: a delayed copy of a bot's own earlier report loses to its newer one, but is not a conflict.
        WorldKnowledge k = store();
        k.apply("Amy", r("b:o:1,2,3", "air", 0, 90));
        assertEquals(Outcome.LOST, one(k.apply("Amy", r("b:o:1,2,3", "stone", 3000, 90))));
        assertEquals(0, k.conflictsLost());
        assertFalse(log.toString(), logged("swarm-obs: conflict"));
        assertEquals("air", k.get("b:o:1,2,3").detail);
    }

    @Test
    public void sameFactRefreshesOrIsOlder() {
        WorldKnowledge k = store();
        k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90));
        k.leaseReport(FakeWorld.IRON_L, "obs-unknown vein not loaded", "Bob");
        assertTrue(k.get(FakeWorld.IRON_L).suspect);
        assertEquals(Outcome.OLDER, one(k.apply("Bob", r(FakeWorld.IRON_L, "iron_ore.4", 5000, 90))));
        assertTrue("an older copy does not clear suspect", k.get(FakeWorld.IRON_L).suspect);
        mono.addAndGet(1000);
        assertEquals(Outcome.REFRESH, one(k.apply("Bob", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90))));
        assertFalse(k.get(FakeWorld.IRON_L).suspect);
        assertEquals("Bob", k.get(FakeWorld.IRON_L).bot);
    }

    @Test
    public void overlappingVeinsOfOneOreAreOneConflict() {
        WorldKnowledge k = store();
        k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 1000, 90));
        k.apply("Amy", r("v:o:5,10,5:5,10,5", "coal_ore.1", 1000, 90));
        k.apply("Amy", r("v:o:0,10,0:0,10,0", "coal_ore.1", 1000, 90)); // other ore, same blocks: no conflict
        assertEquals(3, k.terrainSize());
        // Bob saw part of the iron vein mined out, later.
        assertEquals(Outcome.WON, one(k.apply("Bob", r("v:o:1,10,0:1,11,1", "iron_ore.3", 0, 90))));
        assertNull("the overlapped vein record is replaced", k.get(FakeWorld.IRON_L));
        assertNotNull(k.get("v:o:1,10,0:1,11,1"));
        assertNotNull(k.get("v:o:0,10,0:0,10,0"));
        assertEquals(3, k.terrainSize());
        // An older overlapping report loses and changes nothing.
        assertEquals(Outcome.LOST, one(k.apply("Cat", r(FakeWorld.IRON_L, "iron_ore.4", 9000, 90))));
        assertNull(k.get(FakeWorld.IRON_L));
    }

    // ---- stores, caps, freshness --------------------------------------------------------------------------

    @Test
    public void storesAreBoundedLeastRecentlyUsedFirst() {
        WorldKnowledge k = store(3, 2);
        k.apply("Amy", r("b:o:1,0,0", "stone", 0, 90));
        k.apply("Amy", r("b:o:2,0,0", "stone", 0, 90));
        k.apply("Amy", r("b:o:3,0,0", "stone", 0, 90));
        assertNotNull(k.get("b:o:1,0,0")); // touch: 2 is now the eldest
        k.apply("Amy", r("b:o:4,0,0", "stone", 0, 90));
        assertEquals(3, k.terrainSize());
        assertEquals(1, k.evicted());
        assertNull(k.get("b:o:2,0,0"));
        assertNotNull(k.get("b:o:1,0,0"));
        // Entities have their own cap and never push terrain out.
        for (int i = 0; i < 5; i++) {
            k.apply("Amy", r("e:o:cow-" + i, "cow", 0, 90));
        }
        assertEquals(2, k.entitySize());
        assertEquals(3, k.terrainSize());
    }

    @Test
    public void entitiesGoStaleAlmostAtOnceTerrainDoesNot() {
        WorldKnowledge k = store();
        assertEquals(Outcome.EXPIRED, one(k.apply("Amy", r("e:o:cow-1", "cow", WorldKnowledge.ENTITY_TTL_MS + 1, 90))));
        assertEquals(Outcome.NEW, one(k.apply("Amy", r("e:o:cow-2", "cow", 0, 90))));
        k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90));
        ObservationRecord cow = k.get("e:o:cow-2");
        assertTrue("a new entity record is fresh", k.fresh(cow));
        mono.addAndGet(WorldKnowledge.ENTITY_TTL_MS + 1);
        assertFalse("entity freshness is its TTL, not the terrain window", k.fresh(cow)); // Ostinato
        assertNull("entity past its TTL", k.get("e:o:cow-2"));
        k.apply("Amy", r("e:o:cow-3", "cow", 0, 90));
        mono.addAndGet(WorldKnowledge.ENTITY_TTL_MS + 1);
        k.tick();
        assertEquals("tick purges expired entities", 0, k.entitySize());
        ObservationRecord v = k.get(FakeWorld.IRON_L);
        assertTrue("terrain is still fresh", k.fresh(v));
        assertNotNull(k.pick("iron", 'o', null).record);
        mono.addAndGet(WorldKnowledge.TERRAIN_FRESH_MS);
        assertFalse(k.fresh(v));
        assertNull("a stale terrain record is not picked", k.pick("iron", 'o', null).record);
    }

    // ---- world binding ------------------------------------------------------------------------------------

    @Test
    public void worldChangeInvalidatesEverything() {
        WorldKnowledge k = store();
        assertEquals(0, k.invalidations());
        assertFalse("same world", k.setWorld("w1"));
        k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90));
        k.apply("Amy", r("e:o:cow-1", "cow", 0, 90));
        assertTrue(k.setWorld("w2"));
        assertEquals(1, k.invalidations());
        assertEquals(0, k.terrainSize());
        assertEquals(0, k.entitySize());
        assertTrue(logged("swarm-obs: invalidated 2 records (world w1 -> w2)"));
        // A late report from the old world is refused.
        assertEquals(Outcome.REJECTED, one(k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90))));
        assertEquals(1, k.worldRejected());
        assertEquals(0, k.terrainSize());
    }

    @Test
    public void unboundStoreRejectsReports() {
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add);
        assertEquals(Outcome.REJECTED, one(k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90))));
        assertEquals(0, k.terrainSize());
    }

    // ---- rebuild after a restart ----------------------------------------------------------------------------

    @Test
    public void nothingIsUsedUntilTheRebuildEnds() {
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add);
        k.setWorld("w1");
        assertTrue(k.rebuilding());
        k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90));
        assertNull(k.pick("iron", 'o', null).record);
        assertTrue(k.pick("iron", 'o', null).reason.contains("rebuilding"));
        assertNull(k.covering('o', 0, 10, 0));
        k.startRebuild(Arrays.asList("Amy", "Bob"));
        k.apply("amy", msg(ObservationMessage.Op.E, "w1"));
        assertTrue("Bob has not ended", k.rebuilding());
        assertTrue(k.pick("iron", 'o', null).reason.contains("bob"));
        k.apply("Bob", msg(ObservationMessage.Op.E, "w1"));
        assertFalse(k.rebuilding());
        assertTrue(logged("rebuild done records=1 (all-peers)"));
        assertEquals(FakeWorld.IRON_L, k.pick("iron", 'o', null).record.key);
        assertEquals(FakeWorld.IRON_L, k.covering('o', 1, 11, 1).key);
        assertNull(k.covering('o', 2, 10, 0));
        assertNull(k.covering('n', 1, 11, 1));
    }

    @Test
    public void rebuildEndsOnTimeoutOrWithNoPeers() {
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add);
        k.setWorld("w1");
        k.startRebuild(Collections.singletonList("Amy"));
        mono.addAndGet(WorldKnowledge.REBUILD_TIMEOUT_MS - 1);
        k.tick();
        assertTrue(k.rebuilding());
        mono.addAndGet(1);
        k.tick();
        assertFalse(k.rebuilding());
        assertTrue(logged("(timeout pending=[amy])"));
        WorldKnowledge none = new WorldKnowledge(mono::get, log::add);
        none.startRebuild(Collections.emptyList());
        assertFalse(none.rebuilding());
    }

    // ---- lease reports and picking --------------------------------------------------------------------------

    @Test
    public void leaseReportsTakeRecordsOutOfPicking() {
        WorldKnowledge k = store();
        k.apply("Amy", r(FakeWorld.IRON_L, "iron_ore.4", 0, 90));
        k.apply("Amy", r("v:o:20,10,0:20,10,0", "iron_ore.1", 0, 90));
        k.leaseReport(FakeWorld.IRON_L, "obs-unknown vein not loaded", "Bob");
        assertEquals("v:o:20,10,0:20,10,0", k.pick("iron", 'o', null).record.key);
        k.leaseReport("v:o:20,10,0:20,10,0", "obs-stale no iron_ore left in the box", "Bob");
        assertEquals(ObservationRecord.State.STALE, k.get("v:o:20,10,0:20,10,0").state);
        WorldKnowledge.Pick p = k.pick("iron", 'o', null);
        assertNull(p.record);
        assertEquals("no usable iron observation (2 known)", p.reason);
        k.leaseReport(FakeWorld.IRON_L, "done", "Bob"); // not an observation reason: ignored
        // The correction (G) from the bot that checked replaces the stale record.
        mono.addAndGet(10);
        k.apply("Bob", g("v:o:20,10,0:20,10,0"));
        assertTrue(k.get("v:o:20,10,0:20,10,0").gone());
    }

    @Test
    public void pickPrefersConfidenceThenNewestThenKeyAndSkipsBusy() {
        WorldKnowledge k = store();
        k.apply("Amy", r("v:o:10,10,0:10,10,0", "iron_ore.1", 0, 90));
        k.apply("Amy", r("v:o:20,10,0:20,10,0", "deepslate_iron_ore.1", 2000, 100));
        k.apply("Amy", r("v:o:30,10,0:30,10,0", "iron_ore.1", 1000, 100));
        k.apply("Amy", r("v:o:40,10,0:40,10,0", "iron_ore.1", 1000, 100));
        k.apply("Amy", r("v:n:10,10,0:10,10,0", "iron_ore.1", 0, 100));
        k.apply("Amy", r("v:o:50,10,0:50,10,0", "coal_ore.1", 0, 100));
        assertEquals("v:o:30,10,0:30,10,0", k.pick("iron", 'o', null).record.key);
        assertEquals("v:o:40,10,0:40,10,0", k.pick("iron", 'o', key -> key.startsWith("v:o:30")).record.key);
        assertEquals("v:o:20,10,0:20,10,0",
                k.pick("iron", 'o', key -> key.startsWith("v:o:30") || key.startsWith("v:o:40")).record.key);
        assertEquals("v:n:10,10,0:10,10,0", k.pick("iron", 'n', null).record.key);
        assertEquals("v:o:50,10,0:50,10,0", k.pick("coal", 'o', null).record.key);
        assertNull(k.pick("gold", 'o', null).record);
    }
}
