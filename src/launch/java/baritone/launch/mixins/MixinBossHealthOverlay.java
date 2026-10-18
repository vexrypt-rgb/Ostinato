/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IBossOverlay;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.UUID;

/** Ostinato: reads the boss bars; changes nothing. */
@Mixin(BossHealthOverlay.class)
public abstract class MixinBossHealthOverlay implements IBossOverlay {
    @Shadow
    @Final
    private Map<UUID, LerpingBossEvent> events;

    @Override
    public Map<UUID, LerpingBossEvent> ostinato$events() {
        return events;
    }
}
