/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.api.physics;

/**
 * Ostinato: a point-in-time copy of the engine's counters. Latencies are microseconds from fixed log-scale buckets
 * (each percentile is the bucket's upper bound, so within a factor of 1.25 above the true value).
 */
public record PhysicsTelemetry(
        long requests, long inline, long async, long completed, long cancelled, long expired, long rejected, long degraded,
        long cacheHits, long dedupHits, long sectionCaptures, long sectionReuses, long sectionInvalidations,
        int queueDepth, int queueHighWater, long workerBusyNanos, long workerUptimeNanos, long mainThreadNanos,
        long computeP50, long computeP95, long computeP99, long computeMax,
        long queueP50, long queueP95, long queueP99, long queueMax,
        long tickP50, long tickP95, long tickP99, long tickMax, long ticks) {

    /** Fraction of the worker's life spent computing, 0..1. */
    public double workerUtilisation() {
        return workerUptimeNanos == 0 ? 0 : (double) workerBusyNanos / workerUptimeNanos;
    }

    public String summary() {
        return String.format("req %d (inline %d, async %d) done %d cancel %d expired %d rejected %d degraded %d | cache %d dedup %d"
                        + " | sections captured %d reused %d invalidated %d | queue %d (max %d) worker %.1f%% main %.2fms"
                        + " | compute us p50 %d p95 %d p99 %d max %d | wait us p50 %d p95 %d p99 %d max %d"
                        + " | tick us p50 %d p95 %d p99 %d max %d over %d",
                requests, inline, async, completed, cancelled, expired, rejected, degraded, cacheHits, dedupHits,
                sectionCaptures, sectionReuses, sectionInvalidations, queueDepth, queueHighWater, workerUtilisation() * 100,
                mainThreadNanos / 1e6, computeP50, computeP95, computeP99, computeMax, queueP50, queueP95, queueP99, queueMax,
                tickP50, tickP95, tickP99, tickMax, ticks);
    }
}
