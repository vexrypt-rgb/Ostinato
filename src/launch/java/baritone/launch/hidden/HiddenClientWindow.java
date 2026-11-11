/*
 * Ostinato: apply the hidden-window policy to the SDL window. The GL context is kept.
 * This class does not call SDL_DestroyWindow, Window.close, or SDL_RaiseWindow.
 */
package baritone.launch.hidden;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.hidden.HiddenWindowPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * SDL side of hidden mode. Decisions are in {@link HiddenWindowPolicy}.
 * <p>
 * A hidden window is created with {@code SDL_WINDOW_HIDDEN} and is not shown. The GPU backend still creates the
 * context for that window. While hidden, the window is resized to the client's minimum (320x240). The 16px edge in
 * the policy cannot be applied: the client clamps below that. The previous size is restored when the window is shown.
 * Show uses the no-activate hints and never raises the window.
 * <p>
 * A process created as {@code headless} has no SDL window. {@link #afterCreate} and {@link #onTick} return before
 * any SDL call. The handle stored on the stub window is not an SDL id.
 */
public final class HiddenClientWindow {

    private static volatile boolean hiddenApplied;
    private static int savedW = -1;
    private static int savedH = -1;

    private HiddenClientWindow() {}

    /** Hints so a later {@code SDL_ShowWindow} does not activate or focus the window. */
    public static void armNoActivateHints() {
        // Ostinato: same hints the windowPos path already set. Do not raise the window.
        SDLHints.SDL_SetHint("SDL_WINDOW_ACTIVATE_WHEN_SHOWN", "0");
        SDLHints.SDL_SetHint("SDL_WINDOW_ACTIVATE_WHEN_RAISED", "0");
    }

    /**
     * End of {@code Window}'s constructor. Positions when {@code windowPos} is set. Shows only when the policy says
     * so, which is the old windowPos path and not hidden mode.
     */
    public static void afterCreate(long handle, RunMode.Value mode, int[] windowPos) {
        // Ostinato: headless stores a fake handle. Do not pass it to SDL. A later #set cannot create the window.
        if (!HeadlessPolicy.keepsRealGpuDevice() || handle == 0L) {
            return;
        }
        boolean hasPos = windowPos != null;
        if (hasPos) {
            SDLVideo.SDL_SetWindowPosition(handle, windowPos[0], windowPos[1]);
        }
        // Ostinato: hidden and headless both stay unshown and use the client minimum size. Headless never shows later.
        if (mode == RunMode.Value.HIDDEN || mode == RunMode.Value.HEADLESS) {
            rememberSize(handle);
            applyTiny(handle);
            hiddenApplied = true;
        }
        if (HiddenWindowPolicy.showAfterCreate(mode, hasPos)) {
            SDLVideo.SDL_ShowWindow(handle);
        }
    }

    /** Once per client tick. Does not skip the tick. */
    public static void onTick() {
        // Ostinato: same process rule as afterCreate. #set headless on a real window still hides that window.
        if (!HeadlessPolicy.keepsRealGpuDevice()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        var window = mc.getWindow();
        if (window == null) {
            return;
        }
        long handle = window.handle();
        if (handle == 0L) {
            return;
        }
        RunMode.Value mode = RunMode.current();
        boolean presenting = RenderSwap.isPresenting();
        switch (HiddenWindowPolicy.step(hiddenApplied, mode, presenting)) {
            case HIDE -> hide(mc, handle);
            case SHOW -> show(handle);
            case NONE -> {
            }
        }
    }

    /** Tests and a fresh client. Does not touch SDL. */
    public static void resetForTests() {
        hiddenApplied = false;
        savedW = -1;
        savedH = -1;
    }

    public static boolean hiddenApplied() {
        return hiddenApplied;
    }

    private static void hide(Minecraft mc, long handle) {
        rememberSize(handle);
        applyTiny(handle);
        SDLVideo.SDL_HideWindow(handle);
        hiddenApplied = true;
        // Ostinato: drop queued async mesh work. An in-flight compile is left alone so its buffer is finished.
        // A missing renderer must not kill the tick.
        try {
            LevelRenderer levelRenderer = mc.levelRenderer;
            if (levelRenderer == null) {
                return;
            }
            SectionRenderDispatcher dispatcher = levelRenderer.sectionRenderDispatcher();
            if (dispatcher != null) {
                dispatcher.clearCompileQueue();
            }
        } catch (RuntimeException e) {
            System.err.println("Ostinato: hidden mode could not clear the chunk compile queue: " + e.getMessage());
        }
    }

    private static void show(long handle) {
        armNoActivateHints();
        int[] pos = HiddenWindowPolicy.showPosition(System.getProperty("ostinato.windowPos"));
        SDLVideo.SDL_SetWindowPosition(handle, pos[0], pos[1]);
        if (savedW > 0 && savedH > 0) {
            SDLVideo.SDL_SetWindowSize(handle, savedW, savedH);
        }
        SDLVideo.SDL_ShowWindow(handle);
        // Ostinato: SDL_RaiseWindow would steal focus. Do not call it.
        hiddenApplied = false;
    }

    private static void applyTiny(long handle) {
        SDLVideo.SDL_SetWindowSize(handle, HiddenWindowPolicy.APPLIED_WIDTH, HiddenWindowPolicy.APPLIED_HEIGHT);
    }

    /** Remember a non-tiny size. A later shrink must not overwrite the size we will restore. */
    private static void rememberSize(long handle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.callocInt(1);
            IntBuffer h = stack.callocInt(1);
            if (!SDLVideo.SDL_GetWindowSize(handle, w, h)) {
                return;
            }
            int ww = w.get(0);
            int hh = h.get(0);
            if (ww == HiddenWindowPolicy.APPLIED_WIDTH && hh == HiddenWindowPolicy.APPLIED_HEIGHT) {
                return;
            }
            if (ww > 0 && hh > 0) {
                savedW = ww;
                savedH = hh;
            }
        }
    }
}
