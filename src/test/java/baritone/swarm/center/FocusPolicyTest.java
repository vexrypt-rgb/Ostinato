package baritone.swarm.center;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: no-focus flags and second-monitor placement. No frame is created. */
public class FocusPolicyTest {

    @Test public void showDoesNotActivateAndAlertsDoNotStealFocus() {
        assertFalse(FocusPolicy.AUTO_REQUEST_FOCUS);
        assertFalse(FocusPolicy.ACTIVATE_ON_SHOW);
        assertFalse(FocusPolicy.ALERTS_STEAL_FOCUS);
        assertTrue(FocusPolicy.FOCUSABLE_AFTER_SHOW);
    }

    @Test public void defaultPositionIsBesideTheGameWindow() {
        assertArrayEquals(new int[]{2600, 40}, FocusPolicy.position(null));
        assertArrayEquals(new int[]{2600, 40}, FocusPolicy.position("nope"));
        assertArrayEquals(new int[]{10, 20}, FocusPolicy.position("10,20"));
    }

    @Test public void aPointOnAScreenStaysAndAnOffscreenPointUsesTheSecondScreen() {
        WindowPlacement.Screen primary = new WindowPlacement.Screen(0, 0, 1920, 1080);
        WindowPlacement.Screen second = new WindowPlacement.Screen(1920, 0, 1920, 1080);
        assertArrayEquals(new int[]{2600, 40}, WindowPlacement.place(List.of(primary, second), null));
        WindowPlacement.Screen far = new WindowPlacement.Screen(4000, 0, 800, 600);
        assertArrayEquals(new int[]{4040, 40}, WindowPlacement.place(List.of(primary, far), "2600,40"));
        assertArrayEquals(new int[]{2600, 40}, WindowPlacement.place(List.of(primary), "2600,40"));
    }
}
