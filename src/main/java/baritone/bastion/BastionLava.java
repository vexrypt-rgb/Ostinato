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
}