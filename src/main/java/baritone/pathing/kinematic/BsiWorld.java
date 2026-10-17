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

    private static final ThreadLocal<java.util.IdentityHashMap<BlockState, double[][]>> SHAPES = ThreadLocal.withInitial(java.util.IdentityHashMap::new);
    private static final ThreadLocal<java.util.IdentityHashMap<BlockState, Boolean>> CLIMB = ThreadLocal.withInitial(java.util.IdentityHashMap::new);

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
                    cellBoxes(x, y, z, out);
                }
            }
        }
    }

    @Override
    public boolean cellBoxes(int x, int y, int z, List<double[]> out) {
        BlockState state = bsi.get0(x, y, z);
        if (state.isAir()) {
            return true;
        }
        // Ostinato: the planner sims (head hitters, take-offs) spent most of A* in VoxelShape.toAabbs; a
        // block's local boxes are the same everywhere unless its shape depends on position, so cache them
        double[][] boxes = state.getBlock().hasDynamicShape() || state.hasOffsetFunction() ? null : SHAPES.get().get(state);
        if (boxes == null) {
            java.util.List<AABB> l = state.getCollisionShape(bsi.access, pos.set(x, y, z)).toAabbs();
            boxes = new double[l.size()][];
            for (int i = 0; i < boxes.length; i++) {
                AABB bb = l.get(i);
                boxes[i] = new double[]{bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ};
            }
            if (!state.getBlock().hasDynamicShape() && !state.hasOffsetFunction()) {
                SHAPES.get().put(state, boxes);
            }
        }
        for (double[] b : boxes) {
            out.add(new double[]{b[0] + x, b[1] + y, b[2] + z, b[3] + x, b[4] + y, b[5] + z});
        }
        return true;
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

    @Override
    public boolean climbable(int x, int y, int z) {
        BlockState s = bsi.get0(x, y, z); // Ostinato: tag lookups per sim tick were a visible A* cost
        return CLIMB.get().computeIfAbsent(s, st -> st.is(net.minecraft.tags.BlockTags.CLIMBABLE));
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        return bsi.get0(x, y, z).getBlock().getSpeedFactor();
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        return bsi.get0(x, y, z).getBlock().getJumpFactor();
    }

    @Override
    public boolean sticky(int x, int y, int z) {
        return bsi.get0(x, y, z).getBlock() == Blocks.HONEY_BLOCK;
    }
}
