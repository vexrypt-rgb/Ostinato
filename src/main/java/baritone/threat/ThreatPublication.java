package baritone.threat;

import baritone.api.threat.ThreatSnapshot;

/** Monotonic publication guard. Invalidation makes every outstanding generation ineligible. */
public final class ThreatPublication {
    private volatile ThreatSnapshot latest = ThreatSnapshot.unavailable(0, 0, "NOT_OBSERVED");
    public ThreatSnapshot latest() { return latest; }
    public synchronized long invalidate(long tick, String reason) {
        latest = ThreatSnapshot.unavailable(latest.generation() + 1, tick, reason);
        return latest.generation();
    }
    public synchronized boolean publish(ThreatSnapshot value) {
        if (value.generation() != latest.generation() || value.tick() < latest.tick()) return false;
        latest = value;
        return true;
    }
}
