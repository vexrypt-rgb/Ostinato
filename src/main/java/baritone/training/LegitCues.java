package baritone.training;

// Ostinato: F3, the debug pie, and client-audible sounds. A closed pie chart contributes nothing.

/** Classifies the cues a legit track is allowed to read. No world lookup. */
public final class LegitCues {

    private LegitCues() {}

    /**
     * The top slice of the debug pie, or empty when the client is not showing the chart.
     * A stale profiler result left over from an earlier press does not count.
     */
    public static String pieTop(boolean chartOpen, String topSlice) {
        if (!chartOpen || topSlice == null || topSlice.isBlank()) {
            return "";
        }
        return topSlice;
    }

    /**
     * Footsteps, mining and breaking, mob ambient, mob hurt, water, lava. Other sounds are not cues.
     * {@code path} is the sound id path ({@code entity.zombie.ambient}), without the namespace.
     */
    public static String soundKind(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("lava")) {
            return "LAVA";
        }
        if (p.contains("water") || p.contains("swim") || p.contains("splash") || p.contains("bubble")) {
            return "WATER";
        }
        if (p.contains("hurt")) {
            return "MOB_HURT";
        }
        if (p.contains("ambient") && p.contains("entity")) {
            return "MOB_AMBIENT";
        }
        if (p.contains(".step")) {
            return "FOOTSTEP";
        }
        if (p.contains(".break") || p.contains(".hit")) {
            return "BLOCK_BREAK";
        }
        return null;
    }

    /** N, E, S, W, or SELF when the sound is on the player. */
    public static String bearing(double dx, double dz) {
        String card = CompassNeedle.cardinal(dx, dz);
        return card.isEmpty() ? "SELF" : card;
    }
}
