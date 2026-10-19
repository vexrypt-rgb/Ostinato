/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.utils.accessor;

/** Ostinato: private screen and history state auto-spectate reads (menu slot positions, kick reason, typed commands). */
public interface IOstinatoScreens {
    /** AbstractContainerScreen: {leftPos, topPos}. */
    default int[] ostinato$pos() {
        return new int[]{0, 0};
    }

    /** DisconnectedScreen: the reason shown. */
    default net.minecraft.network.chat.Component ostinato$reason() {
        return null;
    }

    /** CommandHistory: drop the commands matching and save the file. */
    default void ostinato$scrub(java.util.function.Predicate<String> drop) {
    }
}
