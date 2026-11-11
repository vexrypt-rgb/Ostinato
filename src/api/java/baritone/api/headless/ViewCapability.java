/*
 * Ostinato: what a later viewer can attach. Not a stream, not a socket, and not a POV switcher.
 */
package baritone.api.headless;

import baritone.api.run.RunMode;

/**
 * Capability advertisement for Phase 4. Nothing in this class opens a connection.
 * <p>
 * A hidden bot kept a GL context, so a later render-swap can show its window. A headless bot has no renderer to
 * swap. A later full stream (position, view angle, dimension, nearby chunks, entities, health, hunger, inventory)
 * is how that bot would be viewed. This class only answers which of those two a mode allows.
 */
public final class ViewCapability {

    private ViewCapability() {}

    /** True only for {@link RunMode.Value#HIDDEN}. Normal, headless, and null cannot render-swap. */
    public static boolean canRenderSwap(RunMode.Value mode) {
        return mode == RunMode.Value.HIDDEN;
    }

    /** True only for {@link RunMode.Value#HEADLESS}. Normal, hidden, and null cannot stream a view. */
    public static boolean canStreamView(RunMode.Value mode) {
        return mode == RunMode.Value.HEADLESS;
    }
}
