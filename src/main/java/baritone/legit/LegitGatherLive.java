/*
 * Ostinato: reads one client tick into LegitGather.Facts. Not part of upstream Baritone.
 */
package baritone.legit;

import baritone.api.legit.SeenOnly;
import baritone.api.utils.IPlayerContext;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Samples the player, the four neighboring cells, and the water column. It does not scan for ore. A failure leaves
 * the facts unreadable, and {@link LegitGather#plan} then yields.
 */
public final class LegitGatherLive {

    private LegitGatherLive() {}

    public static LegitGather.Facts capture(IPlayerContext ctx, BlockStateInterface blocks, boolean graceBusy,
                                             int salt, int tick, int branchIndex, int branchStep,
                                             int originX, int originZ, String block) {
        LegitGather.Facts facts = new LegitGather.Facts();
        facts.graceBusy = graceBusy;
        facts.salt = salt;
        facts.tick = tick;
        facts.branchIndex = branchIndex;
        facts.branchStep = branchStep;
        facts.originX = originX;
        facts.originZ = originZ;
        facts.block = block == null ? "stone" : block;
        if (ctx == null || ctx.player() == null || ctx.world() == null) {
            facts.unreadable = true;
            return facts;
        }
        try {
            Player player = ctx.player();
            Level world = ctx.world();
            BlockPos feet = player.blockPosition();
            facts.feetX = feet.getX();
            facts.feetY = feet.getY();
            facts.feetZ = feet.getZ();
            facts.headInWater = player.isEyeInFluid(FluidTags.WATER);
            facts.air = player.getAirSupply();
            facts.maxAir = Math.max(1, player.getMaxAirSupply());
            int doors = 0;
            boolean holding = false;
            boolean anyPick = false;
            int selected = player.getInventory().getSelectedSlot();
            for (int i = 0; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (isPick(stack)) {
                    anyPick = true;
                    if (i == selected) {
                        holding = true;
                    }
                }
                if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof DoorBlock) {
                    doors++;
                }
            }
            facts.doors = doors;
            facts.pickaxe = anyPick;
            facts.pickInInventory = anyPick;
            facts.holdingPick = holding;
            McSeenView view = new McSeenView(world, blocks);
            BlockPos below = feet.below();
            BlockState floor = state(world, below);
            facts.floor = floor != null && floor.isFaceSturdy(world, below, Direction.UP) && !(floor.getBlock() instanceof DoorBlock);
            BlockState here = state(world, feet);
            facts.inPocket = here != null && here.getBlock() instanceof DoorBlock;
            facts.surface = facts.headInWater ? surface(view, feet.above()) : 0;
            facts.exit = false;
            for (int i = 0; i < 4; i++) {
                int x = feet.getX() + LegitGather.DX[i];
                int y = feet.getY();
                int z = feet.getZ() + LegitGather.DZ[i];
                SeenOnly.Cell kind = view.at(x, y, z);
                facts.open[i] = kind == SeenOnly.Cell.AIR || kind == SeenOnly.Cell.FLUID;
                facts.light[i] = view.light(x, y, z);
                if (kind == SeenOnly.Cell.AIR) {
                    facts.exit = true;
                }
            }
            return facts;
        } catch (Throwable ignored) {
            facts.unreadable = true;
            return facts;
        }
    }

    /** A pick of any tier. 26.3 has no PickaxeItem type, so this matches the pickaxe items. */
    private static boolean isPick(ItemStack stack) {
        return stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE) || stack.is(Items.IRON_PICKAXE)
                || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE);
    }

    /** Water blocks above the eye cell, or -1 when a solid or unknown cell closes the column. */
    private static int surface(McSeenView view, BlockPos eye) {
        int water = 0;
        for (int i = 1; i <= 12; i++) {
            SeenOnly.Cell cell = view.at(eye.getX(), eye.getY() + i, eye.getZ());
            if (cell == SeenOnly.Cell.AIR) {
                return water;
            }
            if (cell == SeenOnly.Cell.FLUID) {
                water++;
                continue;
            }
            return -1;
        }
        return -1;
    }

    private static BlockState state(Level world, BlockPos pos) {
        if (!world.hasChunkAt(pos)) {
            return null;
        }
        return world.getBlockState(pos);
    }
}
