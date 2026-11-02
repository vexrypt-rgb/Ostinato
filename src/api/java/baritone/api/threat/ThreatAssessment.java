package baritone.api.threat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Detached observation, not an instruction or a probability. Null numbers mean unavailable. */
public record ThreatAssessment(UUID source, String entityType, UUID subject, int level,
        Severity severity, Urgency urgency, Double contactTicks, double distance,
        Exposure exposure, Sight sight, Double damage, long observationAgeTicks,
        Confidence confidence, List<String> reasons, Set<String> uncertainties, String minecraftVersion) {
    public enum Severity { UNKNOWN, MINOR, SERIOUS, LETHAL }
    public enum Urgency { UNKNOWN, POTENTIAL, SOON, IMMEDIATE }
    public enum Exposure { UNKNOWN, COVERED, POSSIBLE, IN_REACH, PREDICTED_IMPACT }
    public enum Sight { UNKNOWN, BLOCKED, CLEAR }
    public enum Confidence { UNKNOWN, LIMITED, OBSERVED }
    public ThreatAssessment {
        if (level < 0 || level > 5 || observationAgeTicks < 0) throw new IllegalArgumentException();
        reasons = List.copyOf(reasons);
        uncertainties = Set.copyOf(uncertainties);
    }
}
