package baritone.threat;

import baritone.api.threat.ThreatAssessment;
import org.junit.Test;
import org.junit.Assume;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** Warmed pure scoring cost only; excludes game capture, sight checks and PPE. */
public class ThreatBenchmark {
    private static volatile Object sink;
    @Test public void measure32Sources() {
        Assume.assumeTrue(Boolean.getBoolean("ostinato.bench"));
        var o = ThreatRulesTest.obs(ThreatRules.Kind.MELEE, ThreatAssessment.Sight.CLEAR, true, true, false, 20., 4., 0);
        for (int i=0;i<20000;i++) sink = ThreatRules.assess(o, null, 0);
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long[] samples = new long[5000];
        long allocation = bean.getThreadAllocatedBytes(id);
        for (int i=0;i<samples.length;i++) {
            long start=System.nanoTime();
            for (int n=0;n<32;n++) sink = ThreatRules.assess(o,null,0);
            samples[i]=System.nanoTime()-start;
        }
        long bytes = bean.getThreadAllocatedBytes(id)-allocation;
        Arrays.sort(samples);
        System.out.printf("THREAT_SCORE_32 samples=%d p50_us=%.3f p95_us=%.3f p99_us=%.3f max_us=%.3f bytes_per_batch=%.1f%n",
                samples.length, samples[2500]/1000., samples[4750]/1000., samples[4950]/1000., samples[4999]/1000., (double)bytes/samples.length);
    }
}
