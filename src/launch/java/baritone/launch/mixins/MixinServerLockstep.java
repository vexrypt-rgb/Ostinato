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
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ostinato: the singleplayer server's half of {@link Lockstep}; inert unless -Dostinato.lockstep=true. */
@Mixin(MinecraftServer.class)
public abstract class MixinServerLockstep {

    @Inject(method = "processPacketsAndTick", at = @At("HEAD"))
    private void ostinato$gate(boolean sprinting, CallbackInfo ci) {
        if (!Lockstep.ENABLED || !((Object) this instanceof IntegratedServer)) return;
        Lockstep.serverGate((MinecraftServer) (Object) this, ((AccessorEventLoop) this)::ostinato$pollTask);
    }

    @Inject(method = "processPacketsAndTick", at = @At("RETURN"))
    private void ostinato$done(boolean sprinting, CallbackInfo ci) {
        if (!Lockstep.ENABLED || !((Object) this instanceof IntegratedServer)) return;
        Lockstep.serverDone();
    }

    /**
     * While the server waits at the gate it is past its tick deadline, so vanilla's shouldRun holds back any task
     * submitted this tick for three more ticks: a client blocked on server.submit(..).join() (the bench's commands)
     * then deadlocked with a server waiting for that client's go, until the gate timed out and the server ticked on
     * its own (the parity drill lost a minute and a tick of phase at round start). At the gate, run them.
     */
    @Inject(method = "shouldRun(Lnet/minecraft/server/TickTask;)Z", at = @At("HEAD"), cancellable = true)
    private void ostinato$runAtGate(net.minecraft.server.TickTask task, CallbackInfoReturnable<Boolean> cir) {
        if (Lockstep.atGate()) cir.setReturnValue(true);
    }
}
