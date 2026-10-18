/*
 * Ostinato: bare-hands ToolSet for the headless pathfinding benchmark. New file, replaces no Baritone code.
 */
package baritone.pathing.calc.bench;

import baritone.utils.ToolSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/** Ostinato: break speed with an empty hand and no effects, cached per block like the real ToolSet. */
public final class HeadlessToolSet extends ToolSet {
    private final Map<Block, Double> cache = new IdentityHashMap<>();

    public HeadlessToolSet() {
        super(null); // considerPotionEffects must be off while constructing (no player)
    }

    @Override
    public double getStrVsBlock(BlockState state) {
        return cache.computeIfAbsent(state.getBlock(), b -> ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, b.defaultBlockState()));
    }
}
