/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.PhysicsTelemetry;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Ostinato: the engine's counters and latency histograms. Thread safe, fixed size, no allocation when recording. */
public final class PhysicsStats {

    /** Microsecond latencies in fixed log-scale buckets (each bound about 1.25x the last); exact maximum. */
    public static final class Histogram {
        private static final long[] BOUNDS;

        static {
            long[] b = new long[96];
            b[0] = 1;
            for (int i = 1; i < b.length; i++) b[i] = Math.max(b[i - 1] + 1, Math.round(b[i - 1] * 1.25));
            BOUNDS = b;
        }

        private final AtomicLongArray counts = new AtomicLongArray(BOUNDS.length + 1);
        private final AtomicLong max = new AtomicLong();

        public void recordNanos(long nanos) {
            long us = Math.max(0, nanos / 1000);
            int i = java.util.Arrays.binarySearch(BOUNDS, us);
            counts.incrementAndGet(i >= 0 ? i : -i - 1);
            max.accumulateAndGet(us, Math::max);
        }

        public long count() {
            long n = 0;
            for (int i = 0; i < counts.length(); i++) n += counts.get(i);
            return n;
        }

        /** The upper bound of the bucket holding the {@code p} quantile, 0..1; 0 when empty. */
        public long percentile(double p) {
            long n = count();
            if (n == 0) return 0;
            long target = Math.max(1, (long) Math.ceil(p * n)), seen = 0;
            for (int i = 0; i < counts.length(); i++) {
                seen += counts.get(i);
                if (seen >= target) return i < BOUNDS.length ? Math.min(BOUNDS[i], max.get()) : max.get();
            }
            return max.get();
        }

        public long max() {
            return max.get();
        }
    }

    public final LongAdder requests = new LongAdder(), inline = new LongAdder(), async = new LongAdder(), completed = new LongAdder(),
            cancelled = new LongAdder(), expired = new LongAdder(), rejected = new LongAdder(), degraded = new LongAdder(),
            cacheHits = new LongAdder(), dedupHits = new LongAdder(), sectionCaptures = new LongAdder(),
            sectionReuses = new LongAdder(), sectionInvalidations = new LongAdder(), workerBusyNanos = new LongAdder(),
            mainThreadNanos = new LongAdder(), captureCuts = new LongAdder();
    public final Histogram compute = new Histogram(), queue = new Histogram(), tick = new Histogram();

    public PhysicsTelemetry snapshot(int queueDepth, int queueHighWater, long workerUptimeNanos) {
        return new PhysicsTelemetry(requests.sum(), inline.sum(), async.sum(), completed.sum(), cancelled.sum(), expired.sum(),
                rejected.sum(), degraded.sum(), cacheHits.sum(), dedupHits.sum(), sectionCaptures.sum(), sectionReuses.sum(),
                sectionInvalidations.sum(), queueDepth, queueHighWater, workerBusyNanos.sum(), workerUptimeNanos, mainThreadNanos.sum(),
                compute.percentile(0.5), compute.percentile(0.95), compute.percentile(0.99), compute.max(),
                queue.percentile(0.5), queue.percentile(0.95), queue.percentile(0.99), queue.max(),
                tick.percentile(0.5), tick.percentile(0.95), tick.percentile(0.99), tick.max(), tick.count());
    }
}
