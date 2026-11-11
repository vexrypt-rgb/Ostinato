/*
 * Ostinato: hidden render-reduction flags. No Minecraft client.
 */
package baritone.hidden;

import baritone.api.BaritoneAPI;
import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.PathBench;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HiddenRenderPolicyTest {

    private RunMode.Value previousMode;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Before
    public void save() {
        previousMode = BaritoneAPI.getSettings().runMode.value;
        RenderSwap.resetForTests();
        RunMode.resetForTests();
        RunMode.installStartup(BaritoneAPI.getSettings());
    }

    @After
    public void restore() {
        BaritoneAPI.getSettings().runMode.value = previousMode;
        RenderSwap.resetForTests();
        RunMode.resetForTests();
        RunMode.installStartup(BaritoneAPI.getSettings());
    }

    @Test
    public void normalAndHeadlessDoNotReduce() {
        assertUnchanged(RunMode.Value.NORMAL);
        assertUnchanged(RunMode.Value.HEADLESS);
        assertUnchanged(null);
    }

    @Test
    public void hiddenReducesAndPresentingDoesNot() {
        assertReduced(RunMode.Value.HIDDEN, false);
        assertUnchanged(RunMode.Value.HIDDEN, true);
    }

    @Test
    public void displayCapNeverRaisesTheCallerAndDoesNotSleepAtOneHertz() {
        assertEquals(60, HiddenRenderPolicy.displayFpsCap(RunMode.Value.NORMAL, false, 60));
        assertEquals(30, HiddenRenderPolicy.displayFpsCap(RunMode.Value.NORMAL, false, 30));
        assertEquals(10, HiddenRenderPolicy.displayFpsCap(RunMode.Value.HIDDEN, false, 10));
        assertEquals(HiddenRenderPolicy.LOOP_FLOOR_FPS, HiddenRenderPolicy.displayFpsCap(RunMode.Value.HIDDEN, false, 30));
        assertEquals(HiddenRenderPolicy.LOOP_FLOOR_FPS, HiddenRenderPolicy.displayFpsCap(RunMode.Value.HIDDEN, false, 260));
        assertEquals(HiddenRenderPolicy.LOOP_FLOOR_FPS, HiddenRenderPolicy.displayFpsCap(RunMode.Value.HIDDEN, false, 0));
        assertEquals(HiddenRenderPolicy.LOOP_FLOOR_FPS, HiddenRenderPolicy.displayFpsCap(RunMode.Value.HIDDEN, false, -1));
        assertEquals(60, HiddenRenderPolicy.displayFpsCap(RunMode.Value.HIDDEN, true, 60));
        assertEquals(20, HiddenRenderPolicy.LOOP_FLOOR_FPS);
        assertEquals(1, HiddenRenderPolicy.RENDERED_FPS);
        assertTrue(HiddenRenderPolicy.LOOP_FLOOR_FPS > HiddenRenderPolicy.RENDERED_FPS);
    }

    @Test
    public void worldDrawIsAboutOnePerSecondWhileHidden() {
        long t0 = 1_000_000_000L;
        assertTrue(HiddenRenderPolicy.shouldDrawWorld(RunMode.Value.HIDDEN, false, -1L, t0));
        assertFalse(HiddenRenderPolicy.shouldDrawWorld(RunMode.Value.HIDDEN, false, t0, t0 + HiddenRenderPolicy.RENDER_INTERVAL_NS - 1));
        assertTrue(HiddenRenderPolicy.shouldDrawWorld(RunMode.Value.HIDDEN, false, t0, t0 + HiddenRenderPolicy.RENDER_INTERVAL_NS));
        assertTrue(HiddenRenderPolicy.shouldDrawWorld(RunMode.Value.NORMAL, false, t0, t0));
        assertTrue(HiddenRenderPolicy.shouldDrawWorld(RunMode.Value.HIDDEN, true, t0, t0));
        assertEquals(HiddenRenderPolicy.RENDER_INTERVAL_NS, 1_000_000_000L);
    }

    @Test
    public void framebufferAsksForSixteenAndAppliesTheClientMinimum() {
        assertEquals(16, HiddenRenderPolicy.REQUESTED_FRAMEBUFFER_EDGE);
        assertEquals(320, HiddenRenderPolicy.APPLIED_FRAMEBUFFER_WIDTH);
        assertEquals(240, HiddenRenderPolicy.APPLIED_FRAMEBUFFER_HEIGHT);
        assertTrue(HiddenRenderPolicy.APPLIED_FRAMEBUFFER_WIDTH > HiddenRenderPolicy.REQUESTED_FRAMEBUFFER_EDGE);
        assertEquals(16, HiddenRenderPolicy.requestedFramebufferEdge(RunMode.Value.HIDDEN, false));
        assertEquals(320, HiddenRenderPolicy.appliedFramebufferWidth(RunMode.Value.HIDDEN, false));
        assertEquals(240, HiddenRenderPolicy.appliedFramebufferHeight(RunMode.Value.HIDDEN, false));
        assertEquals(-1, HiddenRenderPolicy.requestedFramebufferEdge(RunMode.Value.NORMAL, false));
        assertEquals(-1, HiddenRenderPolicy.appliedFramebufferWidth(RunMode.Value.HEADLESS, false));
        assertEquals(-1, HiddenRenderPolicy.appliedFramebufferHeight(RunMode.Value.HIDDEN, true));
    }

    @Test
    public void flagsFollowTheSettingOnlyWhileHidden() {
        BaritoneAPI.getSettings().runMode.value = RunMode.Value.NORMAL;
        assertFalse(HiddenRenderPolicy.skipChunkMeshBuild(RunMode.current(), RenderSwap.isPresenting()));
        assertFalse(HiddenRenderPolicy.skipTextureUpload(RunMode.current(), RenderSwap.isPresenting()));
        assertFalse(HiddenRenderPolicy.skipHighResAtlasDecode(RunMode.current(), RenderSwap.isPresenting()));
        assertFalse(HiddenRenderPolicy.silenceSound(RunMode.current(), RenderSwap.isPresenting()));

        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "hidden");
        assertTrue(RunMode.isHidden());
        assertTrue(HiddenRenderPolicy.skipChunkMeshBuild(RunMode.current(), RenderSwap.isPresenting()));
        assertTrue(HiddenRenderPolicy.skipTextureUpload(RunMode.current(), RenderSwap.isPresenting()));
        assertTrue(HiddenRenderPolicy.skipHighResAtlasDecode(RunMode.current(), RenderSwap.isPresenting()));
        assertTrue(HiddenRenderPolicy.silenceSound(RunMode.current(), RenderSwap.isPresenting()));
        assertEquals(0, HiddenRenderPolicy.atlasMipLevel(RunMode.current(), RenderSwap.isPresenting(), 4));
        assertEquals(1, HiddenRenderPolicy.renderedFpsCap(RunMode.current(), RenderSwap.isPresenting()));

        RenderSwap.requestPresent();
        assertTrue(RenderSwap.isPresenting());
        assertEquals(RunMode.Value.HIDDEN, BaritoneAPI.getSettings().runMode.value);
        assertFalse(HiddenRenderPolicy.reduced(RunMode.current(), RenderSwap.isPresenting()));
        assertFalse(HiddenRenderPolicy.skipChunkMeshBuild(RunMode.current(), RenderSwap.isPresenting()));
        assertEquals(4, HiddenRenderPolicy.atlasMipLevel(RunMode.current(), RenderSwap.isPresenting(), 4));

        RenderSwap.releasePresent();
        assertTrue(HiddenRenderPolicy.reduced(RunMode.current(), RenderSwap.isPresenting()));

        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "headless");
        assertTrue(RunMode.isHeadless());
        assertFalse(RunMode.isHidden());
        assertFalse(HiddenRenderPolicy.reduced(RunMode.current(), false));
        assertEquals(4, HiddenRenderPolicy.atlasMipLevel(RunMode.current(), false, 4));
    }

    private static void assertUnchanged(RunMode.Value mode) {
        assertUnchanged(mode, false);
    }

    private static void assertUnchanged(RunMode.Value mode, boolean presenting) {
        assertFalse(HiddenRenderPolicy.reduced(mode, presenting));
        assertEquals(-1, HiddenRenderPolicy.renderedFpsCap(mode, presenting));
        assertEquals(-1, HiddenRenderPolicy.requestedFramebufferEdge(mode, presenting));
        assertEquals(-1, HiddenRenderPolicy.appliedFramebufferWidth(mode, presenting));
        assertEquals(-1, HiddenRenderPolicy.appliedFramebufferHeight(mode, presenting));
        assertEquals(4, HiddenRenderPolicy.atlasMipLevel(mode, presenting, 4));
        assertFalse(HiddenRenderPolicy.skipChunkMeshBuild(mode, presenting));
        assertFalse(HiddenRenderPolicy.skipTextureUpload(mode, presenting));
        assertFalse(HiddenRenderPolicy.skipHighResAtlasDecode(mode, presenting));
        assertFalse(HiddenRenderPolicy.silenceSound(mode, presenting));
        assertTrue(HiddenRenderPolicy.shouldDrawWorld(mode, presenting, 50L, 50L));
    }

    private static void assertReduced(RunMode.Value mode, boolean presenting) {
        assertTrue(HiddenRenderPolicy.reduced(mode, presenting));
        assertEquals(1, HiddenRenderPolicy.renderedFpsCap(mode, presenting));
        assertEquals(0, HiddenRenderPolicy.atlasMipLevel(mode, presenting, 4));
        assertTrue(HiddenRenderPolicy.skipChunkMeshBuild(mode, presenting));
        assertTrue(HiddenRenderPolicy.skipTextureUpload(mode, presenting));
        assertTrue(HiddenRenderPolicy.skipHighResAtlasDecode(mode, presenting));
        assertTrue(HiddenRenderPolicy.silenceSound(mode, presenting));
    }
}
