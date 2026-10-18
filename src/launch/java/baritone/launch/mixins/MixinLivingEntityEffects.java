/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IEffectParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Ostinato: reads the synced effect particles; changes nothing. */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityEffects implements IEffectParticles {
    @Shadow
    @Final
    private static EntityDataAccessor<List<ParticleOptions>> DATA_EFFECT_PARTICLES;

    @Override
    public List<ParticleOptions> ostinato$effectParticles() {
        return ((LivingEntity) (Object) this).getEntityData().get(DATA_EFFECT_PARTICLES);
    }
}
