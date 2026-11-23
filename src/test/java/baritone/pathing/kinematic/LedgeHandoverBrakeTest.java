package baritone.pathing.kinematic;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ostinato: the kinematic controller does not hand a flat step back to Baritone at walking speed toward a drop the path
 * does not go into (p2c2 s2run2; swarm-gate-logs/ledge repro3, 10 of 10 walked off). States are from the live trace:
 * a 3-wide sky lane (z 1299-1301) with a 1-wide ledge at z=1302 under x 1040-1041, open air at z=1303.
 */
public class LedgeHandoverBrakeTest {

    static PlayerSim.World ledge() {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++)
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++)
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                            boolean solid = by == 149 && ((bz >= 1299 && bz <= 1301) || (bz == 1302 && (bx == 1040 || bx == 1041)));
                            if (solid) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                        }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    private static Set<Long> path(int... xyz) {
        Set<Long> s = new java.util.HashSet<>();
        for (int i = 0; i < xyz.length; i += 3) s.add(BlockPos.asLong(xyz[i], xyz[i + 1], xyz[i + 2]));
        return s;
    }

    /** The tick the controller handed over in repro3 trial 1: in the ledge cell, still moving toward the drop. */
    @Test
    public void theLiveHandOverStateHeadsForADrop() {
        assertTrue(KinematicController.dropAhead(ledge(), 1040.483, 150, 1302.448, -0.015, 0.136, path(1041, 150, 1302)));
    }

    @Test
    public void walkingAlongTheLedgeIsNotTowardADrop() {
        assertFalse(KinematicController.dropAhead(ledge(), 1040.5, 150, 1302.5, 0.2, 0, path(1041, 150, 1302)));
    }

    @Test
    public void theOpenLaneHasNoDrop() {
        assertFalse(KinematicController.dropAhead(ledge(), 1020.5, 150, 1300.5, 0, 0.2, path(1021, 150, 1300)));
        assertFalse(KinematicController.dropAhead(ledge(), 1020.5, 150, 1300.5, 0.2, 0, path(1021, 150, 1300)));
    }

    @Test
    public void aDropThePathGoesIntoIsTheDescendsNotADanger() {
        // the next movement steps down into the open cell ahead: that edge is the path's own
        assertFalse(KinematicController.dropAhead(ledge(), 1040.483, 150, 1302.448, -0.015, 0.136, path(1040, 148, 1303)));
    }

    @Test
    public void standingStillOrStillOverTheOwnCellIsNotBraked() {
        assertFalse(KinematicController.dropAhead(ledge(), 1040.5, 150, 1302.5, 0, 0, path()));
        assertTrue("walking speed is braked", 0.136 > KinematicController.BRAKE_FROM);
        assertTrue("a creep is not", 0.03 < KinematicController.BRAKE_FROM);
    }

    @Test
    public void onlyLevelWalkingStepsAreBraked() {
        assertTrue("a traverse along the ledge", KinematicController.flatWalk(true, 150, 150));
        assertFalse("a walking step that goes down", KinematicController.flatWalk(true, 150, 149));
        assertFalse("a descend, fall or jump", KinematicController.flatWalk(false, 150, 150));
    }
}
