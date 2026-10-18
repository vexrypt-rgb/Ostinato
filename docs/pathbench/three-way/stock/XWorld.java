/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Per-fork adapter: stock Baritone.
 */
package baritone.pathing.calc;

import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.world.level.block.state.BlockState;

/** Stock Baritone's block lookup over the bench voxels (uses the local XBENCH BlockStateInterface constructor). */
public final class XWorld extends BlockStateInterface {
    public final XVoxels voxels;

    public XWorld(XVoxels voxels) {
        super(new BetterWorldBorder(XBorder.around(voxels.sizeX, voxels.sizeZ)));
        this.voxels = voxels;
    }

    @Override
    public BlockState get0(int x, int y, int z) {
        return voxels.real(x, y, z);
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
