/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.utils.accessor;

/**
 * Ostinato: the private input entry points auto-spectate drives, so a click or key goes through the same code a real
 * one does (1.21.11 keeps them private; 26.3 opened them up). Cast KeyboardHandler or MouseHandler to this.
 */
public interface IOstinatoInput {
    default void ostinato$keyPress(long window, int action, net.minecraft.client.input.KeyEvent e) {
        throw new UnsupportedOperationException();
    }

    default void ostinato$charTyped(long window, net.minecraft.client.input.CharacterEvent e) {
        throw new UnsupportedOperationException();
    }

    default void ostinato$onButton(long window, net.minecraft.client.input.MouseButtonInfo b, int action) {
        throw new UnsupportedOperationException();
    }

    default void ostinato$onMove(long window, double x, double y) {
        throw new UnsupportedOperationException();
    }
}
