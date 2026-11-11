package baritone.swarm.center;

/**
 * Ostinato: the two command-center layouts. Wall is the default. Map is the war-room placeholder.
 */
public enum CenterLayout {
    WALL,
    MAP;

    public static CenterLayout parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("layout is wall or map");
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "wall", "tiles" -> WALL;
            case "map", "war", "warroom" -> MAP;
            default -> throw new IllegalArgumentException("layout is wall or map");
        };
    }

    public String wire() {
        return this == WALL ? "wall" : "map";
    }
}
