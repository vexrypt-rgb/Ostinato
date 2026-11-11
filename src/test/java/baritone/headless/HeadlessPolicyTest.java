/*
 * Ostinato: true-headless policy. No Minecraft client and no SDL.
 */
package baritone.headless;

import baritone.api.BaritoneAPI;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.headless.HeadlessPolicy;
import baritone.api.headless.ViewCapability;
import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.HiddenWindowPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.run.RunMode;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.calc.PathBench;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class HeadlessPolicyTest {

    private String previousProperty;
    private RunMode.Value previousMode;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Before
    public void save() {
        previousProperty = System.getProperty(RunMode.PROPERTY);
        previousMode = BaritoneAPI.getSettings().runMode.value;
        clearProperty();
        RunMode.resetForTests();
        RenderSwap.resetForTests();
        BaritoneAPI.getSettings().runMode.value = RunMode.Value.NORMAL;
        RunMode.installStartup(BaritoneAPI.getSettings());
    }

    @After
    public void restore() {
        if (previousProperty == null) {
            clearProperty();
        } else {
            System.setProperty(RunMode.PROPERTY, previousProperty);
        }
        BaritoneAPI.getSettings().runMode.value = previousMode;
        RenderSwap.resetForTests();
        RunMode.resetForTests();
        RunMode.installStartup(BaritoneAPI.getSettings());
    }

    @Test
    public void headlessInitPathIsTakenOnlyWhenTheModeIsHeadless() {
        assertNotHeadless(RunMode.Value.NORMAL);
        assertNotHeadless(RunMode.Value.HIDDEN);
        assertNotHeadless(null);
        assertHeadless(RunMode.Value.HEADLESS);

        assertFalse(HiddenRenderPolicy.reduced(RunMode.Value.HEADLESS, false));
        assertTrue(HiddenRenderPolicy.shouldDrawWorld(RunMode.Value.HEADLESS, false, 0L, 0L));
        assertTrue(HeadlessPolicy.skipWorldDraw(RunMode.Value.HEADLESS));
        assertTrue(HeadlessPolicy.skipAtlasUpload(RunMode.Value.HEADLESS));
        assertTrue(HeadlessPolicy.skipCompileSync(RunMode.Value.HEADLESS));
        assertFalse(HeadlessPolicy.keepsRealGpuDevice(RunMode.Value.HEADLESS));
        assertTrue(HeadlessPolicy.keepsRealGpuDevice(RunMode.Value.NORMAL));
        assertTrue(HeadlessPolicy.keepsRealGpuDevice(RunMode.Value.HIDDEN));
        assertTrue(HeadlessPolicy.keepsRealGpuDevice(null));
        // The property is clear in this test, so the process methods still describe a normal client.
        assertTrue(HeadlessPolicy.keepsRealGpuDevice());
        assertFalse(HeadlessPolicy.skipSdl());
        assertFalse(HeadlessPolicy.skipGpuWorkNow());
        assertTrue(HeadlessPolicy.startsRenderThread(RunMode.windowCreateMode()));
        assertNotEquals(0L, HeadlessPolicy.FAKE_WINDOW_HANDLE);
        assertEquals(HiddenRenderPolicy.LOOP_FLOOR_FPS, HeadlessPolicy.LOOP_FLOOR_FPS);
        assertEquals(20, HeadlessPolicy.displayFpsCap(RunMode.Value.HEADLESS, 60));
        assertEquals(10, HeadlessPolicy.displayFpsCap(RunMode.Value.HEADLESS, 10));
        assertEquals(20, HeadlessPolicy.displayFpsCap(RunMode.Value.HEADLESS, 0));
        assertEquals(60, HeadlessPolicy.displayFpsCap(RunMode.Value.NORMAL, 60));
        assertEquals(60, HeadlessPolicy.displayFpsCap(RunMode.Value.HIDDEN, 60));
    }

    @Test
    public void propertyAndSettingBothSelectHeadlessAndLeaveTheOtherModesAlone() {
        clearProperty();
        RunMode.resetForTests();
        System.setProperty(RunMode.PROPERTY, "headless");
        assertEquals(RunMode.Value.HEADLESS, RunMode.windowCreateMode());
        assertTrue(HeadlessPolicy.active(RunMode.current()));
        assertTrue(ViewCapability.canStreamView(RunMode.current()));
        assertFalse(ViewCapability.canRenderSwap(RunMode.current()));

        RunMode.installStartup(BaritoneAPI.getSettings());
        assertEquals(RunMode.Value.HEADLESS, BaritoneAPI.getSettings().runMode.value);
        assertTrue(HeadlessPolicy.skipSoundDeviceOpen(RunMode.current()));

        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "normal");
        assertTrue(RunMode.isNormal());
        assertFalse(HeadlessPolicy.active(RunMode.current()));
        assertFalse(HeadlessPolicy.skipWorldDraw(RunMode.current()));
        assertFalse(HeadlessPolicy.skipGameRenderer(RunMode.current()));
        assertFalse(HeadlessPolicy.skipExtract(RunMode.current()));
        // The process was created headless. #set normal does not create the device that was skipped.
        assertFalse(HeadlessPolicy.keepsRealGpuDevice());
        assertTrue(HeadlessPolicy.skipSdl());
        assertTrue(HeadlessPolicy.skipGpuWorkNow());
        assertFalse(HeadlessPolicy.startsRenderThread(RunMode.windowCreateMode()));
        assertFalse(ViewCapability.canStreamView(RunMode.current()));
        assertFalse(ViewCapability.canRenderSwap(RunMode.current()));

        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "hidden");
        assertTrue(RunMode.isHidden());
        assertFalse(HeadlessPolicy.active(RunMode.current()));
        assertFalse(HeadlessPolicy.skipWorldDraw(RunMode.current()));
        assertFalse(HeadlessPolicy.skipAsyncChunkMesh(RunMode.current()));
        assertFalse(HeadlessPolicy.skipSoundDeviceOpen(RunMode.current()));
        assertTrue(ViewCapability.canRenderSwap(RunMode.current()));
        assertFalse(ViewCapability.canStreamView(RunMode.current()));
        assertFalse(HiddenWindowPolicy.showAfterCreate(RunMode.current(), true));

        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "headless");
        assertTrue(RunMode.isHeadless());
        assertTrue(HeadlessPolicy.skipAsyncChunkMesh(RunMode.current()));
        assertTrue(HeadlessPolicy.skipTextureAnimation(RunMode.current()));
        assertEquals(0, HeadlessPolicy.atlasMipLevel(RunMode.current(), 4));
        assertFalse(HiddenWindowPolicy.showAfterCreate(RunMode.current(), true));
        assertTrue(HiddenWindowPolicy.createHidden(RunMode.current(), false));
    }

    @Test
    public void presentIsIgnoredUnlessTheModeCanRenderSwap() {
        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "normal");
        RenderSwap.requestPresent();
        assertFalse(RenderSwap.isPresenting());

        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "headless");
        RenderSwap.requestPresent();
        assertFalse(RenderSwap.isPresenting());
        assertTrue(ViewCapability.canStreamView(RunMode.Value.HEADLESS));
        assertFalse(ViewCapability.canRenderSwap(RunMode.Value.HEADLESS));
        assertFalse(ViewCapability.canRenderSwap(null));
        assertFalse(ViewCapability.canStreamView(null));
        assertFalse(ViewCapability.canRenderSwap(RunMode.Value.NORMAL));
        assertFalse(ViewCapability.canStreamView(RunMode.Value.HIDDEN));

        SettingsUtil.parseAndApply(BaritoneAPI.getSettings(), "runmode", "hidden");
        RenderSwap.requestPresent();
        assertTrue(RenderSwap.isPresenting());
        assertEquals(RunMode.Value.HIDDEN, BaritoneAPI.getSettings().runMode.value);
        RenderSwap.releasePresent();
        assertFalse(RenderSwap.isPresenting());
    }

    @Test
    public void pathingGoalStillConstructsWhileHeadless() {
        clearProperty();
        RunMode.resetForTests();
        System.setProperty(RunMode.PROPERTY, "headless");
        assertEquals(RunMode.Value.HEADLESS, RunMode.windowCreateMode());
        assertTrue(HeadlessPolicy.active(RunMode.current()));

        GoalBlock goal = new GoalBlock(3, 64, 5);
        assertTrue(goal.isInGoal(3, 64, 5));
        assertFalse(goal.isInGoal(3, 64, 6));
        assertEquals(0.0, goal.heuristic(3, 64, 5), 0.0);
        assertTrue(goal.heuristic(4, 64, 5) > 0.0);

        PathingCommand command = new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
        assertEquals(goal, command.goal);
        assertEquals(PathingCommandType.SET_GOAL_AND_PATH, command.commandType);
        assertArrayEquals(new String[]{"goal", "path"}, new TabCompleteHelper().append("goal", "path").build());
    }

    @Test
    public void hiddenAndNormalStillKeepTheDeviceAndTheRenderThread() {
        clearProperty();
        RunMode.resetForTests();
        System.setProperty(RunMode.PROPERTY, "hidden");
        assertEquals(RunMode.Value.HIDDEN, RunMode.windowCreateMode());
        assertTrue(HeadlessPolicy.keepsRealGpuDevice());
        assertFalse(HeadlessPolicy.skipSdl());
        assertTrue(HeadlessPolicy.startsRenderThread(RunMode.windowCreateMode()));
        assertFalse(HeadlessPolicy.skipGpuWorkNow());
        assertFalse(HeadlessPolicy.skipAtlasUpload(RunMode.Value.HIDDEN));
        assertFalse(HeadlessPolicy.skipCompileSync(RunMode.Value.HIDDEN));
        assertFalse(HeadlessPolicy.skipGameRenderer(RunMode.Value.HIDDEN));
        assertFalse(HeadlessPolicy.skipExtract(RunMode.Value.HIDDEN));
        assertFalse(HeadlessPolicy.skipRenderThread(RunMode.Value.HIDDEN));

        clearProperty();
        RunMode.resetForTests();
        System.setProperty(RunMode.PROPERTY, "normal");
        assertTrue(HeadlessPolicy.keepsRealGpuDevice());
        assertTrue(HeadlessPolicy.startsRenderThread(RunMode.Value.NORMAL));
        assertTrue(HeadlessPolicy.startsRenderThread(null));
        assertFalse(HeadlessPolicy.skipRenderThread(RunMode.Value.NORMAL));
        assertFalse(HeadlessPolicy.skipGpuWorkNow());
    }

    private static void assertNotHeadless(RunMode.Value mode) {
        assertFalse(HeadlessPolicy.active(mode));
        assertFalse(HeadlessPolicy.skipWorldDraw(mode));
        assertFalse(HeadlessPolicy.skipAsyncChunkMesh(mode));
        assertFalse(HeadlessPolicy.skipTextureAnimation(mode));
        assertFalse(HeadlessPolicy.skipSoundDeviceOpen(mode));
        assertFalse(HeadlessPolicy.silenceSound(mode));
        assertFalse(HeadlessPolicy.skipGameRenderer(mode));
        assertFalse(HeadlessPolicy.skipExtract(mode));
        assertFalse(HeadlessPolicy.skipRenderThread(mode));
        assertTrue(HeadlessPolicy.startsRenderThread(mode));
        assertFalse(HeadlessPolicy.skipAtlasUpload(mode));
        assertFalse(HeadlessPolicy.skipCompileSync(mode));
        assertFalse(HeadlessPolicy.skipFontLoad(mode));
        assertFalse(HeadlessPolicy.skipShaderLoad(mode));
        assertTrue(HeadlessPolicy.keepsRealGpuDevice(mode));
        assertEquals(4, HeadlessPolicy.atlasMipLevel(mode, 4));
        assertFalse(ViewCapability.canStreamView(mode));
    }

    private static void assertHeadless(RunMode.Value mode) {
        assertTrue(HeadlessPolicy.active(mode));
        assertTrue(HeadlessPolicy.skipWorldDraw(mode));
        assertTrue(HeadlessPolicy.skipAsyncChunkMesh(mode));
        assertTrue(HeadlessPolicy.skipTextureAnimation(mode));
        assertTrue(HeadlessPolicy.skipSoundDeviceOpen(mode));
        assertTrue(HeadlessPolicy.silenceSound(mode));
        assertTrue(HeadlessPolicy.skipGameRenderer(mode));
        assertTrue(HeadlessPolicy.skipExtract(mode));
        assertTrue(HeadlessPolicy.skipRenderThread(mode));
        assertFalse(HeadlessPolicy.startsRenderThread(mode));
        assertTrue(HeadlessPolicy.skipAtlasUpload(mode));
        assertTrue(HeadlessPolicy.skipCompileSync(mode));
        assertTrue(HeadlessPolicy.skipFontLoad(mode));
        assertTrue(HeadlessPolicy.skipShaderLoad(mode));
        assertFalse(HeadlessPolicy.keepsRealGpuDevice(mode));
        assertEquals(0, HeadlessPolicy.atlasMipLevel(mode, 4));
        assertTrue(ViewCapability.canStreamView(mode));
        assertFalse(ViewCapability.canRenderSwap(mode));
    }

    private static void clearProperty() {
        System.clearProperty(RunMode.PROPERTY);
    }
}
