/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * What the predictive layer says about a spear jab. The fight asks and keeps its own way when told nothing:
 * {@link #NONE} is the fight exactly as it was.
 */
public interface JabAdvice {
    JabAdvice NONE = new JabAdvice() {};

    /** Where to look for the jab; null leaves the fight's own point. */
    default Vec3 aim(Player me, LivingEntity target) {
        return null;
    }

    /**
     * -1: hold the jab, the server's ray cannot reach the opponent; 1: send it, the server's ray meets the predicted
     * opponent inside the band; 0: no opinion, the client's own ray decides.
     */
    default int verdict(Player me, LivingEntity target, double bandLo, double bandHi) {
        return 0;
    }

    /** A jab went out; {@code clientRay} is what the client's own ray said of it. */
    default void sent(Player me, LivingEntity target, boolean clientRay) {}
}
