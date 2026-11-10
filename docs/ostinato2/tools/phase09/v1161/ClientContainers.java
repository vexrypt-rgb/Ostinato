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

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.inventory.container.BlastFurnaceContainer;
import net.minecraft.inventory.container.ChestContainer;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.FurnaceContainer;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.inventory.container.WorkbenchContainer;

import java.util.function.Supplier;

/**
 * Ostinato 2.0 Phase 09, second-version check only (not shipped, never run): the 1.16 (MCP names, Java 8) side of
 * {@link ContainerView}. Compiled by compile-1161.sh with the 26.3 tree's ContainerView and OpenVerifier unchanged,
 * against the 1.16.5 MCP jar the origin/1.16.1 branch builds with.
 */
public final class ClientContainers implements ContainerView {

    private final Supplier<ClientPlayerEntity> player;

    public ClientContainers(Supplier<ClientPlayerEntity> player) {
        this.player = player;
    }

    @Override
    public Kind kind() {
        ClientPlayerEntity p = player.get();
        return p == null ? Kind.NONE : kindOf(p.openContainer);
    }

    @Override
    public Object menu() {
        ClientPlayerEntity p = player.get();
        return p == null ? null : p.openContainer;
    }

    @Override
    public void close() {
        player.get().closeScreen();
    }

    public static Kind kindOf(Container menu) {
        if (menu instanceof PlayerContainer) {
            return Kind.NONE;
        }
        if (menu instanceof ChestContainer) {
            return Kind.CHEST;
        }
        if (menu instanceof WorkbenchContainer) {
            return Kind.CRAFTING;
        }
        if (menu instanceof FurnaceContainer) {
            return Kind.FURNACE;
        }
        if (menu instanceof BlastFurnaceContainer) {
            return Kind.BLAST_FURNACE;
        }
        return Kind.OTHER;
    }

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
