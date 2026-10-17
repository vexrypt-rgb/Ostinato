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

import baritone.behavior.FreecamBehavior;
import baritone.gui.hud.AcsHud;
import baritone.gui.hud.FreecamInventoryHud;
import baritone.gui.hud.PathStatusHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the Ostinato path status and autotune cards after the vanilla HUD.
 */
@Mixin(net.minecraft.client.gui.Hud.class)
public class MixinIngameGui {

    @Inject(
            method = "extractRenderState",
            at = @At("RETURN")
    )
    private void onRenderIngameGui(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        PathStatusHud.render(graphics, delta.getGameTimeDeltaPartialTick(false));
        AcsHud.render(graphics);
        FreecamInventoryHud.render(graphics, delta.getGameTimeDeltaPartialTick(false));
    }

    // Hotbar, health, food and air all ask the camera entity for a Player. The freecam body is not one,
    // so those bars would otherwise vanish. Keep drawing the real player's HUD.
    @Inject(method = "getCameraPlayer", at = @At("HEAD"), cancellable = true)
    private void freecamHudPlayer(CallbackInfoReturnable<Player> cir) {
        if (FreecamBehavior.activeCamera() != null && Minecraft.getInstance().player != null) {
            cir.setReturnValue(Minecraft.getInstance().player);
        }
    }
}
