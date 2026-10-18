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

import baritone.utils.Lockstep;
import io.netty.channel.ChannelFutureListener;
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

/** Ostinato: packet counts for {@link Lockstep}'s barriers; inert unless -Dostinato.lockstep=true. */
@Mixin(Connection.class)
public abstract class MixinConnectionLockstep {

    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    private io.netty.channel.Channel channel;

    /** Only the in-memory pair between this client and its own server: a fake player's connection is not one of them. */
    private boolean ostinato$local() {
        return channel instanceof io.netty.channel.local.LocalChannel;
    }

    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void ostinato$sent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (!Lockstep.ENABLED || !ostinato$local()) return;
        if (receiving == PacketFlow.CLIENTBOUND) Lockstep.c2sSent.incrementAndGet();
        else Lockstep.s2cSent.incrementAndGet();
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("RETURN"))
    private void ostinato$read(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (!Lockstep.ENABLED || !ostinato$local()) return;
        if (receiving == PacketFlow.SERVERBOUND) Lockstep.c2sRead.incrementAndGet();
        else Lockstep.s2cRead.incrementAndGet();
    }
}
