package baritone.training;

// Ostinato: F3 biome and temperature, the pie chart if it is open, and sounds the client just played.

import baritone.utils.accessor.IProfilerPie;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ResultField;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/** Cues the client already shows. Distant biomes and a closed pie chart are not read. */
public final class ClientLegit {

    public final String biome;
    public final double temperature;
    public final String pieTop;
    public final List<String> sounds;

    private ClientLegit(String biome, double temperature, String pieTop, List<String> sounds) {
        this.biome = biome;
        this.temperature = temperature;
        this.pieTop = pieTop;
        this.sounds = sounds;
    }

    public static ClientLegit capture(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            return new ClientLegit("", 0, "", List.of());
        }
        Holder<Biome> holder = mc.level.getBiome(mc.player.blockPosition());
        String name = holder.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
        double temperature = holder.value().getBaseTemperature();
        return new ClientLegit(name, temperature, pie(mc), LegitSoundLog.recent());
    }

    static String pie(Minecraft mc) {
        if (mc.getDebugOverlay() == null || !mc.getDebugOverlay().showProfilerChart()) {
            return LegitCues.pieTop(false, "");
        }
        Object chart = mc.getDebugOverlay().getProfilerPieChart();
        if (!(chart instanceof IProfilerPie pie)) {
            return LegitCues.pieTop(true, "");
        }
        ProfileResults results = pie.ostinato$results();
        if (results == null) {
            return LegitCues.pieTop(true, "");
        }
        String path = pie.ostinato$path();
        if (path == null || path.isEmpty()) {
            path = "root";
        }
        try {
            List<ResultField> times = results.getTimes(path);
            if (times == null || times.isEmpty()) {
                return LegitCues.pieTop(true, "");
            }
            return LegitCues.pieTop(true, times.get(0).name);
        } catch (RuntimeException ex) {
            return LegitCues.pieTop(true, "");
        }
    }
}
