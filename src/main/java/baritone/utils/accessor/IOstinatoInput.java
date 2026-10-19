/*
 * Ostinato: new file (auto-spectate on 1.21.4). Not part of upstream Baritone.
 */
package baritone.utils.accessor;

/** Ostinato: the private keyboard and mouse entry points, for humanized input that goes through the real paths. */
public interface IOstinatoInput {
    default void ostinato$keyPress(long window, int key, int scancode, int action, int mods) {
        throw new UnsupportedOperationException();
    }

    default void ostinato$charTyped(long window, int codepoint, int mods) {
        throw new UnsupportedOperationException();
    }

    default void ostinato$onButton(long window, int button, int action, int mods) {
        throw new UnsupportedOperationException();
    }

    default void ostinato$onMove(long window, double x, double y) {
        throw new UnsupportedOperationException();
    }
}
