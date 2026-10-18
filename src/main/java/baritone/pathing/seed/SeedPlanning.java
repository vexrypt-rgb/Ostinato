/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.seed;

import baritone.Baritone;
import baritone.api.utils.Helper;
import baritone.pathing.SeedPlan;
import baritone.pathing.calc.hierarchy.SectionGraph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.lang.ref.WeakReference;
import java.util.OptionalLong;

/**
 * Ostinato: keeps {@link SeedPlan} in step with the {@code seedPlanning} toggle (off by default), the seed setting and
 * the world. Called every tick; it only acts when one of them changed. The predictor is built on a daemon thread
 * (the vanilla registries take a moment) and installed only if nothing changed meanwhile. Singleplayer with a blank
 * {@code seedPlanningSeed} uses the integrated server's own generator, which predicts exactly what that world
 * generates (before surface and features); otherwise vanilla generation from the given seed.
 */
public final class SeedPlanning {

    private static boolean on;
    private static String seedText = "";
    private static WeakReference<Level> world = new WeakReference<>(null);
    /** Bumped on every change, so a build that finishes after a newer change is dropped. */
    private static volatile int generation;
    /** The installed predictor, for status and the benchmark; null when none. */
    public static volatile SeedTerrain terrain;

    private SeedPlanning() {}

    /** {@code level} null means no world (the menu): clear. */
    public static synchronized void sync(Minecraft mc, Level level) {
        boolean wantOn = level != null && Baritone.settings().seedPlanning.value;
        String text = Baritone.settings().seedPlanningSeed.value.trim();
        if (wantOn == on && (!on || (text.equals(seedText) && world.get() == level))) return;
        on = wantOn;
        seedText = text;
        world = new WeakReference<>(level);
        int gen = ++generation;
        SeedPlan.clear();
        terrain = null;
        SectionGraph.INSTANCE.invalidateAll(); // section edges priced from predicted terrain are stale now
        if (!wantOn) return;
        ResourceKey<Level> dim = level.dimension();
        int minY = level.getMinY(), height = level.getHeight();
        int cap = Math.max(256, Baritone.settings().seedPlanningCacheColumns.value);
        IntegratedServer server = mc.getSingleplayerServer();
        Thread t = new Thread(() -> {
            try {
                long seed;
                SeedTerrain built;
                OptionalLong parsed = WorldOptions.parseSeed(text);
                if (parsed.isPresent()) {
                    seed = parsed.getAsLong();
                    built = SeedTerrain.vanilla(seed, dim, minY, height, cap);
                } else if (server != null && server.getLevel(dim) != null) {
                    ServerLevel sl = server.getLevel(dim);
                    seed = sl.getSeed();
                    built = new SeedTerrain(sl.getChunkSource().getGenerator(), sl.getChunkSource().randomState(), minY, height, cap);
                } else {
                    Helper.HELPER.logDirect("seedPlanning is on but seedPlanningSeed is blank; on a server, set it to the world seed.");
                    return;
                }
                synchronized (SeedPlanning.class) {
                    if (gen != generation) return;
                    terrain = built;
                    SeedPlan.use(seed, built);
                }
                SectionGraph.INSTANCE.invalidateAll(); // re-price unknown sections from the prediction
                Helper.HELPER.logDebug("Seed planning: predicting unloaded terrain in " + dim + " from seed " + seed);
            } catch (Throwable e) {
                Helper.HELPER.logDirect("seedPlanning could not set up world generation: " + e);
            }
        }, "Ostinato seed planning");
        t.setDaemon(true);
        t.start();
    }
}
