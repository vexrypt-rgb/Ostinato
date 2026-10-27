package baritone.maze;

import baritone.api.schematic.AbstractSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ostinato: dig-only schematic for one {@code #maze.tunnels} edge. Listed cells become air; an optional floor torch. */
public final class TunnelSchematic extends AbstractSchematic {

    public final BlockPos origin;
    private final Set<BlockPos> air = new HashSet<>();
    private final BlockPos torch;

    private TunnelSchematic(BlockPos origin, int sx, int sy, int sz, Set<BlockPos> cells, BlockPos torch) {
        super(sx, sy, sz);
        this.origin = origin;
        for (BlockPos p : cells) air.add(p.subtract(origin));
        this.torch = torch == null ? null : torch.subtract(origin);
    }

    /** {@code torch} (world pos, must be one of the cells, floor level) or null. */
    public static TunnelSchematic of(Set<BlockPos> cells, BlockPos torch) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
        }
        BlockPos o = new BlockPos(x0, y0, z0);
        return new TunnelSchematic(o, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1, cells, torch);
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        BlockPos p = new BlockPos(x, y, z);
        return air.contains(p);
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        if (current != null && current.is(Blocks.TORCH)) {
            return current; // Ostinato: never break an earlier breadcrumb torch when this edge overlaps its node
        }
        if (torch != null && torch.getX() == x && torch.getY() == y && torch.getZ() == z
                && approxPlaceable != null && approxPlaceable.contains(Blocks.TORCH.defaultBlockState())) {
            return Blocks.TORCH.defaultBlockState();
        }
        return Blocks.AIR.defaultBlockState();
    }
}
