package baritone.gracenote;

import baritone.api.gracenote.IGraceNote.Mode;
import baritone.gracenote.GraceNoteCore.Body;
import baritone.gracenote.GraceNoteCore.Late;
import org.junit.Assume;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Warmed decision cost only (the core, no game reads, no PPE prediction): the idle tick every tick pays, and a whole
 * clutch episode. The game-side cost, PPE included, is measured live by GraceNoteLiveTest.
 */
public class GraceNoteBenchmark {
    private static volatile Object sink;

    @Test
    public void measureIdleTickAndEpisode() {
        Assume.assumeTrue(Boolean.getBoolean("ostinato.bench"));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();

        GraceNoteCore idle = new GraceNoteCore();
        Body ground = GraceNoteCoreTest.ground();
        Late none = new Late(false, false, false, false, false, false);
        for (int i = 0; i < 50_000; i++) sink = idle.early(i, Mode.LIVE, true, false, ground, null, null, false, 0, null);
        long[] samples = new long[20_000];
        long alloc = bean.getThreadAllocatedBytes(id);
        for (int i = 0; i < samples.length; i++) {
            long t0 = System.nanoTime();
            sink = idle.early(100_000 + i, Mode.LIVE, true, false, ground, null, null, false, 0, null);
            sink = idle.late(100_000 + i, none);
            samples[i] = System.nanoTime() - t0;
        }
        long bytes = bean.getThreadAllocatedBytes(id) - alloc;
        Arrays.sort(samples);
        int n = samples.length;
        System.out.printf("GRACENOTE_IDLE_TICK samples=%d p50_ns=%d p95_ns=%d p99_ns=%d max_ns=%d bytes_per_tick=%.1f%n",
                n, samples[n / 2], samples[n * 95 / 100], samples[n * 99 / 100], samples[n - 1], (double) bytes / n);

        for (int i = 0; i < 2_000; i++) sink = new GraceNoteCoreTest.Sim().run(100);
        long[] episodes = new long[5_000];
        int[] ticks = new int[episodes.length];
        for (int i = 0; i < episodes.length; i++) {
            GraceNoteCoreTest.Sim s = new GraceNoteCoreTest.Sim();
            long t0 = System.nanoTime();
            sink = s.run(100);
            episodes[i] = System.nanoTime() - t0;
            ticks[i] = (int) (s.tick - 1000);
        }
        Arrays.sort(episodes);
        n = episodes.length;
        System.out.printf("GRACENOTE_EPISODE samples=%d ticks=%d p50_us=%.3f p95_us=%.3f p99_us=%.3f max_us=%.3f (scripted world included)%n",
                n, ticks[0], episodes[n / 2] / 1000., episodes[n * 95 / 100] / 1000., episodes[n * 99 / 100] / 1000., episodes[n - 1] / 1000.);
    }
}
