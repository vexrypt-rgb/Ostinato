package baritone.api.command;

/**
 * Ostinato: kind of one declared command argument. Forms and the unified registry read this.
 * It does not replace {@code IArgConsumer} parsing inside {@code execute}.
 */
public enum ArgKind {
    TOKEN,
    INT,
    ENUM,
    REST
}
