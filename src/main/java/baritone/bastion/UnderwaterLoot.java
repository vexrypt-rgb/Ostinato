package baritone.bastion;

import java.util.Set;

/**
 * Pure decisions for underwater looting (buried treasure, shipwrecks): what to take, when to stop for air, and how to
 * reach a chest below the seabed. A door pocket on the seabed keeps the dig column dry: the cell under a door only touches
 * seabed blocks and the door, so no water flows in as long as no side neighbour holds water.
 */
public final class UnderwaterLoot {
    private UnderwaterLoot() {}

    /** Speedrun-useful chest items (iron for buckets/tools, gold for bartering, food, TNT, emeralds). */
    public static final Set<String> USEFUL = Set.of(
            "iron_ingot", "iron_nugget", "gold_ingot", "gold_nugget", "emerald", "diamond", "tnt",
            "cooked_cod", "cooked_salmon", "bread", "carrot", "potato", "wheat", "pumpkin", "suspicious_stew",
            "heart_of_the_sea", "iron_block", "gold_block", "obsidian");

    public static boolean useful(String id) { return USEFUL.contains(id); }

    public enum Step { SWIM_TO, SINK, POCKET, DIG_DOWN, OPEN, SURFACE, DONE }

    /**
     * @param dxz horizontal distance to the chest column
     * @param dy chest y minus feet y (negative: below)
     * @param air air ticks left
     * @param surface blocks of water to open air above, -1 if sealed
     * @param looted chest done
     * @param inPocket in our door pocket or dry dig column
     * @param sideWater the next dig cell has water beside it
     */
    public static Step next(double dxz, int dy, int air, int surface, boolean looted, boolean inPocket, boolean sideWater) {
        if (looted) return Step.SURFACE;
        boolean wet = !inPocket;
        int up = Math.max(0, surface) * FluidPocket.TICKS_PER_BLOCK + FluidPocket.MARGIN;
        if (wet && air < up + 40) return surface >= 0 && up < air ? Step.SURFACE : Step.POCKET;
        if (Math.hypot(dxz, dy) <= 4.0) return Step.OPEN;
        if (dxz > 1.0) return Step.SWIM_TO;
        if (dy < -1) {
            if (sideWater) return Step.DONE; // digging would flood the column
            return wet ? Step.POCKET : Step.DIG_DOWN;
        }
        return Step.SINK;
    }

    /** One seabed pocket is enough for any depth below it: the column under the door stays dry. */
    public static int doorsFor(int depthBelowSeabed) { return depthBelowSeabed > 0 ? 1 : 0; }

    /** Shipwreck chest order for a speedrun: treasure (iron, emeralds) > supply (food, TNT) > map. Lower is better. */
    public static int shipwreckPriority(String lootTable) {
        if (lootTable == null) return 3;
        if (lootTable.contains("treasure")) return 0;
        if (lootTable.contains("supply")) return 1;
        if (lootTable.contains("map")) return 2;
        return 3;
    }
}