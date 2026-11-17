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

import baritone.swarm.lease.ReservationResolver;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static baritone.swarm.world.ObservationVerifier.State.*;
import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c. The bot's world view must call a chunk the client does not hold "not loaded", although the real
 * client reads it as air. {@link ClientStub} behaves like the 26.3 client (unheld chunks read as air, and a level-wide
 * "has chunk" that is always true), so a view that trusts the level instead of the chunk source fails these tests.
 * Live case (p2ch run 1, 17 Nov): Azalea, about 420 blocks from a vein, called it stale and wiped a valid record.
 */
public class LoadedWorldTest {

    /** Like the 26.3 client: only held chunks have blocks, everything else reads as air. */
    static final class ClientStub implements LoadedWorld.Client {
        final Set<Long> held = new HashSet<>();
        final Map<String, String> blocks = new HashMap<>();

        ClientStub hold(int chunkX, int chunkZ) {
            held.add(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
            return this;
        }

        ClientStub set(int x, int y, int z, String id) {
            blocks.put(x + "," + y + "," + z, id);
            return this;
        }

        /** 26.3's ClientLevel.hasChunk: always true. */
        boolean levelHasChunk(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public boolean chunkHeld(int chunkX, int chunkZ) {
            return held.contains(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
        }

        @Override
        public String blockId(int x, int y, int z) {
            if (!chunkHeld(x >> 4, z >> 4)) {
                return "minecraft:air";
            }
            return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:stone");
        }

        @Override
        public int[] otherChestHalf(int x, int y, int z) {
            return null;
        }
    }

    /** The view the bug used: loaded whenever the level says so, which on 26.3 is always. */
    private static ReservationResolver.World levelView(ClientStub c) {
        return new ReservationResolver.World() {
            @Override
            public String block(int x, int y, int z) {
                return c.levelHasChunk(x >> 4, z >> 4) ? c.blockId(x, y, z) : null;
            }

            @Override
            public int[] otherChestHalf(int x, int y, int z) {
                return null;
            }
        };
    }

    /** The L-shaped iron vein of FakeWorld (key v:o:0,10,0:1,11,1, chunk 0,0). */
    private static ClientStub ironL(ClientStub c) {
        return c.set(0, 10, 0, "minecraft:iron_ore").set(1, 10, 0, "minecraft:iron_ore")
                .set(1, 11, 0, "minecraft:iron_ore").set(1, 11, 1, "minecraft:iron_ore");
    }

    @Test
    public void anUnheldChunkIsNotLoadedThoughItReadsAsAir() {
        ClientStub c = ironL(new ClientStub().hold(0, 0));
        LoadedWorld w = new LoadedWorld(c);
        assertEquals("minecraft:iron_ore", w.block(0, 10, 0));
        assertEquals("the client reads it as air", "minecraft:air", c.blockId(400, 10, 400));
        assertNull("but the view calls it not loaded", w.block(400, 10, 400));
    }

    @Test
    public void negativeCoordinatesUseFloorChunks() {
        ClientStub c = new ClientStub().hold(-1, -1);
        LoadedWorld w = new LoadedWorld(c);
        assertNotNull(w.block(-1, 64, -16));
        assertNull("x = 0 is chunk 0, not held", w.block(0, 64, -1));
        assertNull("z = -17 is chunk -2, not held", w.block(-1, 64, -17));
    }

    @Test
    public void aBotOutOfRangeAnswersUnknownNotStale() {
        // The bot stands near chunk 25,25; the vein is in chunk 0,0, which it does not hold.
        ClientStub far = ironL(new ClientStub().hold(25, 25));
        ObservationVerifier.Result r = ObservationVerifier.verify(FakeWorld.IRON_L, "iron_ore.4", 'o', new LoadedWorld(far));
        assertEquals(UNKNOWN, r.state);
        assertEquals("vein not loaded", r.why);
        assertNull("no corrected key from a vein it cannot see", r.key);
        // The trap this test exists for: the level-wide check reads the unheld chunk as air and says STALE.
        assertEquals(STALE, ObservationVerifier.verify(FakeWorld.IRON_L, "iron_ore.4", 'o', levelView(far)).state);
    }

    @Test
    public void aBotInRangeStillConfirmsAndSeesAStaleVein() {
        // The vein's neighbours at x = -1 and z = -1 are in chunk -1, so a bot that sees it whole holds those too.
        ClientStub near = ironL(new ClientStub().hold(0, 0).hold(-1, 0).hold(0, -1).hold(-1, -1));
        assertEquals(CONFIRMED, ObservationVerifier.verify(FakeWorld.IRON_L, "iron_ore.4", 'o', new LoadedWorld(near)).state);
        near.set(1, 11, 1, "minecraft:stone");
        assertEquals(STALE, ObservationVerifier.verify(FakeWorld.IRON_L, "iron_ore.4", 'o', new LoadedWorld(near)).state);
    }

    @Test
    public void aVeinCrossingIntoAnUnheldChunkIsUnknown() {
        // Vein from x = 15 (chunk 0, held) to x = 16 (chunk 1, not held).
        ClientStub c = new ClientStub().hold(0, 0).set(15, 10, 0, "minecraft:iron_ore").set(16, 10, 0, "minecraft:iron_ore");
        assertEquals(UNKNOWN, ObservationVerifier.verify("v:o:15,10,0:16,10,0", "iron_ore.2", 'o', new LoadedWorld(c)).state);
        assertEquals("vein runs into unloaded chunks", ReservationResolver.vein('o', 15, 10, 0, new LoadedWorld(c)).error);
    }

    @Test
    public void unknownLeavesTheSharedRecordUntouched() {
        AtomicLong mono = new AtomicLong(1_000_000L);
        List<String> log = new ArrayList<>();
        WorldKnowledge k = new WorldKnowledge(mono::get, log::add, WorldKnowledge.TERRAIN_CAP, WorldKnowledge.ENTITY_CAP);
        assertTrue(k.setWorld("w1"));
        k.startRebuild(Collections.emptyList());
        k.apply("Spelunker", new ObservationMessage(ObservationMessage.Op.R, "w1",
                Arrays.asList(new ObservationMessage.Entry(FakeWorld.IRON_L, "iron_ore.4", 0, 90))));

        ObservationVerifier.Result r = ObservationVerifier.verify(FakeWorld.IRON_L, "iron_ore.4", 'o',
                new LoadedWorld(ironL(new ClientStub().hold(25, 25))));
        assertEquals(UNKNOWN, r.state);
        k.leaseReport(FakeWorld.IRON_L, "obs-unknown " + r.why, "Azalea");

        ObservationRecord rec = k.get(FakeWorld.IRON_L);
        assertEquals("still Spelunker's record", "Spelunker", rec.bot);
        assertEquals("iron_ore.4", rec.detail);
        assertEquals("not gone", ObservationRecord.State.CONFIRMED, rec.state);
        assertTrue("only taken out of picking until a fresh report", rec.suspect);
    }

    @Test
    public void aScanThatTouchesAnUnheldChunkDoesNotClaimTheWholeBox() {
        // Bot at x = 8 with r = 16: the box spans chunks -1..1. It holds -1 and 0 (the whole vein and its
        // neighbours) but not 1.
        ClientStub c = ironL(new ClientStub().hold(0, 0).hold(-1, 0).hold(0, -1).hold(-1, -1));
        ObservationReporter.Scan s = ObservationReporter.scan(new LoadedWorld(c), 'o', 8, 10, 8, 16, 2);
        assertNull("a partly unheld box must not be used to call records gone", s.box);
        assertEquals("the vein it does see whole is still reported", 1, s.seen.size());
    }
}
