/*
 * Ostinato: hidden-mode render decisions. Not part of upstream Baritone.
 * Mixins apply these flags. They do not cancel the render thread.
 */
package baritone.api.hidden;

import baritone.api.run.RunMode;

/**
 * What to cut while {@link RunMode.Value#HIDDEN} is on and the bot is not being presented.
 * <p>
 * The render thread still runs. {@code renderFrame} and {@code runTick} are not skipped. World drawing is limited
 * to about {@link #RENDERED_FPS} by cancelling {@code GameRenderer.renderLevel} on the frames in between. The display
 * limiter is capped at {@link #LOOP_FLOOR_FPS}, not at 1. {@code FramerateLimiter.limitDisplayFPS} sleeps the same
 * thread that pumps ticks, and the client catches up at most 10 ticks per update, so a 1 Hz sleep would stall the bot.
 * <p>
 * {@link RunMode.Value#NORMAL} and {@link RunMode.Value#HEADLESS} return the caller's values. Headless cuts are in
 * {@link baritone.api.headless.HeadlessPolicy}, not here, so a hidden bot's 1 fps path is not reused as "no renderer".
 * Presenting a hidden bot ({@code presenting == true}) also returns the caller's values, so a later render-swap can
 * show this window with rendering resumed. The stored mode is not changed.
 */
public final class HiddenRenderPolicy {

    /** How often a hidden bot draws the world. Not the display-limiter argument. */
    public static final int RENDERED_FPS = 1;

    /** Nanoseconds between world draws while reduced. */
    public static final long RENDER_INTERVAL_NS = 1_000_000_000L;

    /**
     * Upper bound passed to {@code limitDisplayFPS} while reduced, when the caller's cap is higher or unlimited.
     * A lower caller cap is kept. 20 leaves room for one tick per update.
     */
    public static final int LOOP_FLOOR_FPS = 20;

    /**
     * The size asked for. The 26.3 window clamps to {@link #APPLIED_FRAMEBUFFER_WIDTH} by
     * {@link #APPLIED_FRAMEBUFFER_HEIGHT} ({@code Window.MIN_WINDOW_WIDTH} / {@code MIN_WINDOW_HEIGHT}), so a real
     * window is never set to this edge.
     */
    public static final int REQUESTED_FRAMEBUFFER_EDGE = 16;

    /** SDL window size applied while reduced. Same values {@link HiddenWindowPolicy} resizes to. */
    public static final int APPLIED_FRAMEBUFFER_WIDTH = HiddenWindowPolicy.APPLIED_WIDTH;
    public static final int APPLIED_FRAMEBUFFER_HEIGHT = HiddenWindowPolicy.APPLIED_HEIGHT;

    private HiddenRenderPolicy() {}

    /** True only for hidden, and only while that bot is not being presented. */
    public static boolean reduced(RunMode.Value mode, boolean presenting) {
        return mode == RunMode.Value.HIDDEN && !presenting;
    }

    /**
     * Cap passed to {@code limitDisplayFPS}. Not reduced: the caller's cap, unchanged. Reduced: {@code min(caller, 20)},
     * or 20 when the caller is unlimited ({@code <= 0}). Never raises the caller's cap.
     */
    public static int displayFpsCap(RunMode.Value mode, boolean presenting, int callerCap) {
        if (!reduced(mode, presenting)) {
            return callerCap;
        }
        if (callerCap <= 0) {
            return LOOP_FLOOR_FPS;
        }
        return Math.min(callerCap, LOOP_FLOOR_FPS);
    }

    /** {@link #RENDERED_FPS} while reduced, otherwise {@code -1} (do not impose a world-draw cap). */
    public static int renderedFpsCap(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting) ? RENDERED_FPS : -1;
    }

    /** {@link #REQUESTED_FRAMEBUFFER_EDGE} while reduced, otherwise {@code -1}. */
    public static int requestedFramebufferEdge(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting) ? REQUESTED_FRAMEBUFFER_EDGE : -1;
    }

    /** {@link #APPLIED_FRAMEBUFFER_WIDTH} while reduced, otherwise {@code -1}. */
    public static int appliedFramebufferWidth(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting) ? APPLIED_FRAMEBUFFER_WIDTH : -1;
    }

    /** {@link #APPLIED_FRAMEBUFFER_HEIGHT} while reduced, otherwise {@code -1}. */
    public static int appliedFramebufferHeight(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting) ? APPLIED_FRAMEBUFFER_HEIGHT : -1;
    }

    /** Mip level for atlas stitch. Reduced forces 0. Otherwise the caller's level. */
    public static int atlasMipLevel(RunMode.Value mode, boolean presenting, int callerMip) {
        return reduced(mode, presenting) ? 0 : callerMip;
    }

    /** Skip {@code RenderSection.compileAsync} and {@code resortTransparency}. {@code compileSync} is not skipped. */
    public static boolean skipChunkMeshBuild(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting);
    }

    /** Skip animated texture uploads ({@code TextureAtlas.cycleAnimationFrames}). The base atlas upload still runs. */
    public static boolean skipTextureUpload(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting);
    }

    /** Force the atlas mip argument to 0. High-resolution mip decode is the part that is skipped. */
    public static boolean skipHighResAtlasDecode(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting);
    }

    /** Report sound volume 0. The sound engine is not shut down. */
    public static boolean silenceSound(RunMode.Value mode, boolean presenting) {
        return reduced(mode, presenting);
    }

    /**
     * Whether this frame should draw the world. Not reduced: always. Reduced: the first frame
     * ({@code lastDrawNs < 0}) and then at most once per {@link #RENDER_INTERVAL_NS}.
     */
    public static boolean shouldDrawWorld(RunMode.Value mode, boolean presenting, long lastDrawNs, long nowNs) {
        if (!reduced(mode, presenting)) {
            return true;
        }
        if (lastDrawNs < 0) {
            return true;
        }
        return nowNs - lastDrawNs >= RENDER_INTERVAL_NS;
    }
}
