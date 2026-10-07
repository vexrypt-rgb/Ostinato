package baritone.pathing.kinematic;

import baritone.utils.BlockStateInterface;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/** {@link PlayerSim.World} over the planner's cached block states, safe off the client thread. */
public final class BsiWorld implements PlayerSim.World {

    private final BlockStateInterface bsi;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public BsiWorld(BlockStateInterface bsi) {
        this.bsi = bsi;
    }

    @Override
    public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
        for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
            for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) {
                for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                    BlockState state = bsi.get0(x, y, z);
                    VoxelShape shape = state.getCollisionShape(bsi.access, pos.set(x, y, z));
                    for (AABB bb : shape.toAabbs()) {
                        out.add(new double[]{bb.minX + x, bb.minY + y, bb.minZ + z, bb.maxX + x, bb.maxY + y, bb.maxZ + z});
                    }
                }
            }
        }
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        return bsi.get0(x, y, z).getBlock().getFriction();
    }

    @Override
    public boolean bouncy(int x, int y, int z) {
        return bsi.get0(x, y, z).getBlock() == Blocks.SLIME_BLOCK;
    }

    @Override
    public boolean water(int x, int y, int z) {
        return bsi.get0(x, y, z).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }
}
