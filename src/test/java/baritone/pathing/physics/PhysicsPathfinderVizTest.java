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

package baritone.pathing.physics;

import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.viz.PathViz;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class PhysicsPathfinderVizTest {

    /** A flat floor at y = -1 under x, z in [-4, 12). */
    private static final PlayerSim.World FLOOR = new PlayerSim.World() {
        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = Math.max(-4, PlayerSim.floor(minX)); x <= Math.min(11, PlayerSim.floor(maxX)); x++)
                for (int z = Math.max(-4, PlayerSim.floor(minZ)); z <= Math.min(11, PlayerSim.floor(maxZ)); z++)
                    if (PlayerSim.floor(minY) - 1 <= -1 && -1 <= PlayerSim.floor(maxY)) out.add(new double[]{x, -1, z, x + 1, 0, z + 1});
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }
    };

    private static PlayerSim start() {
        PlayerSim s = new PlayerSim(FLOOR);
        s.x = 0.5;
        s.z = 0.5;
        for (int i = 0; i < 3; i++) s.tick(0, false, false, false);
        return s;
    }

    private static PathViz viz(boolean search) {
        return new PathViz(() -> true, () -> true, () -> true, () -> true, () -> true, () -> search, () -> 0L);
    }

    @Test
    public void recordsTheExploredTreeWhenOn() {
        PathViz v = viz(true);
        List<PhysicsPathfinder.Action> plan = new PhysicsPathfinder(FLOOR, 1500).viz(v).plan(start(), 0.5, 0, 5.5, 0.35);
        assertNotNull(plan);
        assertTrue(v.treeCount > 0);
        assertTrue(v.treeMaxDepth >= 1);
        assertTrue(v.treeCount <= PathViz.MAX_TREE_EDGES);
    }

    @Test
    public void recordsNothingWhenOffAndPlansTheSame() {
        PathViz off = viz(false);
        List<PhysicsPathfinder.Action> a = new PhysicsPathfinder(FLOOR, 1500).viz(off).plan(start(), 0.5, 0, 5.5, 0.35);
        List<PhysicsPathfinder.Action> b = new PhysicsPathfinder(FLOOR, 1500).plan(start(), 0.5, 0, 5.5, 0.35);
        assertEquals(0, off.treeCount);
        assertNotNull(a);
        assertEquals(b.size(), a.size());
    }
}
