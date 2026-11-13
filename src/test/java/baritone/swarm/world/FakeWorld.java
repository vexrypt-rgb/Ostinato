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

import java.util.HashMap;
import java.util.Map;

/** Ostinato: Phase 2c test world. Stone everywhere, blocks set by hand, loaded within |x|,|z| <= radius. */
final class FakeWorld implements ReservationResolver.World {

    final Map<String, String> blocks = new HashMap<>();
    int loadedRadius = 100;

    FakeWorld set(int x, int y, int z, String id) {
        blocks.put(x + "," + y + "," + z, id);
        return this;
    }

    @Override
    public String block(int x, int y, int z) {
        if (Math.abs(x) > loadedRadius || Math.abs(z) > loadedRadius) {
            return null;
        }
        return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:stone");
    }

    @Override
    public int[] otherChestHalf(int x, int y, int z) {
        return null;
    }

    /** The L-shaped iron vein of ReservationTest: key v:o:0,10,0:1,11,1, 4 blocks. */
    static FakeWorld ironL() {
        return new FakeWorld()
                .set(0, 10, 0, "minecraft:iron_ore")
                .set(1, 10, 0, "minecraft:iron_ore")
                .set(1, 11, 0, "minecraft:iron_ore")
                .set(1, 11, 1, "minecraft:iron_ore");
    }

    static final String IRON_L = "v:o:0,10,0:1,11,1";
}
