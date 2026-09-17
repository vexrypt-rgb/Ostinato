package baritone.movement;

import baritone.api.BaritoneAPI;
import baritone.api.movement.IMovementBackend;
import baritone.api.movement.MovementBackendKind;
import baritone.api.utils.Helper;

/**
 * Resolves the active travel movement backend from {@link baritone.api.Settings#movementBackend}.
 * Mining / digging / builder processes always keep classic Baritone pathing.
 */
public final class MovementBackends implements Helper {

    private MovementBackends() {}

    public static IMovementBackend current() {
        MovementBackendKind kind = MovementBackendKind.fromString(
                BaritoneAPI.getSettings().movementBackend.value);
        return switch (kind) {
            case BARITONE -> BaritoneMovementBackend.INSTANCE;
            case TUNGSTEN, AUTO -> {
                if (TungstenMovementBackend.INSTANCE.isAvailable()) {
                    yield TungstenMovementBackend.INSTANCE;
                }
                yield BaritoneMovementBackend.INSTANCE;
            }
        };
    }

    /** True when CustomGoal travel should prefer Tungsten over Baritone pathing. */
    public static boolean preferTungstenTravel() {
        MovementBackendKind kind = MovementBackendKind.fromString(
                BaritoneAPI.getSettings().movementBackend.value);
        return switch (kind) {
            case BARITONE -> false;
            case TUNGSTEN, AUTO -> TungstenMovementBackend.INSTANCE.isAvailable();
        };
    }

    public static String statusLine() {
        MovementBackendKind kind = MovementBackendKind.fromString(
                BaritoneAPI.getSettings().movementBackend.value);
        boolean tung = TungstenMovementBackend.INSTANCE.isAvailable();
        return "movementBackend=" + kind
                + " tungsten=" + (tung ? "AVAILABLE (" + TungstenMovementBackend.INSTANCE.detail() + ")"
                : "MISSING (" + TungstenMovementBackend.INSTANCE.detail() + ")");
    }
}

