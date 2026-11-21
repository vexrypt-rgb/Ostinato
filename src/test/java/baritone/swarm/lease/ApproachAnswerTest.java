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

package baritone.swarm.lease;

import baritone.swarm.world.ObservationVerifier;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c part 2, from the p2c2 slice 1 live check (21 Nov). Walking into range of a far vein, a bot first
 * sees none of its chunks ("vein not loaded"), then some of them ("vein runs into unloaded chunks"), then all of them.
 * Both not-loaded answers mean "walk closer and check again"; the decision comes from the result's notLoaded flag,
 * never from its message (the live S2 run ended its approach at the second answer).
 */
public class ApproachAnswerTest {

    /** Blocks are stone except the vein; a block is visible only in a loaded chunk. */
    static final class ChunkWorld implements ReservationResolver.World {
        final Set<Long> loaded = new HashSet<>();

        void load(int cx, int cz) {
            loaded.add(((long) cx << 32) ^ (cz & 0xffffffffL));
        }

        @Override
        public String block(int x, int y, int z) {
            if (!loaded.contains(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL))) {
                return null;
            }
            if (y == 10 && z == 0 && (x == 14 || x == 15)) {
                return "minecraft:iron_ore";
            }
            return "minecraft:stone";
        }

        @Override
        public int[] otherChestHalf(int x, int y, int z) {
            return null;
        }
    }

    // Two iron blocks at the east edge of chunk 0,0; the vein's neighbours reach into chunks 1,0 and 0,-1.
    static final String VEIN = "v:o:14,10,0:15,10,0";

    @Test
    public void walkingIntoRangeIsApproachApproachVerified() {
        ChunkWorld w = new ChunkWorld();
        ObservationVerifier.Result none = ObservationVerifier.verify(VEIN, "iron_ore.2", 'o', w);
        assertTrue(none.notLoaded);
        assertEquals(BotLeaseGuard.OBS_APPROACH, BotLeaseGuard.answerFor(none));

        w.load(0, 0);
        w.load(0, -1);
        ObservationVerifier.Result part = ObservationVerifier.verify(VEIN, "iron_ore.2", 'o', w);
        assertEquals("the box is loaded but the vein's neighbours are not", "vein runs into unloaded chunks", part.why);
        assertTrue(part.notLoaded);
        assertEquals(BotLeaseGuard.OBS_APPROACH, BotLeaseGuard.answerFor(part));

        w.load(1, 0);
        w.load(1, -1);
        ObservationVerifier.Result all = ObservationVerifier.verify(VEIN, "iron_ore.2", 'o', w);
        assertEquals(ObservationVerifier.State.CONFIRMED, all.state);
        assertNull("verified", BotLeaseGuard.answerFor(all));
    }

    @Test
    public void otherUnknownsAreNotApproached() {
        ObservationVerifier.Result other = ObservationVerifier.verify(VEIN, "iron_ore.2", 'n', new ChunkWorld());
        assertEquals(ObservationVerifier.State.UNKNOWN, other.state);
        assertFalse("another dimension cannot be walked to", other.notLoaded);
        assertEquals(BotLeaseGuard.OBS_UNKNOWN, BotLeaseGuard.answerFor(other));
        ChunkWorld w = new ChunkWorld();
        w.load(0, 0);
        w.load(0, -1);
        w.load(1, 0);
        w.load(1, -1);
        assertEquals(BotLeaseGuard.OBS_STALE, BotLeaseGuard.answerFor(
                ObservationVerifier.verify("v:o:4,10,4:4,10,4", "iron_ore.1", 'o', w)));
    }
}
