/*
 * Ostinato: hidden-window flag math. No SDL and no Minecraft client.
 */
package baritone.hidden;

import baritone.api.hidden.HiddenWindowPolicy;
import baritone.api.run.RunMode;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HiddenWindowPolicyTest {

    @Test
    public void normalWithoutWindowPosIsUnchanged() {
        assertFalse(HiddenWindowPolicy.createHidden(RunMode.Value.NORMAL, false));
        assertFalse(HiddenWindowPolicy.showAfterCreate(RunMode.Value.NORMAL, false));
        assertFalse(HiddenWindowPolicy.suppressActivate(RunMode.Value.NORMAL, false));
        assertFalse(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.NORMAL, false, false, false));
        assertEquals(HiddenWindowPolicy.Step.NONE, HiddenWindowPolicy.step(false, RunMode.Value.NORMAL, false));
        assertEquals(HiddenWindowPolicy.Step.NONE, HiddenWindowPolicy.step(false, null, false));
    }

    @Test
    public void normalWithWindowPosStillHidesPlacesAndShowsWithoutActivate() {
        assertTrue(HiddenWindowPolicy.createHidden(RunMode.Value.NORMAL, true));
        assertTrue(HiddenWindowPolicy.showAfterCreate(RunMode.Value.NORMAL, true));
        assertTrue(HiddenWindowPolicy.suppressActivate(RunMode.Value.NORMAL, true));
        assertTrue(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.NORMAL, false, true, false));
        assertFalse(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.NORMAL, false, false, false));
        assertTrue(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.NORMAL, false, false, true));
        assertEquals(HiddenWindowPolicy.Step.NONE, HiddenWindowPolicy.step(false, RunMode.Value.NORMAL, false));
    }

    @Test
    public void hiddenStaysHiddenAndDoesNotGrabTheCursor() {
        assertTrue(HiddenWindowPolicy.createHidden(RunMode.Value.HIDDEN, false));
        assertTrue(HiddenWindowPolicy.createHidden(RunMode.Value.HIDDEN, true));
        assertFalse(HiddenWindowPolicy.showAfterCreate(RunMode.Value.HIDDEN, false));
        assertFalse(HiddenWindowPolicy.showAfterCreate(RunMode.Value.HIDDEN, true));
        assertTrue(HiddenWindowPolicy.suppressActivate(RunMode.Value.HIDDEN, false));
        assertTrue(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.HIDDEN, false, false, false));
        assertEquals(HiddenWindowPolicy.Step.HIDE, HiddenWindowPolicy.step(false, RunMode.Value.HIDDEN, false));
        assertEquals(HiddenWindowPolicy.Step.NONE, HiddenWindowPolicy.step(true, RunMode.Value.HIDDEN, false));
    }

    @Test
    public void presentingShowsWithoutChangingTheHideRuleForANormalClient() {
        assertEquals(HiddenWindowPolicy.Step.SHOW, HiddenWindowPolicy.step(true, RunMode.Value.HIDDEN, true));
        assertEquals(HiddenWindowPolicy.Step.NONE, HiddenWindowPolicy.step(false, RunMode.Value.HIDDEN, true));
        assertTrue(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.HIDDEN, true, false, false));
        assertFalse(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.NORMAL, false, false, false));
        assertEquals(HiddenWindowPolicy.Step.SHOW, HiddenWindowPolicy.step(true, RunMode.Value.NORMAL, false));
    }

    @Test
    public void headlessStaysHiddenAndDoesNotShowForWindowPosOrPresent() {
        assertTrue(HiddenWindowPolicy.createHidden(RunMode.Value.HEADLESS, false));
        assertTrue(HiddenWindowPolicy.createHidden(RunMode.Value.HEADLESS, true));
        assertFalse(HiddenWindowPolicy.showAfterCreate(RunMode.Value.HEADLESS, false));
        assertFalse(HiddenWindowPolicy.showAfterCreate(RunMode.Value.HEADLESS, true));
        assertTrue(HiddenWindowPolicy.suppressActivate(RunMode.Value.HEADLESS, false));
        assertTrue(HiddenWindowPolicy.suppressMouseGrab(RunMode.Value.HEADLESS, false, false, false));
        assertEquals(HiddenWindowPolicy.Step.HIDE, HiddenWindowPolicy.step(false, RunMode.Value.HEADLESS, false));
        assertEquals(HiddenWindowPolicy.Step.NONE, HiddenWindowPolicy.step(true, RunMode.Value.HEADLESS, false));
        assertEquals(HiddenWindowPolicy.Step.NONE, HiddenWindowPolicy.step(true, RunMode.Value.HEADLESS, true));
        assertEquals(HiddenWindowPolicy.Step.HIDE, HiddenWindowPolicy.step(false, RunMode.Value.HEADLESS, true));
    }

    @Test
    public void showPositionUsesThePropertyOrTheDefaultCorner() {
        assertArrayEquals(new int[]{2600, 40}, HiddenWindowPolicy.showPosition(null));
        assertArrayEquals(new int[]{2600, 40}, HiddenWindowPolicy.showPosition(""));
        assertArrayEquals(new int[]{2600, 40}, HiddenWindowPolicy.showPosition("nope"));
        assertArrayEquals(new int[]{2600, 40}, HiddenWindowPolicy.showPosition("2600"));
        assertArrayEquals(new int[]{2600, 40}, HiddenWindowPolicy.showPosition("a,b"));
        assertArrayEquals(new int[]{1, 2}, HiddenWindowPolicy.showPosition("1,2,3"));
        assertArrayEquals(new int[]{10, -3}, HiddenWindowPolicy.showPosition(" 10 , -3 "));
        assertNull(HiddenWindowPolicy.parsePair(null));
        assertEquals(2600, HiddenWindowPolicy.DEFAULT_SHOW_X);
        assertEquals(40, HiddenWindowPolicy.DEFAULT_SHOW_Y);
        assertEquals(320, HiddenWindowPolicy.APPLIED_WIDTH);
        assertEquals(240, HiddenWindowPolicy.APPLIED_HEIGHT);
    }
}
