package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;

/**
 * An iron door is not mined when a button, lever, or pressure plate on this side of it opens it,
 * and the walk fits in the time the door stays open. A wooden door is still a right-click on the door.
 * One-sided: an opener on the far side of the door does not count.
 */
public final class DoorPass {

    public enum Kind { NONE, WOOD_BUTTON, STONE_BUTTON, LEVER, PLATE }

    /** Wooden button 1.5s, stone button 1s. A lever stays; a plate stays while we stand on it. */
    public static final int WOOD_TICKS = 30;
    public static final int STONE_TICKS = 20;
    public static final int HELD_TICKS = 1_000_000;

    private DoorPass() {}

    public static int openTicks(Kind kind) {
        switch (kind) {
            case WOOD_BUTTON:
                return WOOD_TICKS;
            case STONE_BUTTON:
                return STONE_TICKS;
            case LEVER:
            case PLATE:
                return HELD_TICKS;
            default:
                return 0;
        }
    }

    /**
     * Extra ticks to click the opener, or {@link ActionCosts#COST_INF} when the door would shut on us.
     * The walk itself is not included.
     */
    public static double price(Kind kind, double walkTicks) {
        int open = openTicks(kind);
        if (open <= 0) {
            return ActionCosts.COST_INF;
        }
        if (walkTicks + 2 > open) {
            return ActionCosts.COST_INF;
        }
        return kind == Kind.LEVER || kind == Kind.PLATE ? 1 : 4;
    }

    /**
     * Opener offsets are relative to the door cell. {@code step} is src to dest, so the player stands on the
     * opposite side of that step. An opener in the door cell, or on the player's side, is reachable.
     */
    public static boolean fromThisSide(int stepX, int stepZ, int openerX, int openerZ) {
        if (openerX == 0 && openerZ == 0) {
            return true;
        }
        return openerX * stepX + openerZ * stepZ <= 0;
    }
}
