package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ChainTemplatesTest {

    private static final class Blocks implements PlayerSim.World {
        final Set<Long> solid = new HashSet<>();

        void add(int x, int y, int z) {
            solid.add(((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512));
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++)
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++)
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++)
                        if (solid.contains(((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512))) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }
    }

    @Test
    public void templatesLoadAndReachFurtherThanOneJump() {
        assertFalse(ChainTemplates.ALL.isEmpty());
        for (ChainTemplates.Template t : ChainTemplates.ALL) {
            assertTrue(t.a > t.padA && t.padA >= 3);
            assertTrue(t.ticks > 0 && t.ticks < 120);
            for (JumpTemplates.Template single : JumpTemplates.ALL) {
                assertFalse("a chain is only worth having where no single jump goes", single.a == t.a && single.dy == t.dy && single.b == t.b);
            }
        }
    }

    @Test
    public void everyChainFliesInItsOwnWorld() {
        for (ChainTemplates.Template t : ChainTemplates.ALL) {
            Blocks w = new Blocks();
            for (int a = -t.runUp; a <= 0; a++) w.add(a, -1, 0);
            w.add(t.padA, t.padDy - 1, 0);
            w.add(t.a, t.dy - 1, t.b);
            JumpSearch j1 = new JumpSearch(w);
            j1.dirX = 1;
            j1.edge = 1;
            j1.destX = t.padA;
            j1.destY = t.padDy;
            j1.carry = true;
            PlayerSim s = new PlayerSim(w);
            s.x = -t.runUp + 0.5;
            s.z = 0.5 + t.lateral;
            s.onGround = true;
            s.vy = -0.0784000015258789;
            assertTrue("first jump of " + t.padA + "," + t.a, j1.run(s, t.plan1, false, 0, null));
            JumpSearch j2 = new JumpSearch(w);
            j2.dirX = 1;
            j2.edge = t.padA + 1;
            j2.destX = t.a;
            j2.destY = t.dy;
            j2.destZ = t.b;
            assertTrue("second jump of " + t.padA + "," + t.a, j2.run(new PlayerSim(w).copyFrom(j1.sim()), t.plan2, false, 0, null));
        }
    }
}
