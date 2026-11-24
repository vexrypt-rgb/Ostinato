package baritone.behavior;

import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Ostinato: #stop / #cancel releases a process's forced clicks even when no path segment exists. The builder (tunnel,
 * build) breaks blocks standing still with Input.CLICK_LEFT forced; the stop cleared keys only inside the segment
 * cancel, so the click stayed held and BlockBreakHelper went on breaking the block in view (p2c2 slice 3 rerun 3).
 */
public class StopReleasesClickTest {

    private static IInputOverrideHandler fake(Map<Input, Boolean> keys) {
        return (IInputOverrideHandler) Proxy.newProxyInstance(IInputOverrideHandler.class.getClassLoader(),
                new Class<?>[]{IInputOverrideHandler.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "isInputForcedDown": return keys.getOrDefault((Input) a[0], false);
                        case "setInputForceState": keys.put((Input) a[0], (Boolean) a[1]); return null;
                        case "clearAllKeys": keys.clear(); return null;
                        default: return null;
                    }
                });
    }

    @Test
    public void aStopWithNoSegmentReleasesTheHeldClick() {
        Map<Input, Boolean> keys = new EnumMap<>(Input.class);
        keys.put(Input.CLICK_LEFT, true);
        keys.put(Input.MOVE_FORWARD, true);
        PathingBehavior.releaseOnStop(fake(keys), true);
        assertFalse(keys.getOrDefault(Input.CLICK_LEFT, false));
        assertFalse("every forced input goes when letting go is safe", keys.getOrDefault(Input.MOVE_FORWARD, false));
    }

    @Test
    public void midJumpOnlyTheClicksGo() {
        Map<Input, Boolean> keys = new EnumMap<>(Input.class);
        keys.put(Input.CLICK_LEFT, true);
        keys.put(Input.CLICK_RIGHT, true);
        keys.put(Input.MOVE_FORWARD, true);
        keys.put(Input.JUMP, true);
        PathingBehavior.releaseOnStop(fake(keys), false);
        assertFalse(keys.get(Input.CLICK_LEFT));
        assertFalse(keys.get(Input.CLICK_RIGHT));
        assertTrue("movement keys stay until it is safe", keys.get(Input.MOVE_FORWARD));
        assertTrue(keys.get(Input.JUMP));
    }
}
