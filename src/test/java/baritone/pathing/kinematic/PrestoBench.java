package baritone.pathing.kinematic;

import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;

/** Opt-in bookkeeping microbenchmark; excludes Minecraft, PlayerSim and PPE terrain capture. */
public class PrestoBench {
    private static volatile long sink;

    @Test
    public void bookkeeping() {
        Assume.assumeTrue(Boolean.getBoolean("ostinato.bench"));
        Object world = new Object(), player = new Object(), path = new Object();
        Presto p = new Presto();
        int tick = 0;
        int count = 500_000;
        long[] samples = new long[9];
        for (int round = -5; round < samples.length; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < count; i++, tick++) {
                p.auditContext(world, player, true);
                p.begin(world, player, path, tick);
                p.observe(tick, tick * 0.28, 64, 0, 0.28, 0, true);
                p.expect(tick, (tick + 1) * 0.28, 64, 0, 0.28, 0, true);
            }
            long elapsed = System.nanoTime() - start;
            sink = p.observations;
            if (round >= 0) samples[round] = elapsed;
        }
        Arrays.sort(samples);
        System.out.printf("PRESTO-BENCH bookkeeping ns/tick min=%.1f median=%.1f max=%.1f samples=%d iterations=%d observations=%d%n",
                samples[0] / (double) count, samples[4] / (double) count, samples[8] / (double) count,
                samples.length, count, sink);
    }
}
