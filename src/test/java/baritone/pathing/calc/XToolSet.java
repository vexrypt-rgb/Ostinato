/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Shared file, identical in every fork.
 */
package baritone.pathing.calc;

import baritone.utils.ToolSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/** Three-way bench: break speed with an empty hand and no effects, through each fork's own speed formula. */
public final class XToolSet extends ToolSet {
    private final Map<Block, Double> cache = new IdentityHashMap<>();

    public XToolSet() {
        super(null); // considerPotionEffects is off (no player)
    }

    @Override
    public double getStrVsBlock(BlockState state) {
        return cache.computeIfAbsent(state.getBlock(), b -> ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, b.defaultBlockState()));
    }
}
