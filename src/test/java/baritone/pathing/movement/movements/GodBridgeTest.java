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
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.TickSim;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: god bridge. The walk is pure; the costs run on a synthetic world with the setting toggled. */
public class GodBridgeTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void restore() {
        Baritone.settings().allowGodBridge.value = false;
        Baritone.settings().assumeSafeWalk.value = false;
    }

    @Test
    public void defaultIsOffAndTheContextAgrees() {
        assertFalse(Baritone.settings().allowGodBridge.value);
        assertFalse(PathBench.context(new SyntheticWorld(8, 8)).allowGodBridge);
    }

    @Test
    public void thePitchHitsTheFarFaceOnlyOncePastIt() {
        assertTrue(GodBridge.hitsFace(0.2, GodBridge.pitchDegrees(0.2)));
        assertFalse(GodBridge.hitsFace(0, GodBridge.pitchDegrees(0.2)));
        assertFalse(GodBridge.hitsFace(-0.2, GodBridge.pitchDegrees(0.2)));
        assertFalse(GodBridge.hitsFace(0.5, GodBridge.pitchDegrees(0.2)));
    }

    @Test
    public void aWalkClicksInsideTheWindow() {
        double steady = TickSim.steadyGroundSpeed(false);
        double dragged = steady * TickSim.GROUND_DRAG;
        assertTrue(GodBridge.places(0.5, dragged));
        assertTrue(GodBridge.places(0.5, 0));
        assertTrue(GodBridge.places(0.05, dragged));
        assertTrue(steady < GodBridge.SUPPORT + 0.05);
    }

    @Test
    public void stillTurningAtTheEdgeSneaks() {
        assertFalse(GodBridge.sneakInstead(true, 0.2));
        assertFalse(GodBridge.sneakInstead(false, -0.5));
        assertTrue(GodBridge.sneakInstead(false, -0.1));
        assertTrue(GodBridge.sneakInstead(true, GodBridge.SUPPORT + 0.01));
    }

    @Test
    public void yawBiasWalksBackOntoTheLine() {
        assertEquals(90, GodBridge.backYaw(1, 0, 0), 1e-3);
        assertEquals(-90, GodBridge.backYaw(-1, 0, 0), 1e-3);
        assertEquals(180, Math.abs(GodBridge.backYaw(0, 1, 0)), 1e-3);
        assertEquals(0, GodBridge.backYaw(0, -1, 0), 1e-3);
        assertEquals(Direction.EAST, GodBridge.travel(1, 0));
        assertEquals(0.2, GodBridge.lateral(1, 0, 2.5, 2.7, 2.5, 2.5), 1e-9);
        double yaw = Math.toRadians(GodBridge.backYaw(1, 0, 0.2));
        assertTrue(-Math.cos(yaw) < 0); // too far south while bridging east: S walks back north of that
        double north = Math.toRadians(GodBridge.backYaw(0, -1, 0.2));
        assertTrue(Math.sin(north) < 0); // too far east while bridging north: S walks back west of that
    }

    @Test
    public void offMatchesTheSneakPriceAndOnIsAWalk() {
        double place = Baritone.settings().blockPlacementPenalty.value;
        double sneak = ActionCosts.SNEAK_ONE_BLOCK_COST + place;
        double walk = ActionCosts.WALK_ONE_BLOCK_COST + place;
        assertEquals(sneak, price(false, stone(), false), 1e-9);
        assertEquals(walk, price(true, stone(), false), 1e-9);
        assertTrue(walk < sneak);
    }

    @Test
    public void badFootingAndSidePlacementStayOnTheOldPrice() {
        double offStone = price(false, stone(), false);
        for (BlockState floor : new BlockState[]{
                Blocks.ICE.defaultBlockState(),
                Blocks.PACKED_ICE.defaultBlockState(),
                Blocks.BLUE_ICE.defaultBlockState(),
                Blocks.FROSTED_ICE.defaultBlockState(),
                Blocks.SLIME_BLOCK.defaultBlockState(),
                Blocks.HONEY_BLOCK.defaultBlockState(),
                Blocks.MAGMA_BLOCK.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(),
                Blocks.FURNACE.defaultBlockState(),
                Blocks.CRAFTING_TABLE.defaultBlockState()
        }) {
            assertFalse(floor.toString(), GodBridge.footing(floor));
        }
        assertEquals(offStone, price(true, Blocks.ICE.defaultBlockState(), false), 1e-9);
        assertEquals(price(false, stone(), true), price(true, stone(), true), 1e-9);
        assertTrue(GodBridge.footing(stone()));
        assertFalse(GodBridge.footing(Blocks.SOUL_SAND.defaultBlockState()));
        assertFalse(GodBridge.footing(Blocks.LADDER.defaultBlockState()));
    }

    @Test
    public void safeWalkKeepsTheSneak() {
        Baritone.settings().assumeSafeWalk.value = true;
        Baritone.settings().allowGodBridge.value = true;
        CalculationContext ctx = PathBench.context(bridge(stone(), false));
        assertFalse(ctx.allowGodBridge);
        double place = Baritone.settings().blockPlacementPenalty.value;
        assertEquals(ActionCosts.SNEAK_ONE_BLOCK_COST + place, MovementTraverse.cost(ctx, 2, 4, 2, 3, 2), 1e-9);
    }

    private static double price(boolean god, BlockState floor, boolean side) {
        boolean prev = Baritone.settings().allowGodBridge.value;
        Baritone.settings().allowGodBridge.value = god;
        try {
            return MovementTraverse.cost(PathBench.context(bridge(floor, side)), 2, 4, 2, 3, 2);
        } finally {
            Baritone.settings().allowGodBridge.value = prev;
        }
    }

    /** Feet at y=4 on {@code floor}, bridging east into air. {@code side} adds a stone neighbour to place against. */
    private static SyntheticWorld bridge(BlockState floor, boolean side) {
        SyntheticWorld w = new SyntheticWorld(8, 8);
        w.set(2, 3, 2, floor);
        if (side) {
            w.set(3, 3, 3, stone());
        }
        return w;
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }
}
