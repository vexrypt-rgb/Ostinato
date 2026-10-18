/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.utils.accessor;

import net.minecraft.client.gui.components.LerpingBossEvent;

import java.util.Map;
import java.util.UUID;

/** Ostinato: the boss bars the client shows. */
public interface IBossOverlay {
    Map<UUID, LerpingBossEvent> ostinato$events();
}
