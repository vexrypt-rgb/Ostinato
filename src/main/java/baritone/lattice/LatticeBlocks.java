/*
 * Ostinato: solid floor for the lattice experiment. Same cell key as PhysicsPathfinderTest. Not upstream Baritone.
 */
package baritone.lattice;

import baritone.pathing.kinematic.PlayerSim;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Axis-aligned full blocks. Slipperiness is 0.6, matching the physics pathfinder tests. */
public final class LatticeBlocks implements PlayerSim.World {
    private final Set<Long> solid = new HashSet<>();

    /** Fills every cell in the inclusive rectangle at one y. */
    public LatticeBlocks fill(int x0, int x1, int y, int z0, int z1) {
        int xa = Math.min(x0, x1);
        int xb = Math.max(x0, x1);
        int za = Math.min(z0, z1);
        int zb = Math.max(z0, z1);
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                solid.add(key(x, y, z));
            }
        }
        return this;
    }

    static long key(int x, int y, int z) {
        return ((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512);
    }

    @Override
    public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
        for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
            for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) {
                for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                    if (solid.contains(key(x, y, z))) {
                        out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                    }
                }
            }
        }
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        return 0.6f;
    }
}
