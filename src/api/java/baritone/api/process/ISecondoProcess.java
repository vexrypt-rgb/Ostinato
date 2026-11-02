/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.api.process;

/**
 * Ostinato: companion coordinator. Idle unless {@code secondoEnabled} is on and a follow session is bound.
 * Primo is the player being followed. This process asks Baritone for a {@code GoalNear}; it does not own path search.
 */
public interface ISecondoProcess extends IBaritoneProcess {

    /**
     * Bind to the nearest other player. Pathing stays idle while {@code secondoEnabled} is off.
     *
     * @return a one-line status
     */
    String followNearest();

    /**
     * Bind to the named player. The name is matched when that player is loaded.
     *
     * @param name player name
     * @return a one-line status
     */
    String followNamed(String name);

    /** Clear the bind. */
    String stop();

    /** One line: bind, mode, and which planned features are absent, off, or ready. */
    String status();

    /** True after follow, until stop or until another process takes pathing and cancels this one. */
    boolean running();

    /** Bound name, or null when following whoever is nearest (or when stopped). */
    String primoName();
}
