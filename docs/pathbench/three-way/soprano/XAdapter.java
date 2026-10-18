/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Per-fork adapter: Soprano.
 */
package baritone.pathing.calc;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.Baritone;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import baritone.utils.BlockStateInterface;

import java.util.LinkedHashMap;
import java.util.Map;

public final class XAdapter {
    private static boolean booted;

    public static String fork() {
        return "soprano";
    }

    public static synchronized void boot() {
        if (booted) return;
        ItemFixture.boot();
        Baritone.settings().considerPotionEffects.value = false;
        Baritone.settings().chatDebug.value = false;
        booted = true;
    }

    /** Soprano's headless context, as its own BoatRunCostTest builds it: no bucket, no boat, full health, no enchantments. */
    private static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi) {
            super(null, true, null, null, bsi, new XToolSet(), Baritone.settings().allowPlace.value, false,
                    Baritone.settings().allowSprint.value, 0, 1.0f, 20.0, false);
        }
    }

    public static CalculationContext context(XWorld w) {
        return new Ctx(w);
    }

    public static Map<String, XBench.Finder> finders() {
        Map<String, XBench.Finder> m = new LinkedHashMap<>();
        m.put("soprano", (s, g, c) -> new AStarPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c));
        return m;
    }
}
