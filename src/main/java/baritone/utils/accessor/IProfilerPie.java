package baritone.utils.accessor;

// Ostinato: the debug pie's current results. Empty unless the chart is actually on screen.

import net.minecraft.util.profiling.ProfileResults;

public interface IProfilerPie {

    ProfileResults ostinato$results();

    String ostinato$path();
}
