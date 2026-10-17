/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Shared file, identical in every fork.
 */
package baritone.pathing.calc;

import net.minecraft.world.level.border.WorldBorder;

/** Three-way bench: a rectangular vanilla border around the bench box, so every fork's search stops at the same edges. */
public final class XBorder extends WorldBorder {
    private final double minX, maxX, minZ, maxZ;

    public XBorder(double minX, double maxX, double minZ, double maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /** The same box Ostinato's PathBench uses: one block of margin around the voxels. */
    public static XBorder around(int sizeX, int sizeZ) {
        return new XBorder(-1, sizeX + 1, -1, sizeZ + 1);
    }

    @Override
    public double getMinX() {
        return minX;
    }

    @Override
    public double getMaxX() {
        return maxX;
    }

    @Override
    public double getMinZ() {
        return minZ;
    }

    @Override
    public double getMaxZ() {
        return maxZ;
    }
}
