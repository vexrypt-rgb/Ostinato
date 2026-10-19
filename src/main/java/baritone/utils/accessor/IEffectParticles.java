/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.utils.accessor;

import net.minecraft.core.particles.ParticleOptions;

import java.util.List;

/** Ostinato: the effect particles a LivingEntity syncs (the only sign of another entity's effects a client gets). */
public interface IEffectParticles {
    List<ParticleOptions> ostinato$effectParticles();
}
