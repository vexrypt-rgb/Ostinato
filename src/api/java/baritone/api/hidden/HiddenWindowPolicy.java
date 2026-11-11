/*
 * Ostinato: hidden-mode window decisions. Not part of upstream Baritone.
 * The SDL calls live in the launch source set. This class is the flag math the mixins follow.
 */
package baritone.api.hidden;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;

/**
 * Whether the SDL window is created hidden, shown, or left alone.
 * <p>
 * {@link RunMode.Value#NORMAL} with no window position matches the client from before this mode: the window is
 * created visible and the mouse grab is unchanged. {@link RunMode.Value#NORMAL} with {@code -Dostinato.windowPos}
 * still creates the window hidden, moves it, and shows it without activating. {@link RunMode.Value#HIDDEN} creates
 * the window hidden and does not show it. The GL context is created for hidden, because the hidden flag is only
 * OR'd into the window flags. {@link RunMode.Value#HEADLESS} uses the same stay-hidden answers so a later
 * {@code #set runMode headless} on a real window does not show it. A process that was created headless never
 * builds that window: {@link HeadlessPolicy#keepsRealGpuDevice()} is false and the SDL calls are not made.
 * A present does not show a headless window.
 */
public final class HiddenWindowPolicy {

    /** Where a hidden window is shown when {@code -Dostinato.windowPos} is unset. Does not take focus. */
    public static final int DEFAULT_SHOW_X = 2600;
    public static final int DEFAULT_SHOW_Y = 40;

    /**
     * SDL size while hidden. The 26.3 client clamps to this ({@code Window.MIN_WINDOW_WIDTH} / {@code HEIGHT}).
     * A 16px framebuffer was the ask; it cannot be applied.
     */
    public static final int APPLIED_WIDTH = 320;
    public static final int APPLIED_HEIGHT = 240;

    public enum Step {
        /** Leave the window as it is. */
        NONE,
        /** Hide it, and shrink it. The GL context stays. */
        HIDE,
        /** Show it at {@link #showPosition} without raising it. */
        SHOW
    }

    private HiddenWindowPolicy() {}

    /**
     * True when a real {@code createWindow} must OR in {@code SDL_WINDOW_HIDDEN}. Hidden still creates the GL context.
     * A process created as headless never reaches that call.
     */
    public static boolean createHidden(RunMode.Value mode, boolean windowPos) {
        return stayHidden(mode) || windowPos;
    }

    /** True when the window should be shown at the end of construction. Hidden and headless do not show. */
    public static boolean showAfterCreate(RunMode.Value mode, boolean windowPos) {
        return !stayHidden(mode) && windowPos;
    }

    /** True when show and raise must not activate the window. */
    public static boolean suppressActivate(RunMode.Value mode, boolean windowPos) {
        return createHidden(mode, windowPos);
    }

    /**
     * True when grab and release must not touch the OS cursor.
     * Hidden, and a render-swap present, always suppress. Otherwise the old {@code windowPos} / {@code noMouseGrab}
     * rule applies, so a normal client with neither flag is unchanged.
     */
    public static boolean suppressMouseGrab(RunMode.Value mode, boolean presenting, boolean windowPos, boolean noMouseGrabFlag) {
        if (stayHidden(mode) || presenting) {
            return true;
        }
        return windowPos || noMouseGrabFlag;
    }

    /**
     * One transition. {@code currentlyHidden} is the last applied hidden state, not a fresh SDL query.
     * Presenting a hidden bot shows the window and does not change the stored mode. Presenting a headless bot does
     * not show it: headless has no render-swap.
     */
    public static Step step(boolean currentlyHidden, RunMode.Value mode, boolean presenting) {
        boolean wantHidden = HeadlessPolicy.active(mode(mode)) || (mode(mode) == RunMode.Value.HIDDEN && !presenting);
        if (wantHidden && !currentlyHidden) {
            return Step.HIDE;
        }
        if (!wantHidden && currentlyHidden) {
            return Step.SHOW;
        }
        return Step.NONE;
    }

    /**
     * Where to place the window when it is shown again. A valid {@code x,y} property wins. Anything else is
     * {@link #DEFAULT_SHOW_X}, {@link #DEFAULT_SHOW_Y}.
     */
    public static int[] showPosition(String windowPos) {
        int[] parsed = parsePair(windowPos);
        if (parsed == null) {
            return new int[]{DEFAULT_SHOW_X, DEFAULT_SHOW_Y};
        }
        return parsed;
    }

    /**
     * The same pair parser the window-position mixin used. Null when the property is absent or the first two
     * comma-separated tokens are not integers. Extra tokens after {@code x,y} are ignored, as they were before.
     */
    public static int[] parsePair(String windowPos) {
        if (windowPos == null) {
            return null;
        }
        try {
            String[] s = windowPos.split(",");
            return new int[]{Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim())};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Hidden, and headless, stay unshown. Null is not either of those. Headless may have no SDL window at all. */
    private static boolean stayHidden(RunMode.Value mode) {
        RunMode.Value resolved = mode(mode);
        return resolved == RunMode.Value.HIDDEN || HeadlessPolicy.active(resolved);
    }

    private static RunMode.Value mode(RunMode.Value mode) {
        return mode == null ? RunMode.Value.NORMAL : mode;
    }
}
