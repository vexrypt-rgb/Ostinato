package baritone.bastion;

/**
 * Pure lava-escape choice. A door placed in lava replaces the two lava cells it occupies and the player can stand inside it:
 * the lava damage stops at once (only the remaining fire burns), which beats swimming when the shore is far or there is none.
 */
public final class BastionLava {
    private BastionLava() {}

    public enum Escape { DOOR, SWIM, PILLAR, NONE }

    /**
     * @param shoreDist blocks to the nearest standable cell, or -1 when none is in sight
     * @param depth     lava cells stacked at our feet (1 = a puddle we can just walk out of)
     * @param haveDoor  a door in the inventory
     * @param doorFloor a full block under our feet cell (a door needs it below)
     */
    public static Escape choose(double shoreDist, int depth, float health, boolean haveDoor, boolean doorFloor, boolean haveBlocks) {
        boolean shore = shoreDist >= 0;
        // lava costs ~2 hp a tick-second plus fire: about one block of swimming per 2-3 hp
        boolean swimSafe = shore && shoreDist <= 2.5 && health > 6;
        if (haveDoor && doorFloor && (depth >= 2 || !swimSafe)) return Escape.DOOR;
        if (shore) return Escape.SWIM;
        if (haveBlocks) return Escape.PILLAR;
        return Escape.NONE;
    }

    /**
     * Door descent under a lava lake: from a door pocket, break the block under us and put a new door in the cell we drop
     * into, until both our cells are clear of lava on every side; then seal the cell over our head and dig out sideways.
     *
     * @param clean clean[i] = the cell i below our feet (0 = feet) has no lava in it or beside it; index 0 is usually false
     * @param solid solid[i] = that cell is a block we can break and stand on (no cave under us mid-descent)
     * @return breaks needed (each break costs one door), or -1 when no safe bottom is in range
     */
    public static int descentSteps(boolean[] clean, boolean[] solid) {
        for (int k = 1; k < clean.length; k++) {
            // every cell we drop into must be a block (we break it, then the door needs the block under it)
            if (!solid[k]) return -1;
            if (clean[k] && clean[k - 1] && k + 1 < solid.length && solid[k + 1]) return k;
        }
        return -1;
    }

    /** Doors for the whole descent: the first pocket plus one per break. */
    public static int doorsNeeded(int steps) {
        return steps < 0 ? -1 : steps + 1;
    }

    /** Commit to the descent only with a pickaxe and enough doors for the depth; otherwise use the normal escape. */
    public static boolean canDescend(int steps, int doors, boolean pickaxe) {
        return pickaxe && steps > 0 && doors >= doorsNeeded(steps);
    }

    /** Seal once our head cell (index k-1) and feet cell (k) are clean: the cell above the head is what still touches the lake. */
    public static boolean sealNow(boolean headClean, boolean feetClean) {
        return headClean && feetClean;
    }
}