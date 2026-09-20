package baritone.api.movement;

/**
 * Pure selection rules for hybrid travel. Safe to unit-test without Minecraft.
 * Mining / builder never use this selector.
 */
public final class MovementBackendSelector {

    private MovementBackendSelector() {}

    /**
     * Choose which backend should attempt travel first.
     * AUTO / TUNGSTEN prefer Tungsten when {@code tungstenAvailable}; otherwise Baritone.
     */
    public static MovementBackendKind select(MovementBackendKind preference, boolean tungstenAvailable) {
        MovementBackendKind pref = preference != null ? preference : MovementBackendKind.AUTO;
        return switch (pref) {
            case BARITONE -> MovementBackendKind.BARITONE;
            case TUNGSTEN, AUTO -> tungstenAvailable ? MovementBackendKind.TUNGSTEN : MovementBackendKind.BARITONE;
        };
    }

    /**
     * Map a declined / failed backend attempt to a {@link MovementFailureReason}.
     */
    public static MovementFailureReason mapFailure(boolean backendAvailable, boolean declined) {
        if (!backendAvailable) {
            return MovementFailureReason.BACKEND_UNAVAILABLE;
        }
        if (declined) {
            return MovementFailureReason.DECLINED;
        }
        return MovementFailureReason.PATH_CALC_FAILED;
    }
}
