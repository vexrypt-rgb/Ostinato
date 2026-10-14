package baritone.bastion;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Nether biome by its ground, not its registry key: the jar is compiled against 1.16.5 (dynamic biome registries) but
 * runs on 1.16.1 (static registry), so neither lookup works on both. The ground cover is unambiguous in the nether.
 */
public final class Biomes1161 {
    private Biomes1161() {}

    public static String id(World w, BlockPos p) {
        for (int dy = 6; dy >= -24; dy--) {
            BlockPos q = p.add(0, dy, 0);
            if (!w.isBlockLoaded(q)) continue;
            BlockState s = w.getBlockState(q);
            if (s.isAir() || !w.getBlockState(q.up()).isAir()) continue;
            String g = ground(s.getBlock());
            if (g != null) return g;
        }
        return "";
    }

    static String ground(Block b) {
        if (b == Blocks.WARPED_NYLIUM || b == Blocks.WARPED_WART_BLOCK) return "warped_forest";
        if (b == Blocks.CRIMSON_NYLIUM || b == Blocks.NETHER_WART_BLOCK) return "crimson_forest";
        if (b == Blocks.SOUL_SAND || b == Blocks.SOUL_SOIL) return "soul_sand_valley";
        if (b == Blocks.BASALT || b == Blocks.BLACKSTONE) return "basalt_deltas";
        if (b == Blocks.NETHERRACK) return "nether_wastes";
        return null;
    }
}
