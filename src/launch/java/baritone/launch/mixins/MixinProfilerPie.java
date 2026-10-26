package baritone.launch.mixins;

// Ostinato: read the pie chart the client is drawing. No behavior change.

import baritone.utils.accessor.IProfilerPie;
import net.minecraft.client.gui.components.debugchart.ProfilerPieChart;
import net.minecraft.util.profiling.ProfileResults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ProfilerPieChart.class)
public interface MixinProfilerPie extends IProfilerPie {

    @Accessor("profilerPieChartResults")
    @Override
    ProfileResults ostinato$results();

    @Accessor("profilerTreePath")
    @Override
    String ostinato$path();
}
