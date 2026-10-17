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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.utils.AirDrift;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static org.junit.Assert.*;

public class SteeredFallTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    private static final int Y = 100;

    /**
     * A dropper: walk off at y 100, a glass floor 13 down with its hole one over diagonally, another 40 down with its
     * hole one further over, and water at the bottom.
     */
    private static SyntheticWorld dropper() {
        SyntheticWorld w = new SyntheticWorld(32, 32);
        w.set(10, Y - 1, 10, Blocks.STONE.defaultBlockState());
        for (int x = 4; x <= 20; x++) {
            for (int z = 4; z <= 20; z++) {
                w.set(x, 86, z, Blocks.GLASS.defaultBlockState());
                w.set(x, 59, z, Blocks.GLASS.defaultBlockState());
                w.set(x, 40, z, Blocks.WATER.defaultBlockState());
                w.set(x, 39, z, Blocks.WATER.defaultBlockState());
                w.set(x, 38, z, Blocks.STONE.defaultBlockState());
            }
        }
        w.set(12, 86, 11, Blocks.AIR.defaultBlockState());
        w.set(13, 59, 11, Blocks.AIR.defaultBlockState());
        return w;
    }

    @Test
    public void threadsBothHolesIntoTheWater() {
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(PathBench.context(dropper()), 10, Y, 10, 11, 10, res);
        assertTrue("cost " + res.cost, res.cost < COST_INF);
        assertEquals(13, res.x);
        assertEquals(40, res.y);
        assertEquals(11, res.z);
        assertArrayEquals(new int[]{12, 11, 86, 13, 11, 59}, res.steer);
        assertEquals(0, res.damage, 0);
    }

    @Test
    public void withoutSteeringItLandsOnTheFirstFloor() {
        Baritone.settings().allowSteeredFalls.value = false;
        try {
            MutableMoveResult res = new MutableMoveResult();
            MovementDescend.cost(PathBench.context(dropper()), 10, Y, 10, 11, 10, res);
            assertNull(res.steer);
            if (res.cost < COST_INF) {
                assertEquals(87, res.y);
            }
        } finally {
            Baritone.settings().allowSteeredFalls.value = true;
        }
    }

    @Test
    public void aHoleTooFarOverIsNotThreaded() {
        SyntheticWorld w = dropper();
        // the second hole three over: 27 blocks of falling is not enough time to drift that far
        w.set(13, 59, 11, Blocks.GLASS.defaultBlockState());
        w.set(15, 59, 11, Blocks.AIR.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(PathBench.context(w), 10, Y, 10, 11, 10, res);
        assertNotEquals(40, res.y);
    }

    @Test
    public void reach() {
        assertTrue(AirDrift.reachable(1, 1, 0, 13));
        assertTrue(AirDrift.reachable(1, 0, 16, 40));
        assertFalse(AirDrift.reachable(1, 0, 16, 30));
        assertFalse(AirDrift.reachable(3, 0, 0, 5));
        assertFalse(AirDrift.reachable(AirDrift.MAX_RADIUS + 1, 0, 0, 500));
        assertTrue(AirDrift.reachable(0, 0, 0, 0));
    }

    @Test
    public void keysPointAtTheTarget() {
        // facing south (yaw 0), +z is forward and +x is left
        boolean[] k = AirDrift.keys(0, 0, 0, 0, 50, 0, 0, 0, 1, 0);
        assertArrayEquals(new boolean[]{true, false, false, false}, k);
        k = AirDrift.keys(0, 0, 0, 0, 50, 0, 0, 1, 0, 0);
        assertArrayEquals(new boolean[]{false, false, true, false}, k);
        k = AirDrift.keys(0, 0, 0, 0, 50, 0, 0, -1, -1, 0);
        assertArrayEquals(new boolean[]{false, true, false, true}, k);
        // already drifting there fast enough: let go
        k = AirDrift.keys(0, 0, 0, 0.1, 50, 0, 0, 0, 1, 0);
        assertArrayEquals(new boolean[]{false, false, false, false}, k);
    }
}
