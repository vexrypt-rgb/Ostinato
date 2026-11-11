/*
 * Ostinato: seen-only gate for mine and get-to-block targets. Not part of upstream Baritone.
 */
package baritone.legit;

import baritone.Baritone;
import baritone.api.legit.LegitMode;
import baritone.api.legit.SeenOnly;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks mine targets. When the filter is off, the candidate list is returned as it was given.
 * When the filter is on, a block is kept only if it is knowable from the eye now, or it was admitted earlier in
 * this task after passing that check. A buried block never enters the admitted set.
 */
public final class LegitTargets {

    private LegitTargets() {}

    public static boolean active() {
        return LegitMode.isActive(Baritone.settings().legitMode.value, LegitSessions.current());
    }

    public static List<BlockPos> select(List<BlockPos> candidates, LongSet seen, SeenOnly.View view,
                                         double eyeX, double eyeY, double eyeZ, boolean filterOn) {
        if (!filterOn) {
            return candidates;
        }
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : candidates) {
            if (pos == null) {
                continue;
            }
            long key = SeenOnly.key(pos.getX(), pos.getY(), pos.getZ());
            boolean knowable = view != null && SeenOnly.knowable(view, eyeX, eyeY, eyeZ, pos.getX(), pos.getY(), pos.getZ());
            if (knowable && seen != null) {
                seen.add(key);
            }
            if (knowable || (seen != null && seen.contains(key))) {
                out.add(pos);
            }
        }
        return out;
    }
}
