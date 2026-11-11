package baritone.swarm.center;

import baritone.api.hidden.HiddenWindowPolicy;

/**
 * Ostinato: the command-center window must not take focus when it opens or when it alerts.
 * A click in the window may focus it afterwards. Same idea as the game window's no-activate path.
 */
public final class FocusPolicy {

    /** {@code JFrame#setAutoRequestFocus(false)}. */
    public static final boolean AUTO_REQUEST_FOCUS = false;

    /** Do not call {@code toFront} or {@code requestFocus} on show. */
    public static final boolean ACTIVATE_ON_SHOW = false;

    /** After a show that did not activate, the window can take focus when the user clicks it. */
    public static final boolean FOCUSABLE_AFTER_SHOW = true;

    /** Status text is drawn in the window. No dialog, no beep, no focus steal. */
    public static final boolean ALERTS_STEAL_FOCUS = false;

    private FocusPolicy() {}

    /** {@code -Dostinato.windowPos=x,y}, or 2600,40 when that property is absent or not a pair. */
    public static int[] position(String windowPos) {
        return HiddenWindowPolicy.showPosition(windowPos);
    }
}
