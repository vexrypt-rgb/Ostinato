package baritone.pathing.kinematic;

import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collision boxes and slipperiness read straight from the client world, memoised for one tick. */
public final class ClientWorld implements PlayerSim.World {
    private final Long2ObjectOpenHashMap<List<double[]>> cache = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private final IPlayerContext ctx;

    public ClientWorld(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public void reset() {
        cache.clear();
    }

    @Override
    public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
        for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
            for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) { // -1: fences stick up 1.5
                for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                    out.addAll(boxes(x, y, z));
                }
            }
        }
    }

    private List<double[]> boxes(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        List<double[]> got = cache.get(key);
        if (got != null) {
            return got;
        }
        pos.set(x, y, z);
        BlockState state = ctx.world().getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(ctx.world(), pos);
        if (shape.isEmpty()) {
            got = Collections.emptyList();
        } else {
            got = new ArrayList<>();
            for (AABB bb : shape.toAabbs()) {
                got.add(new double[]{bb.minX + x, bb.minY + y, bb.minZ + z, bb.maxX + x, bb.maxY + y, bb.maxZ + z});
            }
        }
        cache.put(key, got);
        return got;
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).getBlock().getFriction();
    }

    @Override
    public boolean bouncy(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).getBlock() == Blocks.SLIME_BLOCK;
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }
}
