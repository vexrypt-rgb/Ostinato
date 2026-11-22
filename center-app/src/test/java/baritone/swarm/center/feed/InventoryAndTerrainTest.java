package baritone.swarm.center.feed;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: how a bot summarises its inventory and slices its cache into terrain. */
public class InventoryAndTerrainTest {

    @Test public void inventorySummaryMatchesTheOwnersTileChoice() {
        List<InventorySummary.Slot> slots = new ArrayList<>(Arrays.asList(new InventorySummary.Slot[36]));
        slots.set(0, new InventorySummary.Slot("minecraft:cobblestone", 64, false, 0, 0));
        slots.set(1, new InventorySummary.Slot("cobblestone", 30, false, 0, 0));
        slots.set(2, new InventorySummary.Slot("bread", 5, true, 0, 0));
        slots.set(3, new InventorySummary.Slot("iron_pickaxe", 1, false, 200, 250));
        slots.set(4, new InventorySummary.Slot("stone_sword", 1, false, 10, 131));
        slots.set(5, new InventorySummary.Slot("dirt", 12, false, 0, 0));
        slots.set(6, new InventorySummary.Slot("oak_log", 12, false, 0, 0));
        BotFeed.Inventory inv = InventorySummary.of(slots);
        assertEquals(29, inv.freeSlots());
        assertEquals(36, inv.totalSlots());
        assertEquals(5, inv.food());
        assertEquals(4, inv.top().size());
        assertEquals(new BotFeed.Item("cobblestone", 94), inv.top().get(0));
        assertEquals(new BotFeed.Item("dirt", 12), inv.top().get(1)); // ties by name
        assertEquals(20, inv.lowestToolPct());
        assertEquals("iron_pickaxe", inv.lowestTool());
    }

    @Test public void noToolsMeansNoDurability() {
        BotFeed.Inventory inv = InventorySummary.of(List.of(new InventorySummary.Slot("dirt", 1, false, 0, 0)));
        assertEquals(-1, inv.lowestToolPct());
        assertEquals("", inv.lowestTool());
    }

    @Test public void terrainIsChunkAlignedAroundTheBot() {
        TerrainFeed t = TerrainBuilder.around("Copper", 1, 1, 'o', 37, -5, new TerrainBuilder.Columns() {
            @Override public String block(int x, int z) {
                return x < 0 ? null : (x + z) % 2 == 0 ? "minecraft:grass_block" : "stone";
            }

            @Override public int y(int x, int z) {
                return 60 + (x & 3);
            }
        });
        int size = (2 * TerrainFeed.RADIUS_CHUNKS + 1) * 16;
        assertEquals(size, t.width());
        assertEquals((2 - TerrainFeed.RADIUS_CHUNKS) * 16, t.originX()); // bot chunk 2
        assertEquals((-1 - TerrainFeed.RADIUS_CHUNKS) * 16, t.originZ()); // bot chunk -1
        assertTrue(t.contains(37, -5));
        assertEquals("grass_block", t.blockAt(38, 0));
        assertEquals("stone", t.blockAt(37, 0));
        assertEquals(61, t.heightAt(37, 0));
        assertEquals(TerrainFeed.UNCACHED, t.blockAt(-1, 0));
        assertEquals(TerrainFeed.UNKNOWN_Y, t.heightAt(-1, 0));
        assertEquals(3, t.palette().size());
        assertEquals(TerrainFeed.UNCACHED, t.palette().get(0));
    }

    @Test public void moreThan255BlocksShareOther() {
        TerrainFeed t = TerrainBuilder.around("Copper", 1, 1, 'o', 0, 0, new TerrainBuilder.Columns() {
            @Override public String block(int x, int z) {
                return "b" + Math.floorMod(x * 31 + z, 400);
            }

            @Override public int y(int x, int z) {
                return 64;
            }
        });
        assertTrue(t.palette().size() <= 256);
        assertTrue(t.palette().contains(TerrainBuilder.OTHER));
    }
}
