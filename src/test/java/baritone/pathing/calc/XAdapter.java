/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Per-fork adapter: Ostinato.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

public final class XAdapter {
    private static boolean booted;

    public static String fork() {
        return "ostinato";
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
        Baritone.settings().desktopNotifications.value = false;
        booted = true;
    }

    public static CalculationContext context(XWorld w) {
        return new CalculationContext(w, new XToolSet(), Baritone.settings().allowPlace.value, XVoxels.MIN_Y, XVoxels.HEIGHT);
    }

    public static Map<String, XBench.Finder> finders() {
        Map<String, XBench.Finder> m = new LinkedHashMap<>();
        // the bare search, as the other two forks run it
        m.put("ostinato-astar", (s, g, c) -> new AStarPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c));
        // what Ostinato ships: the same search with the far-segment weight PathingBehavior hands it by default
        m.put("ostinato-shipped", (s, g, c) -> new AStarPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c, PathingBehavior.farSegmentWeight(g, s)));
        return m;
    }
}
