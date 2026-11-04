package baritone.api.threat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A tick-stamped immutable publication. Retained snapshots never become a promise about the current world. */
public record ThreatSnapshot(long generation, long tick, UUID subject, String dimension,
        List<ThreatAssessment> sources, Set<String> environment, boolean truncated, String status) {
    public ThreatSnapshot { sources = List.copyOf(sources); environment = Set.copyOf(environment); }
    public int highestLevel() { return sources.stream().mapToInt(ThreatAssessment::level).max().orElse(0); }
    /** Simultaneous exposure count, NOT an additive threat score or combined damage forecast. */
    public long immediateSources() {
        return sources.stream().filter(s -> s.urgency() == ThreatAssessment.Urgency.IMMEDIATE
                && (s.exposure() == ThreatAssessment.Exposure.IN_REACH
                    || s.exposure() == ThreatAssessment.Exposure.PREDICTED_IMPACT)).count();
    }
    public List<ThreatAssessment> mostImportant() { return sources.subList(0, Math.min(5, sources.size())); }
    public static ThreatSnapshot unavailable(long generation, long tick, String status) {
        return new ThreatSnapshot(generation, tick, null, "", List.of(), Set.of(), false, status);
    }
}
