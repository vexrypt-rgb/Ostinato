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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A chest screen covers the whole view. While freecam is on, keep the menu open for the bot's clicks
 * but draw the slots over the bot's head instead. The player's own inventory screen stays, so its doll renders.
 */
@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreen {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void freecamHidesContainer(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (FreecamBehavior.activeCamera() == null) {
            return;
        }
        if ((Object) this instanceof InventoryScreen || (Object) this instanceof CreativeModeInventoryScreen) {
            return;
        }
        ci.cancel();
    }
}
