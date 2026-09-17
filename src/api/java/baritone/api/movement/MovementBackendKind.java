package baritone.api.movement;

/**
 * Travel movement backend selection for goto-style goals.
 * Mining / digging / builder stay on classic Baritone pathing.
 */
public enum MovementBackendKind {
    BARITONE,
    TUNGSTEN,
    AUTO;

    public static MovementBackendKind fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase()) {
            case "baritone", "bati", "bt", "classic" -> BARITONE;
            case "tungsten", "tung", "physics" -> TUNGSTEN;
            case "auto", "default" -> AUTO;
            default -> AUTO;
        };
    }
}
