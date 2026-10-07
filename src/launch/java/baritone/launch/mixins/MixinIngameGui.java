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

import baritone.gui.hud.PathStatusHud;
import net.minecraft.client.DeltaTracker;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Ostinato path status card after the vanilla HUD.
 */
@Mixin(net.minecraft.client.gui.Hud.class)
public class MixinIngameGui {

    @Inject(
            method = "extractRenderState",
            at = @At("RETURN")
    )
    private void onRenderIngameGui(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        PathStatusHud.render(graphics, delta.getGameTimeDeltaPartialTick(false));
    }
}
