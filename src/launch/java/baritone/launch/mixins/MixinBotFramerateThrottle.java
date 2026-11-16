/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.api.run.RunMode;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ostinato: 26.3 drops the frame rate to 10 when the window is iconified or the player has given no keyboard or mouse
 * input for 10 minutes (30 after one minute with the "afk" option). Ticks run inside frames, so at 10 fps two ticks run
 * back to back every 100 ms and the movement packets leave in pairs (tick gap p50 about 5 ms, p95 99 ms; Phase 0,
 * 15 Nov 2026). A bot drives the player without input events, so it always looks idle, and a headless client's stub
 * window always reads as iconified. In the bot run modes (hidden, headless) neither throttle applies; the hidden and
 * headless loop caps ({@code MixinHiddenFps}) still bound the frame rate. Normal mode is vanilla.
 */
@Mixin(FramerateLimitTracker.class)
public class MixinBotFramerateThrottle {

    @Inject(method = "getThrottleReason", at = @At("RETURN"), cancellable = true, require = 0)
    private void ostinato$noIdleThrottleForBots(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        RunMode.Value mode = RunMode.current();
        if (mode != RunMode.Value.HIDDEN && mode != RunMode.Value.HEADLESS) {
            return;
        }
        FramerateLimitTracker.FramerateThrottleReason reason = cir.getReturnValue();
        if (reason == FramerateLimitTracker.FramerateThrottleReason.WINDOW_ICONIFIED
                || reason == FramerateLimitTracker.FramerateThrottleReason.SHORT_AFK
                || reason == FramerateLimitTracker.FramerateThrottleReason.LONG_AFK) {
            cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
        }
    }
}
