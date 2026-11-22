package baritone.swarm.center.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Ostinato: Phase 3 window, which bots the war-room map has selected. A click replaces the set, a shift-click toggles
 * one bot, and a drag box takes every marker inside it. The set keeps insertion order.
 */
public final class MapSelection {

    private final LinkedHashSet<String> names = new LinkedHashSet<>();

    public List<String> names() {
        return List.copyOf(names);
    }

    public boolean contains(String name) {
        return name != null && names.contains(name);
    }

    /** Replace the selection with this one bot. A null or blank name clears it. */
    public void click(String name) {
        names.clear();
        if (name != null && !name.isBlank()) {
            names.add(name);
        }
    }

    /** Add this bot, or remove it when it is already selected. A null name does nothing. */
    public void shiftClick(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (!names.remove(name)) {
            names.add(name);
        }
    }

    public void clear() {
        names.clear();
    }

    /** Replace the selection with these names, dropping blanks. */
    public void replace(List<String> next) {
        names.clear();
        if (next == null) {
            return;
        }
        for (String name : next) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
    }

    /**
     * Markers whose screen point lies inside the rectangle, edges included. {@code add} keeps the current set and
     * adds; otherwise the set becomes just those markers.
     */
    public void box(List<MapHit.Marker> markers, double x1, double y1, double x2, double y2, boolean add) {
        if (!add) {
            names.clear();
        }
        if (markers == null) {
            return;
        }
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2);
        double maxY = Math.max(y1, y2);
        for (MapHit.Marker m : markers) {
            if (m == null || m.name() == null || m.name().isBlank()) {
                continue;
            }
            if (m.sx() >= minX && m.sx() <= maxX && m.sy() >= minY && m.sy() <= maxY) {
                names.add(m.name());
            }
        }
    }

    /** The names currently held, for a control-group store. */
    public List<String> copy() {
        return new ArrayList<>(names);
    }
}
