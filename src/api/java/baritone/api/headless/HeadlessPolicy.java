/*
 * Ostinato: true-headless decisions. Not part of upstream Baritone.
 * Mixins apply these flags. They do not cancel the client tick.
 */
package baritone.api.headless;

import baritone.api.run.RunMode;

/**
 * What to cut while {@link RunMode.Value#HEADLESS} is on.
 * <p>
 * {@link RunMode.Value#NORMAL} and {@link RunMode.Value#HIDDEN} do not take this path. Hidden keeps its own
 * window, its GL context, and its 1 fps reducer. A null mode is not headless.
 * <p>
 * A process started with {@code -Dostinato.runMode=headless} never creates a GPU device or an SDL window.
 * {@link #keepsRealGpuDevice()} is false for that process. The client still builds a {@code Window} and a
 * {@code GpuDevice}, but both are stubs: {@link #FAKE_WINDOW_HANDLE} is not an SDL handle, and the device's
 * textures, buffers, and command encoder do nothing. {@code #set runMode normal} later cannot create the device
 * that was skipped. {@link #skipGpuWorkNow()} stays on for that process so a later mode change does not draw
 * with the stub.
 */
public final class HeadlessPolicy {

    /**
     * Upper bound passed to {@code limitDisplayFPS} while headless, when the caller's cap is higher or unlimited.
     * Same floor as hidden mode: the limiter sleeps the thread that pumps ticks, and the client catches up at most
     * 10 ticks per update. A 1 Hz sleep would stall the bot. A lower caller cap is kept.
     */
    public static final int LOOP_FLOOR_FPS = 20;

    /**
     * Stored on the stub {@code Window} when this process was created headless. Not an SDL window id.
     * Zero is the handle 26.3 rejects inside {@code Window.createWindow}.
     */
    public static final long FAKE_WINDOW_HANDLE = 1L;

    private HeadlessPolicy() {}

    /** True only for {@link RunMode.Value#HEADLESS}. */
    public static boolean active(RunMode.Value mode) {
        return mode == RunMode.Value.HEADLESS;
    }

    /**
     * False for headless: that mode does not create {@code GpuBackend.createDevice} or a GL context.
     * True for normal, hidden, and null. Hidden keeps the context so a later render-swap can show the window.
     */
    public static boolean keepsRealGpuDevice(RunMode.Value mode) {
        return !active(mode);
    }

    /**
     * Whether this process created a real GPU device. Reads {@link RunMode#windowCreateMode()}, which is
     * {@code -Dostinato.runMode} and does not follow a later {@code #set}.
     */
    public static boolean keepsRealGpuDevice() {
        return keepsRealGpuDevice(RunMode.windowCreateMode());
    }

    /** True when this process must not call SDL. Same process rule as {@link #keepsRealGpuDevice()}. */
    public static boolean skipSdl() {
        return !keepsRealGpuDevice();
    }

    /** Cancel {@code GameRenderer.renderLevel} on every frame. Headless also cancels {@code GameRenderer.render}. */
    public static boolean skipWorldDraw(RunMode.Value mode) {
        return active(mode);
    }

    /** Cancel {@code GameRenderer.render}, not only {@code renderLevel}. */
    public static boolean skipGameRenderer(RunMode.Value mode) {
        return active(mode);
    }

    /**
     * Cancel {@code GameRenderer.extract}. Overlay and GUI extract look up atlas sprites. Headless skips
     * {@code TextureAtlas.upload}, so those lookups throw {@code IllegalStateException} if extract still runs.
     */
    public static boolean skipExtract(RunMode.Value mode) {
        return active(mode);
    }

    /**
     * 26.3's render thread is the main thread. {@code Main} renames it and {@code RenderSystem.initRenderThread}
     * records it, before the window exists. Headless skips that call. {@code isOnRenderThread} still reports true
     * so a tick that asserts the thread does not throw. Hidden and normal still record the thread.
     */
    public static boolean skipRenderThread(RunMode.Value mode) {
        return active(mode);
    }

    /** Inverse of {@link #skipRenderThread}. Null starts one: null is not headless. */
    public static boolean startsRenderThread(RunMode.Value mode) {
        return !active(mode);
    }

    /**
     * Cap passed to {@code limitDisplayFPS}. Not headless: the caller's cap. Headless: {@code min(caller, 20)},
     * or 20 when the caller is unlimited ({@code <= 0}). Never raises the caller's cap.
     */
    public static int displayFpsCap(RunMode.Value mode, int callerCap) {
        if (!active(mode)) {
            return callerCap;
        }
        if (callerCap <= 0) {
            return LOOP_FLOOR_FPS;
        }
        return Math.min(callerCap, LOOP_FLOOR_FPS);
    }

    /** Skip {@code RenderSection.compileAsync} and {@code resortTransparency}. */
    public static boolean skipAsyncChunkMesh(RunMode.Value mode) {
        return active(mode);
    }

    /**
     * Headless skips {@code compileSync} too. There is no GPU buffer to finish, and a sync compile would ask the
     * stub device for one. Hidden and normal keep it: cancelling a compile that already owns a real buffer can leak it.
     */
    public static boolean skipCompileSync(RunMode.Value mode) {
        return active(mode);
    }

    /** Skip {@code TextureAtlas.cycleAnimationFrames}. */
    public static boolean skipTextureAnimation(RunMode.Value mode) {
        return active(mode);
    }

    /** Mip level for atlas stitch. Headless forces 0. Otherwise the caller's level. */
    public static int atlasMipLevel(RunMode.Value mode, int callerMip) {
        return active(mode) ? 0 : callerMip;
    }

    /** Skip {@code TextureAtlas.upload}. Headless has no sampler that needs a base atlas on a real device. */
    public static boolean skipAtlasUpload(RunMode.Value mode) {
        return active(mode);
    }

    /** Skip {@code FontManager.reload}. */
    public static boolean skipFontLoad(RunMode.Value mode) {
        return active(mode);
    }

    /** Skip {@code ShaderManager.reload} and {@code Minecraft.loadCriticalShaders}. */
    public static boolean skipShaderLoad(RunMode.Value mode) {
        return active(mode);
    }

    /**
     * Draw, atlas upload, font load, shader load, and sync mesh compile.
     * On when the current mode is headless, and also when this process was created headless, so a later
     * {@code #set runMode normal} does not draw through the stub device.
     */
    public static boolean skipGpuWorkNow() {
        return active(RunMode.current()) || !keepsRealGpuDevice();
    }

    /**
     * Cancel {@code SoundEngine.reload}, which is what calls {@code Library.init} and opens the OpenAL playback
     * device. The {@code SoundEngine} object still exists. {@code play} returns not-started while it was never loaded.
     */
    public static boolean skipSoundDeviceOpen(RunMode.Value mode) {
        return active(mode);
    }

    /** Report sound volume 0. Pairs with {@link #skipSoundDeviceOpen}. */
    public static boolean silenceSound(RunMode.Value mode) {
        return active(mode);
    }
}
