/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoScreens;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Ostinato: the reason a server gave for a kick. */
@Mixin(DisconnectedScreen.class)
public interface MixinOstinatoDisconnected extends IOstinatoScreens {
    @Accessor("details")
    DisconnectionDetails ostinato$details();

    @Override
    default Component ostinato$reason() {
        DisconnectionDetails d = ostinato$details();
        return d == null ? null : d.reason();
    }
}
