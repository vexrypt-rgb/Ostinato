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

import static baritone.swarm.world.FakeWorld.IRON_L;
import static baritone.swarm.world.ObservationVerifier.State.*;
import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c. A bot re-checks a shared record against its own world before acting: CONFIRMED when it
 * matches, STALE (with what is there now) when it does not, UNKNOWN when it cannot see enough to tell.
 */
public class ObservationVerifierTest {

    private static ObservationVerifier.Result v(String key, String detail, FakeWorld w) {
        return ObservationVerifier.verify(key, detail, 'o', w);
    }

    @Test
    public void matchingVeinIsConfirmed() {
        assertEquals(CONFIRMED, v(IRON_L, "iron_ore.4", FakeWorld.ironL()).state);
        assertEquals("a record without a count still matches", CONFIRMED, v(IRON_L, null, FakeWorld.ironL()).state);
        assertEquals("namespaced detail", CONFIRMED, v(IRON_L, "minecraft:iron_ore.4", FakeWorld.ironL()).state);
    }

    @Test
    public void shrunkVeinIsStaleWithTheNewKey() {
        FakeWorld w = FakeWorld.ironL().set(1, 11, 1, "minecraft:stone");
        ObservationVerifier.Result r = v(IRON_L, "iron_ore.4", w);
        assertEquals(STALE, r.state);
        assertEquals("v:o:0,10,0:1,11,0", r.key);
        assertEquals("iron_ore.3", r.detail);
        assertEquals("vein changed iron_ore.4 -> iron_ore.3", r.why);
    }

    @Test
    public void wrongCountInTheSameBoxIsStale() {
        ObservationVerifier.Result r = v(IRON_L, "iron_ore.5", FakeWorld.ironL());
        assertEquals(STALE, r.state);
        assertEquals(IRON_L, r.key);
        assertEquals("iron_ore.4", r.detail);
    }

    @Test
    public void minedOutVeinIsStaleWithNothingLeft() {
        FakeWorld w = FakeWorld.ironL().set(0, 10, 0, "minecraft:air").set(1, 10, 0, "minecraft:air")
                .set(1, 11, 0, "minecraft:air").set(1, 11, 1, "minecraft:air");
        ObservationVerifier.Result r = v(IRON_L, "iron_ore.4", w);
        assertEquals(STALE, r.state);
        assertNull(r.key);
        assertNull(r.detail);
        assertEquals("no iron_ore left in the box", r.why);
        assertEquals(STALE, v(IRON_L, null, w).state);
    }

    @Test
    public void anotherOreInTheBoxIsNotTheRecordedOne() {
        FakeWorld w = new FakeWorld().set(0, 10, 0, "minecraft:coal_ore").set(1, 10, 0, "minecraft:coal_ore")
                .set(1, 11, 0, "minecraft:coal_ore").set(1, 11, 1, "minecraft:coal_ore");
        ObservationVerifier.Result r = v(IRON_L, "iron_ore.4", w);
        assertEquals(STALE, r.state);
        assertNull(r.key);
    }

    @Test
    public void unloadedVeinIsUnknownNeverStale() {
        FakeWorld w = FakeWorld.ironL();
        w.loadedRadius = 0; // x=1 is not loaded.
        ObservationVerifier.Result r = v(IRON_L, "iron_ore.4", w);
        assertEquals(UNKNOWN, r.state);
        assertEquals("vein not loaded", r.why);

        FakeWorld edge = FakeWorld.ironL();
        edge.loadedRadius = 1; // The box is loaded, but the flood fill reaches x=2.
        ObservationVerifier.Result e = v(IRON_L, "iron_ore.4", edge);
        assertEquals(e.toString(), UNKNOWN, e.state);
        assertNull(e.key);
    }

    @Test
    public void hugeBoxIsNotScanned() {
        assertEquals(UNKNOWN, v("v:o:0,0,0:30,30,30", "iron_ore.4", FakeWorld.ironL()).state);
        assertEquals("box too big", v("v:o:0,0,0:30,30,30", "iron_ore.4", FakeWorld.ironL()).why);
    }

    @Test
    public void containers() {
        FakeWorld w = new FakeWorld().set(5, 64, 7, "minecraft:chest").set(9, 64, 7, "minecraft:barrel");
        assertEquals(CONFIRMED, v("c:o:5,64,7", "chest", w).state);
        assertEquals(CONFIRMED, v("c:o:9,64,7", "barrel", w).state);
        ObservationVerifier.Result gone = v("c:o:6,64,7", "chest", w);
        assertEquals(STALE, gone.state);
        assertEquals("no container at 6,64,7", gone.why);
        w.loadedRadius = 4;
        assertEquals(UNKNOWN, v("c:o:5,64,7", "chest", w).state);
    }

    @Test
    public void blocks() {
        FakeWorld w = new FakeWorld().set(1, 2, 3, "minecraft:diamond_ore");
        assertEquals(CONFIRMED, v("b:o:1,2,3", "diamond_ore", w).state);
        assertEquals(CONFIRMED, v("b:o:1,2,3", null, w).state);
        ObservationVerifier.Result r = v("b:o:1,2,3", "gold_ore", w);
        assertEquals(STALE, r.state);
        assertEquals("b:o:1,2,3", r.key);
        assertEquals("diamond_ore", r.detail);
        w.loadedRadius = 0;
        assertEquals(UNKNOWN, v("b:o:1,2,3", "diamond_ore", w).state);
    }

    @Test
    public void regionsOnlyNeedToBeLoaded() {
        FakeWorld w = new FakeWorld();
        assertEquals(CONFIRMED, v("r:o:0,0,0:2,2,2", null, w).state);
        w.loadedRadius = 0;
        assertEquals(UNKNOWN, v("r:o:0,0,0:2,2,2", null, w).state);
    }

    @Test
    public void otherDimensionsAndUncheckableKindsAreUnknown() {
        assertEquals("other dimension", v("v:n:0,10,0:1,11,1", "iron_ore.4", FakeWorld.ironL()).why);
        assertEquals(UNKNOWN, v("v:n:0,10,0:1,11,1", "iron_ore.4", FakeWorld.ironL()).state);
        assertEquals(UNKNOWN, v("w:o:base", "base", FakeWorld.ironL()).state);
        assertEquals("kind not checkable", v("w:o:base", "base", FakeWorld.ironL()).why);
        assertEquals(UNKNOWN, v(null, null, FakeWorld.ironL()).state);
    }
}
