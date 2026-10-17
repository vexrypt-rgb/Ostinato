/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Per-fork adapter: Ostinato.
 */
package baritone.pathing.calc;

import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.world.level.block.state.BlockState;

/** Ostinato's block lookup over the bench voxels (the headless constructor PathBench's SyntheticWorld uses). */
public final class XWorld extends BlockStateInterface {
    public final XVoxels voxels;

    public XWorld(XVoxels voxels) {
        super(XVoxels.MIN_Y, XVoxels.HEIGHT, new BetterWorldBorder(XBorder.around(voxels.sizeX, voxels.sizeZ)));
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
    public boolean isKnown(int x, int z) {
        return true;
    }

    @Override
    public boolean worldContainsLoadedChunk(int x, int z) {
        return true;
    }
}
