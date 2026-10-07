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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands every chat line, as shown (e.g. {@code Bob whispers to you: S2S....}), to the swarm link. Since 1.19 whispers
 * arrive as signed player chat whose packet only holds the body, so the decorated line is taken here, where system,
 * player and disguised chat all end up. Senders are named by the sealed envelope, never by this text.
 */
@Mixin(ChatComponent.class)
public class MixinChatComponent {

    @Inject(
            method = "addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD")
    )
    private void onAddPlayerMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        forward(message);
    }

    @Inject(method = "addServerSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void onAddServerSystemMessage(Component message, CallbackInfo ci) {
        forward(message);
    }

    private static void forward(Component message) {
        if (!Baritone.settings().swarmEnabled.value || message == null) {
            return;
        }
        String text;
        try {
            text = message.getString();
        } catch (Throwable t) {
            return;
        }
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primary instanceof Baritone) {
            ((Baritone) primary).getSwarmBehavior().onIncomingChat(text);
        }
    }
}
