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

import baritone.process.interact.ContainerView.Kind;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Ostinato 2.0 Phase 09: the 26.3 adapter's two mappings, block to kind and menu class to kind. */
public class ClientContainersTest {

    @BeforeClass
    public static void boot() {
        // Load the block registry first; a test that touches it before Bootstrap leaves it broken for the whole JVM.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void theSixContainerBlocksAndNothingElse() {
        assertEquals(Kind.CHEST, ClientContainers.openedBy(Blocks.CHEST));
        assertEquals(Kind.CHEST, ClientContainers.openedBy(Blocks.TRAPPED_CHEST));
        assertEquals(Kind.CHEST, ClientContainers.openedBy(Blocks.ENDER_CHEST));
        assertEquals(Kind.CRAFTING, ClientContainers.openedBy(Blocks.CRAFTING_TABLE));
        assertEquals(Kind.FURNACE, ClientContainers.openedBy(Blocks.FURNACE));
        assertEquals(Kind.BLAST_FURNACE, ClientContainers.openedBy(Blocks.BLAST_FURNACE));
        // Phase 08's isContainer set was exactly these six: every other block opens nothing this capability handles
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Block b : BuiltInRegistries.BLOCK) {
            counts.merge(ClientContainers.openedBy(b), 1, Integer::sum);
        }
        assertEquals(Integer.valueOf(3), counts.get(Kind.CHEST));
        assertEquals(Integer.valueOf(1), counts.get(Kind.CRAFTING));
        assertEquals(Integer.valueOf(1), counts.get(Kind.FURNACE));
        assertEquals(Integer.valueOf(1), counts.get(Kind.BLAST_FURNACE));
        assertNull(counts.get(Kind.OTHER));
        assertEquals(Kind.NONE, ClientContainers.openedBy(Blocks.SMOKER));
        assertEquals(Kind.NONE, ClientContainers.openedBy(Blocks.BARREL));
    }

    @Test
    public void menuClassesMapToTheirKind() throws Exception {
        // instanceof needs only an instance of the class, not a constructed menu (which would need a player)
        assertEquals(Kind.NONE, ClientContainers.kindOf(blank(InventoryMenu.class)));
        assertEquals(Kind.CHEST, ClientContainers.kindOf(blank(ChestMenu.class)));
        assertEquals(Kind.CRAFTING, ClientContainers.kindOf(blank(CraftingMenu.class)));
        assertEquals(Kind.FURNACE, ClientContainers.kindOf(blank(FurnaceMenu.class)));
        assertEquals(Kind.BLAST_FURNACE, ClientContainers.kindOf(blank(BlastFurnaceMenu.class)));
        assertEquals(Kind.OTHER, ClientContainers.kindOf(blank(SmokerMenu.class))); // a sibling furnace is not a furnace
        assertEquals(Kind.OTHER, ClientContainers.kindOf(blank(BeaconMenu.class)));
        assertEquals(Kind.OTHER, ClientContainers.kindOf(null)); // as Phase 08: null was "not the inventory" and matched nothing
    }

    @Test
    public void noPlayerReadsAsNothingOpen() {
        ClientContainers c = new ClientContainers(() -> null);
        assertEquals(Kind.NONE, c.kind());
        assertNull(c.menu());
        assertFalse(OpenVerifier.reached(c, Kind.CHEST, null));
    }

    private static AbstractContainerMenu blank(Class<? extends AbstractContainerMenu> c) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return c.cast(((Unsafe) f.get(null)).allocateInstance(c));
    }
}
