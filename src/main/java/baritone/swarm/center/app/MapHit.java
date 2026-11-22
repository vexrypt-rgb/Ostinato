package baritone.swarm.center.app;

import java.util.List;

/**
 * Ostinato: Phase 3 window, marker hit testing on the war-room map. A marker is a point with a radius; the nearest
 * marker inside the radius wins. An empty map hits nothing.
 */
public final class MapHit {

    /** Pixel radius of a marker for clicks. */
    public static final double RADIUS = 10;

    private MapHit() {}

    public record Marker(String name, double sx, double sy) {}

    /** The nearest marker within {@code radius}, or null. */
    public static String nearest(List<Marker> markers, double sx, double sy, double radius) {
        if (markers == null || markers.isEmpty() || radius < 0) {
            return null;
        }
        String best = null;
        double bestD = radius * radius;
        for (Marker m : markers) {
            if (m == null || m.name() == null) {
                continue;
            }
            double dx = m.sx() - sx;
            double dy = m.sy() - sy;
            double d = dx * dx + dy * dy;
            if (d <= radius * radius && (best == null || d < bestD)) {
                bestD = d;
                best = m.name();
            }
        }
        return best;
    }
}
