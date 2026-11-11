package baritone.swarm.center;

import java.util.List;

/**
 * Ostinato: put the command center on the second monitor.
 * A point that already lands on a screen is used as-is, so {@code -Dostinato.windowPos=2600,40} stays put.
 * A point on no screen moves to the second screen. One screen keeps the point.
 */
public final class WindowPlacement {

    public record Screen(int x, int y, int width, int height) {
        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + Math.max(width, 1) && py < y + Math.max(height, 1);
        }
    }

    private WindowPlacement() {}

    public static int[] place(List<Screen> screens, String windowPos) {
        int[] point = FocusPolicy.position(windowPos);
        if (screens == null || screens.isEmpty()) {
            return point;
        }
        for (Screen screen : screens) {
            if (screen != null && screen.contains(point[0], point[1])) {
                return point;
            }
        }
        if (screens.size() >= 2 && screens.get(1) != null) {
            Screen second = screens.get(1);
            return new int[]{second.x + 40, second.y + 40};
        }
        return point;
    }
}
