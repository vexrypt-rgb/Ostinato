package baritone.launch.mixins;

// Ostinato: note footsteps, breaks, mobs, water and lava while the legit track is on. Off, this returns immediately.

import baritone.training.LegitSoundLog;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class MixinLegitSounds {

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"))
    private void ostinato$legitSound(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (!LegitSoundLog.enabled()) {
            return;
        }
        LegitSoundLog.hear(sound);
    }
}
