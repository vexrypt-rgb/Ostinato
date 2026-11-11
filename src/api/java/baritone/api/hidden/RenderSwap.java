/*
 * Ostinato: a small hook a later render-swap can call. Not a POV switcher and not a command-center button.
 */
package baritone.api.hidden;

import baritone.api.headless.ViewCapability;
import baritone.api.run.RunMode;

/**
 * Presents one hidden bot's window without changing the stored {@code runMode}.
 * <p>
 * {@link #requestPresent()} turns render reduction off and, on the next client tick, shows the SDL window at
 * {@code -Dostinato.windowPos} or the default 2600,40. The window is not raised and the OS cursor is not grabbed.
 * It does nothing unless {@link ViewCapability#canRenderSwap} is true for the current mode, so a normal client and
 * a headless bot are left alone. {@link #releasePresent()} hides it again when the mode is still hidden. Phase 4
 * builds the switcher on top of this. This class does not open a stream.
 */
public final class RenderSwap {

    private static volatile boolean presenting;

    private RenderSwap() {}

    /**
     * Show this bot and resume its rendering. Does not write {@code runMode}. No-op unless the current mode can
     * render-swap, which is hidden only.
     */
    public static void requestPresent() {
        if (!ViewCapability.canRenderSwap(RunMode.current())) {
            return;
        }
        presenting = true;
    }

    /** Hide again if the mode is still hidden, and resume render reduction. */
    public static void releasePresent() {
        presenting = false;
    }

    public static boolean isPresenting() {
        return presenting;
    }

    /** Tests only. */
    public static void resetForTests() {
        presenting = false;
    }
}
