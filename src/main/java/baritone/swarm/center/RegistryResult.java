package baritone.swarm.center;

/**
 * Ostinato: the unified registry's answer. {@code ran} is true only after the dispatcher was invoked and returned true.
 * A refusal never includes a token, a command body, or an exception message.
 */
public record RegistryResult(boolean admitted, boolean executed, String reason) {

    public RegistryResult {
        reason = reason == null ? "" : reason;
    }

    public static RegistryResult denied(String reason) {
        return new RegistryResult(false, false, reason);
    }

    public static RegistryResult preview() {
        return new RegistryResult(false, false, "preview");
    }

    public static RegistryResult accepted() {
        return new RegistryResult(true, true, "accepted");
    }

    public static RegistryResult missed() {
        return new RegistryResult(true, false, "unknown");
    }
}
