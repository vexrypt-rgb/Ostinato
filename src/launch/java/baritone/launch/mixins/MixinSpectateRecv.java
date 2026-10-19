/*
 * Ostinato: new file (spectator learning). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.spectate.PacketTap;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ostinato: stamps clientbound packets with their network-thread arrival for {@link PacketTap} (how many server ticks
 * passed between two updates of a player). Read-only: the packet goes on exactly as before.
 */
@Mixin(Connection.class)
public abstract class MixinSpectateRecv {
    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void ostinato$recv(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (receiving == PacketFlow.CLIENTBOUND) PacketTap.received(packet);
    }
}
