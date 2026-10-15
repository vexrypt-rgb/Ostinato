package baritone.pathing.movement.movements;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A 2-by-1 line only sprints. Cells that need a click or a grab stay on the one-block traverse. */
public class MovementLineSpecialTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void lineLeavesDoorsGatesAndClimbsToTraverse() {
        assertTrue(MovementLine.traverseOperates(Blocks.OAK_DOOR.defaultBlockState()));
        assertTrue(MovementLine.traverseOperates(Blocks.IRON_DOOR.defaultBlockState()));
        assertTrue(MovementLine.traverseOperates(Blocks.OAK_FENCE_GATE.defaultBlockState()));
        assertTrue(MovementLine.traverseOperates(Blocks.LADDER.defaultBlockState()));
        assertTrue(MovementLine.traverseOperates(Blocks.VINE.defaultBlockState()));
        assertTrue(MovementLine.traverseOperates(Blocks.WEEPING_VINES.defaultBlockState()));
        assertFalse(MovementLine.traverseOperates(Blocks.AIR.defaultBlockState()));
        assertFalse(MovementLine.traverseOperates(Blocks.STONE.defaultBlockState()));
    }
}
