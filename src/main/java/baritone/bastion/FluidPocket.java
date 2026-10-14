package baritone.bastion;

/**
 * Door air pockets in a fluid (lava or water). A door cell holds no fluid, so standing in one keeps the head dry:
 * in lava it stops the burning, underwater it lets the air bar refill. Shared decisions for both; the multi-door
 * descent counts come from {@link BastionLava}.
 */
public final class FluidPocket {
    private FluidPocket() {}

    public enum Action { NONE, SWIM_UP, SINK, DOOR, WAIT, TUNNEL }

    /** Ticks to swim up one block (about 2 blocks/s upward in water). */
    public static final int TICKS_PER_BLOCK = 10;
    /** Margin of air ticks kept when deciding the surface is reachable. */
    public static final int MARGIN = 40;

    /**
     * @param air       current air ticks (300 max underwater)
     * @param maxAir    max air ticks
     * @param surface   blocks of water above the eyes to open air, or -1 when a solid ceiling closes it off
     * @param inPocket  standing in our door
     * @param doors     doors in the inventory
     * @param floor     a full block under the feet cell (a door needs one)
     * @param exit      an air pocket outside the water within tunnelling range
     * @param pickaxe   have a pickaxe
     */
    public static Action decide(int air, int maxAir, int surface, boolean inPocket, int doors, boolean floor, boolean exit, boolean pickaxe) {
        boolean reach = surface >= 0 && surface * TICKS_PER_BLOCK + MARGIN < air;
        if (inPocket) {
            if (air < maxAir) return Action.WAIT;
            if (surface >= 0 && surface * TICKS_PER_BLOCK + MARGIN < maxAir) return Action.SWIM_UP;
            return exit && pickaxe ? Action.TUNNEL : Action.WAIT;
        }
        if (reach) return Action.SWIM_UP;
        if (doors <= 0) return Action.SWIM_UP;
        return floor ? Action.DOOR : Action.SINK;
    }

    /** A cell may be mined toward the exit only if none of its neighbours (other than our own pocket/tunnel) holds fluid. */
    public static boolean safeToMine(boolean[] neighbourFluid) {
        for (boolean b : neighbourFluid) if (b) return false;
        return true;
    }
}