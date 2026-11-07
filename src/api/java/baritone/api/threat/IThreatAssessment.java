package baritone.api.threat;

/** Read-only, observation-only service. Available independently of TenorClef. */
public interface IThreatAssessment {
    ThreatSnapshot latest();
    Telemetry telemetry();
    record Telemetry(long evaluations, long scanned, long candidates, long predictions,
                     long discardedPredictions, long truncatedTicks, long totalNanos, long maxNanos) {}
}
