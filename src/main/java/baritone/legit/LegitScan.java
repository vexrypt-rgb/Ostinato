/*
 * Ostinato: nearby block match for legit targeting. Not part of upstream Baritone.
 */
package baritone.legit;

import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks of the requested type within {@link #RADIUS} of the player's feet. This does not read the ore cache and
 * does not decide whether a block is knowable. {@link LegitTargets} does that.
 */
public final class LegitScan {

    /** Far enough to catch a block the player is standing near. Not a world scan. */
    public static final int RADIUS = 16;

    private LegitScan() {}

    public static List<BlockPos> nearby(CalculationContext context, BlockOptionalMetaLookup filter, int radius) {
        List<BlockPos> out = new ArrayList<>();
        if (context == null || filter == null || context.baritone == null) {
            return out;
        }
        Entity player = context.baritone.getPlayerContext().player();
        if (player == null) {
            return out;
        }
        BlockStateInterface blocks = context.bsi;
        if (blocks == null) {
            return out;
        }
        BlockPos feet = player.blockPosition();
        int r = Math.max(1, radius);
        for (int x = feet.getX() - r; x <= feet.getX() + r; x++) {
            for (int y = feet.getY() - r; y <= feet.getY() + r; y++) {
                for (int z = feet.getZ() - r; z <= feet.getZ() + r; z++) {
                    if (filter.has(blocks.get0(x, y, z))) {
                        out.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return out;
    }
}
