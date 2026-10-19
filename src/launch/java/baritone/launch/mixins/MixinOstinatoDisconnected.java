/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoScreens;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Ostinato: the kick reason on the disconnect screen. */
@Mixin(DisconnectedScreen.class)
public abstract class MixinOstinatoDisconnected implements IOstinatoScreens {
    @Shadow
    @Final
    private DisconnectionDetails details;

    @Override
    public Component ostinato$reason() {
        return details == null ? null : details.reason();
    }
}
