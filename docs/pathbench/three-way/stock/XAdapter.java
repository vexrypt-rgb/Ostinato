/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Per-fork adapter: stock Baritone.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class XAdapter {
    private static final Map<XWorld, XLevel> LEVELS = new IdentityHashMap<>();
    private static boolean booted;

    public static String fork() {
        return "baritone";
    }

    public static synchronized void boot() {
        if (booted) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // bind default item components the way a server does after loading registries (bootstrap leaves them unbound)
        if (!Items.WATER_BUCKET.builtInRegistryHolder().areComponentsBound()) {
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        }
        Baritone.settings().considerPotionEffects.value = false;
        Baritone.settings().chatDebug.value = false;
        booted = true;
    }

    public static CalculationContext context(XWorld w) {
        XLevel l = LEVELS.computeIfAbsent(w, XLevel::make);
        return new CalculationContext(l, w, new XToolSet(), Baritone.settings().allowPlace.value);
    }

    public static Map<String, XBench.Finder> finders() {
        Map<String, XBench.Finder> m = new LinkedHashMap<>();
        m.put("baritone", (s, g, c) -> new AStarPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c));
        return m;
    }
}
