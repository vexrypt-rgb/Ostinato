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
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class PhysicsPathfinderTest {

    /** The node budget PhysicsTravel plans with. */
    private static final int BUDGET = 1500;

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

    private static PlayerSim standingAt(Blocks w, double x, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = 0;
        s.z = z;
        for (int i = 0; i < 3; i++) s.tick(0, false, false, false);
        assertTrue(s.onGround);
        return s;
    }

    /** Plans, then flies the plan in a fresh sim: the plan has to be what gets there, not the search's bookkeeping. */
    private static int planAndFly(Blocks w, double gx, double gz) {
        PlayerSim start = standingAt(w, 0.5, 0.5);
        List<PhysicsPathfinder.Action> plan = new PhysicsPathfinder(w, BUDGET).plan(start, gx, 0, gz, 0.35);
        assertNotNull("no plan within the budget", plan);
        PlayerSim s = new PlayerSim(w).copyFrom(start);
        for (PhysicsPathfinder.Action a : plan) s.tick(a.yaw, a.forward, a.sprint, a.jump);
        assertTrue(s.onGround);
        assertTrue(Math.hypot(s.x - gx, s.z - gz) <= 0.35 + 1e-6);
        assertEquals(0, s.y, 1e-6);
        return plan.size();
    }

    @Test
    public void walksAcrossFlatGround() {
        Blocks w = new Blocks();
        for (int z = -1; z <= 8; z++) for (int x = -1; x <= 1; x++) w.add(x, -1, z);
        int ticks = planAndFly(w, 0.5, 5.5);
        assertTrue("five blocks of sprinting, not a detour: " + ticks, ticks <= 30);
    }

    @Test
    public void jumpsATwoBlockGap() {
        Blocks w = new Blocks();
        for (int z = -2; z <= 2; z++) w.add(0, -1, z);
        for (int z = 5; z <= 8; z++) w.add(0, -1, z);
        planAndFly(w, 0.5, 6.5);
    }

    @Test
    public void turnsACorner() {
        Blocks w = new Blocks();
        for (int z = 0; z <= 3; z++) w.add(0, -1, z);
        for (int x = 0; x <= 4; x++) w.add(x, -1, 3);
        planAndFly(w, 3.5, 3.5);
    }
}
