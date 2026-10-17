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
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The first-person arm is built from the player's view angles. In freecam those angles stay on the bot,
 * so the arm is posed for a look direction the camera is not using and it leaves the screen.
 */
@Mixin(FirstPersonHandsAndItems.class)
public class MixinFirstPersonHands {

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getViewXRot(F)F")
    )
    private float freecamPitch(LocalPlayer player, float partial) {
        Entity camera = FreecamBehavior.activeCamera();
        return camera != null ? camera.getViewXRot(partial) : player.getViewXRot(partial);
    }

    @Redirect(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getViewYRot(F)F")
    )
    private float freecamYaw(LocalPlayer player, float partial) {
        Entity camera = FreecamBehavior.activeCamera();
        return camera != null ? camera.getViewYRot(partial) : player.getViewYRot(partial);
    }
}
