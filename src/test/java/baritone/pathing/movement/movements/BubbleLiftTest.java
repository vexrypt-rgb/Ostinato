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

import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;
import static org.junit.Assert.*;

public class BubbleLiftTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    private static final int Y = 95;

    private static BlockState column(boolean down) {
        return Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, down);
    }

    /**
     * Hardkour's lift: stand at (10, 95, 10), a soul sand column one west at x 9 from y 95 to 102 walled in, open above,
     * and a ledge on its east side at y 104 (feet 105).
     */
    private static SyntheticWorld lift(boolean down) {
        SyntheticWorld w = new SyntheticWorld(32, 32);
        for (int x = 7; x <= 12; x++) {
            for (int z = 8; z <= 12; z++) {
                w.set(x, Y - 1, z, Blocks.STONE.defaultBlockState());
            }
        }
        w.set(9, Y - 1, 10, (down ? Blocks.MAGMA_BLOCK : Blocks.SOUL_SAND).defaultBlockState());
        for (int y = Y; y <= 110; y++) {
            if (y <= 102) {
                w.set(9, y, 10, column(down));
            }
            w.set(8, y, 10, Blocks.STONE.defaultBlockState());
            w.set(9, y, 9, Blocks.STONE.defaultBlockState());
            w.set(9, y, 11, Blocks.STONE.defaultBlockState());
            if (y >= Y + 2 && y <= 102) {
                w.set(10, y, 10, Blocks.STONE.defaultBlockState()); // the wall above the doorway
            }
        }
        w.set(10, 104, 10, Blocks.STONE.defaultBlockState());
        return w;
    }

    @Test
    public void ridesUpToTheLedge() {
        MutableMoveResult res = new MutableMoveResult();
        MovementBubbleLift.cost(PathBench.context(lift(false)), 10, Y, 10, 0, res);
        assertTrue("cost " + res.cost, res.cost < COST_INF);
        assertEquals(10, res.x);
        assertEquals(105, res.y);
        assertEquals(10, res.z);
    }

    @Test
    public void aDownColumnIsNoLift() {
        MutableMoveResult res = new MutableMoveResult();
        MovementBubbleLift.cost(PathBench.context(lift(true)), 10, Y, 10, 0, res);
        assertEquals(COST_INF, res.cost, 0);
    }

    @Test
    public void aRoofedColumnIsNoLift() {
        SyntheticWorld w = lift(false);
        w.set(9, 103, 10, Blocks.STONE.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementBubbleLift.cost(PathBench.context(w), 10, Y, 10, 0, res);
        assertEquals(COST_INF, res.cost, 0);
    }

    @Test
    public void aLedgeTooHighIsNoLift() {
        SyntheticWorld w = lift(false);
        for (int y = 103; y <= 106; y++) { // the ledge's floor at 106: feet 107, five over the top
            w.set(10, y, 10, Blocks.STONE.defaultBlockState());
        }
        MutableMoveResult res = new MutableMoveResult();
        MovementBubbleLift.cost(PathBench.context(w), 10, Y, 10, 0, res);
        assertEquals(COST_INF, res.cost, 0);
    }
}
