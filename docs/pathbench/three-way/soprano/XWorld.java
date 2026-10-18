/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Per-fork adapter: Soprano.
 */
package baritone.pathing.calc;

import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.world.level.block.state.BlockState;

/** Soprano's block lookup over the bench voxels; Soprano's own per-thread get0 cache stays in front of it. */
public final class XWorld extends BlockStateInterface {
    public final XVoxels voxels;

    public XWorld(XVoxels voxels) {
        super(new BetterWorldBorder(XBorder.around(voxels.sizeX, voxels.sizeZ)), XVoxels.MIN_Y, XVoxels.HEIGHT);
        this.voxels = voxels;
    }

    @Override
    protected BlockState getUncached(int x, int y, int z) {
        return voxels.real(x, y + XVoxels.MIN_Y, z); // y comes in shifted by -minY
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return true;
    }

    @Override
    public boolean worldContainsLoadedChunk(int x, int z) {
        return true;
    }
}
