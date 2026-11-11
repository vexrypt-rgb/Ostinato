/*
 * Ostinato: runtime side of true headless. Does not skip the client tick.
 * A process created as headless has no GPU device and no SDL window. This class only touches the sound engine.
 */
package baritone.launch.headless;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;

/**
 * Shuts the OpenAL playback device down when the mode is headless, and opens it again once if the mode leaves
 * headless. {@code SoundEngine.reload} is also cancelled while headless, so a device-change tick cannot reopen it.
 * <p>
 * {@code destroy} returns immediately when the engine was never loaded, so calling it every headless tick is safe.
 * The {@code SoundEngine} object stays. Ticks that assume the manager exists still have it.
 */
public final class HeadlessClient {

    /** True after a headless tick has held the device shut, so leaving headless reloads once. */
    private static boolean soundHeld;

    private HeadlessClient() {}

    /** Once per client tick. Does not skip the tick. */
    public static void onTick() {
        boolean headless = HeadlessPolicy.active(RunMode.current());
        if (!headless && !soundHeld) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        try {
            SoundManager sounds = mc.getSoundManager();
            if (sounds == null) {
                return;
            }
            if (headless) {
                // Ostinato: Library.cleanup when a device is open. No-op when reload was cancelled at startup.
                sounds.destroy();
                soundHeld = true;
            } else {
                sounds.reload();
                soundHeld = false;
            }
        } catch (RuntimeException e) {
            System.err.println("Ostinato: headless mode could not update the sound engine: " + e.getMessage());
        }
    }
}
