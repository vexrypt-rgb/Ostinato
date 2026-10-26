package baritone.training;

// Ostinato: the compass reading a player can see. A position that is not on the client is not a goal.

/**
 * Turns a compass sample into a navigation target.
 * <p>
 * The sample is whatever the client already has in order to draw the needle: a lodestone tracker on the item,
 * the spawn point, the last death point, or a player entity that is loaded. A name with no loaded entity does not
 * become a coordinate.
 */
public final class CompassNeedle {

    /** Main hand, chosen before the offhand and the rest of the hotbar. */
    public static final int HAND = -2;
    /** Offhand, chosen when the main hand is not a compass. */
    public static final int OFFHAND = -1;
    /** Nothing in the hand or the hotbar. */
    public static final int NONE = -100;

    public enum Kind {
        NONE,
        /** Lodestone tracker on the item, including a manhunt compass whose tracker is rewritten to a player. */
        LODESTONE,
        /** Unbound compass. The client points it at the respawn position. */
        SPAWN,
        /** Recovery compass. The client points it at the last death position. */
        RECOVERY,
        /** Manhunt compass that names a player. The position exists only when that player is loaded on the client. */
        PLAYER
    }

    /** One compass the client can draw. */
    public static final class Reading {
        public final Kind kind;
        /** False when the needle spins: no client position, wrong dimension, or standing on the target. */
        public final boolean aimed;
        public final int x;
        public final int y;
        public final int z;
        /** N, E, S, or W. Empty when the needle is not aimed. */
        public final String bearing;
        public final String dimension;

        public Reading(Kind kind, boolean aimed, int x, int y, int z, String bearing, String dimension) {
            this.kind = kind;
            this.aimed = aimed;
            this.x = x;
            this.y = y;
            this.z = z;
            this.bearing = bearing;
            this.dimension = dimension;
        }
    }

    private CompassNeedle() {}

    /**
     * @param positionOnClient false when the only way to get {@code x,y,z} would be a server lookup. Those coordinates
     *                         are ignored.
     */
    public static Reading read(Kind kind, boolean positionOnClient, String targetDimension, int x, int y, int z,
            String hereDimension, double feetX, double feetY, double feetZ) {
        Kind use = kind == null ? Kind.NONE : kind;
        if (use == Kind.NONE || !positionOnClient) {
            return spinning(use);
        }
        if (targetDimension != null && !targetDimension.isEmpty() && hereDimension != null && !hereDimension.isEmpty()
                && !targetDimension.equals(hereDimension)) {
            return spinning(use);
        }
        double dx = (x + 0.5) - feetX;
        double dy = (y + 0.5) - feetY;
        double dz = (z + 0.5) - feetZ;
        // Same cutoff the client uses before it lets the needle settle: standing inside the target block spins.
        if (dx * dx + dy * dy + dz * dz < 1.0e-5) {
            return spinning(use);
        }
        String dim = targetDimension == null ? "" : targetDimension;
        return new Reading(use, true, x, y, z, cardinal(dx, dz), dim);
    }

    /**
     * Hand, then offhand, then hotbar slot 0 through 8. {@code hotbar[i]} is true when that slot holds a compass
     * and is not the selected slot (the selected slot is the main hand).
     */
    public static int pick(boolean mainHand, boolean offhand, boolean[] hotbar) {
        if (mainHand) {
            return HAND;
        }
        if (offhand) {
            return OFFHAND;
        }
        if (hotbar != null) {
            int n = Math.min(9, hotbar.length);
            for (int i = 0; i < n; i++) {
                if (hotbar[i]) {
                    return i;
                }
            }
        }
        return NONE;
    }

    /** Larger axis wins. A tie on the axes prefers east or west. This is the cardinal the training model hears. */
    public static String cardinal(double dx, double dz) {
        if (dx * dx + dz * dz < 1.0e-8) {
            return "";
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "E" : "W";
        }
        return dz >= 0 ? "S" : "N";
    }

    private static Reading spinning(Kind kind) {
        return new Reading(kind, false, 0, 0, 0, "", "");
    }
}
