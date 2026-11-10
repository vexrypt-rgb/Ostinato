/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.interact;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Supplier;

/**
 * Ostinato 2.0 Phase 09: the Minecraft 26.3 side of {@link ContainerView}, and the only place the get-to-block
 * capability names a menu class. Another version replaces this file (1.16: {@code ChestContainer},
 * {@code WorkbenchContainer}, {@code FurnaceContainer}, {@code BlastFurnaceContainer}, {@code PlayerContainer},
 * {@code openContainer}, {@code closeScreen()}). The five menu classes are disjoint in 26.3, so the order of the checks
 * in {@link #kindOf} does not matter.
 */
public final class ClientContainers implements ContainerView {

    private final Supplier<LocalPlayer> player;

    public ClientContainers(Supplier<LocalPlayer> player) {
        this.player = player;
    }

    @Override
    public Kind kind() {
        LocalPlayer p = player.get();
        return p == null ? Kind.NONE : kindOf(p.containerMenu);
    }

    @Override
    public Object menu() {
        LocalPlayer p = player.get();
        return p == null ? null : p.containerMenu;
    }

    @Override
    public void close() {
        player.get().closeContainer();
    }

    public static Kind kindOf(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            return Kind.NONE;
        }
        if (menu instanceof ChestMenu) {
            return Kind.CHEST;
        }
        if (menu instanceof CraftingMenu) {
            return Kind.CRAFTING;
        }
        if (menu instanceof FurnaceMenu) {
            return Kind.FURNACE;
        }
        if (menu instanceof BlastFurnaceMenu) {
            return Kind.BLAST_FURNACE;
        }
        return Kind.OTHER;
    }

    /** The container right clicking {@code block} opens, or NONE when it opens none this capability handles. */
    public static Kind openedBy(Block block) {
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST) {
            return Kind.CHEST;
        }
        if (block == Blocks.CRAFTING_TABLE) {
            return Kind.CRAFTING;
        }
        if (block == Blocks.FURNACE) {
            return Kind.FURNACE;
        }
        if (block == Blocks.BLAST_FURNACE) {
            return Kind.BLAST_FURNACE;
        }
        return Kind.NONE;
    }
}
