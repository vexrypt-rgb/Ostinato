/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * The forced-input map, without the client's keyboard/Input object. The path executor and its drivers
 * write here every tick; the headless tick reads the same keys straight into PlayerSim and into the
 * ServerboundPlayerInputPacket, which is what the client's Input/KeyboardInput plumbing would do.
 */
package baritone.headless;

import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;

import java.util.EnumMap;
import java.util.Map;

public final class HeadlessInputOverrideHandler implements IInputOverrideHandler {

    private final Map<Input, Boolean> forced = new EnumMap<>(Input.class);
    private float[] analog;

    @Override
    public boolean isInputForcedDown(Input input) {
        return input != null && forced.getOrDefault(input, false);
    }

    @Override
    public void setInputForceState(Input input, boolean state) {
        if (input != null) {
            forced.put(input, state);
        }
    }

    @Override
    public void clearAllKeys() {
        forced.clear();
        analog = null;
    }

    @Override
    public void setAnalog(float left, float forward) {
        analog = new float[]{Math.max(-1f, Math.min(1f, left)), Math.max(-1f, Math.min(1f, forward))};
    }

    @Override
    public float[] pollAnalog() {
        float[] a = analog;
        analog = null;
        return a;
    }
}
