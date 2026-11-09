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

package baritone.pathing.precompute;

import baritone.pathing.calc.PathBench;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato 2.0 Phase 07: {@link StateIdCache} returns exactly the registry's id, for every state, under collisions and
 * from several threads, and never holds more than its slots.
 */
public class StateIdCacheTest {

    private static final List<BlockState> STATES = new ArrayList<>();

    @BeforeClass
    public static void boot() {
        PathBench.boot();
        for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
            STATES.add(s);
        }
    }

    @Test
    public void everyStateGetsTheRegistryIdOnMissAndOnHit() {
        assertTrue("registry larger than the cache, so slots collide", STATES.size() > 4 * StateIdCache.SLOTS);
        for (BlockState s : STATES) {
            int want = Block.BLOCK_STATE_REGISTRY.getId(s);
            assertEquals(want, StateIdCache.id(s));
            assertEquals(want, StateIdCache.id(s));
        }
        assertTrue(StateIdCache.size() <= StateIdCache.SLOTS);
    }

    @Test
    public void racingThreadsOnlySeeRegistryIds() throws InterruptedException {
        AtomicInteger wrong = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            long seed = t;
            ts.add(new Thread(() -> {
                Random r = new Random(seed);
                for (int i = 0; i < 200_000; i++) {
                    // a hot few states (as in a search) mixed with the whole registry (collisions)
                    BlockState s = STATES.get(r.nextInt(4) == 0 ? r.nextInt(STATES.size()) : r.nextInt(32));
                    if (StateIdCache.id(s) != Block.BLOCK_STATE_REGISTRY.getId(s)) {
                        wrong.incrementAndGet();
                    }
                }
            }));
        }
        ts.forEach(Thread::start);
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(0, wrong.get());
        assertTrue(StateIdCache.size() <= StateIdCache.SLOTS);
    }
}
