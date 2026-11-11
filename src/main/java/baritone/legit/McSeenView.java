/*
 * Ostinato: live world view for the seen-only filter. Not part of upstream Baritone.
 */
package baritone.legit;

import baritone.api.legit.SeenOnly;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Air and fluid do not block sight. Every other block does, including glass and slabs, because a block-grid check
 * cannot show a line through them. A chunk the client has not loaded is unknown, so a cached ore behind it is not
 * knowable. Light is the brighter of block light and sky light. A failure to read either is unknown.
 */
public final class McSeenView implements SeenOnly.View {

    private final Level world;
    private final BlockStateInterface blocks;

    public McSeenView(Level world, BlockStateInterface blocks) {
        this.world = world;
        this.blocks = blocks;
    }

    @Override
    public SeenOnly.Cell at(int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state == null) {
            return SeenOnly.Cell.UNKNOWN;
        }
        // Water and lava are see-through. A waterlogged slab or stair is still a block this check cannot see through.
        if (!state.getFluidState().isEmpty() && state.getBlock() instanceof LiquidBlock) {
            return SeenOnly.Cell.FLUID;
        }
        if (state.isAir()) {
            return SeenOnly.Cell.AIR;
        }
        return SeenOnly.Cell.OPAQUE;
    }

    @Override
    public int light(int x, int y, int z) {
        if (world == null) {
            return -1;
        }
        BlockPos pos = new BlockPos(x, y, z);
        try {
            if (!world.hasChunkAt(pos)) {
                return -1;
            }
            return Math.max(world.getBrightness(LightLayer.BLOCK, pos), world.getBrightness(LightLayer.SKY, pos));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private BlockState state(int x, int y, int z) {
        if (world == null) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        try {
            if (!world.hasChunkAt(pos)) {
                return null;
            }
            if (blocks != null) {
                return blocks.get0(x, y, z);
            }
            return world.getBlockState(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
