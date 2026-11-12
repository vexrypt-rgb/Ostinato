package baritone.swarm.center;

/**
 * Ostinato: edge hit test and resize math for the borderless command center window.
 * No AWT here, so it runs in headless tests.
 */
public final class WindowEdges {

    /** Which sides a press grabs. Corners grab two. */
    public enum Edge {
        NONE(false, false, false, false),
        N(true, false, false, false),
        S(false, true, false, false),
        W(false, false, true, false),
        E(false, false, false, true),
        NW(true, false, true, false),
        NE(true, false, false, true),
        SW(false, true, true, false),
        SE(false, true, false, true);

        public final boolean north;
        public final boolean south;
        public final boolean west;
        public final boolean east;

        Edge(boolean north, boolean south, boolean west, boolean east) {
            this.north = north;
            this.south = south;
            this.west = west;
            this.east = east;
        }

        static Edge of(boolean north, boolean south, boolean west, boolean east) {
            for (Edge edge : values()) {
                if (edge.north == north && edge.south == south && edge.west == west && edge.east == east) {
                    return edge;
                }
            }
            return NONE;
        }
    }

    public record Bounds(int x, int y, int width, int height) {}

    private WindowEdges() {}

    /**
     * The edge under a point, in window coordinates. A point within {@code grip} of a side grabs that side.
     * Near a corner (three grips along the side) it grabs the corner, which is easier to hit than a 5 px square.
     */
    public static Edge hit(int x, int y, int width, int height, int grip) {
        if (width <= 0 || height <= 0 || grip <= 0 || x < 0 || y < 0 || x >= width || y >= height) {
            return Edge.NONE;
        }
        boolean n = y < grip;
        boolean s = y >= height - grip;
        boolean w = x < grip;
        boolean e = x >= width - grip;
        if (!(n || s || w || e)) {
            return Edge.NONE;
        }
        int corner = grip * 3;
        boolean north = n || ((w || e) && y < corner);
        boolean south = s || ((w || e) && y >= height - corner);
        boolean west = w || ((n || s) && x < corner);
        boolean east = e || ((n || s) && x >= width - corner);
        if (north && south) { // a window shorter than two corners: the nearer side wins
            north = y < height / 2;
            south = !north;
        }
        if (west && east) {
            west = x < width / 2;
            east = !west;
        }
        return Edge.of(north, south, west, east);
    }

    /**
     * Bounds after dragging {@code edge} by (dx, dy) from {@code start}. The opposite side stays put,
     * and the size never drops below the minimum.
     */
    public static Bounds resize(Bounds start, Edge edge, int dx, int dy, int minWidth, int minHeight) {
        int x = start.x();
        int y = start.y();
        int w = start.width();
        int h = start.height();
        if (edge.east) {
            w = Math.max(minWidth, w + dx);
        }
        if (edge.south) {
            h = Math.max(minHeight, h + dy);
        }
        if (edge.west) {
            int nw = Math.max(minWidth, w - dx);
            x += w - nw;
            w = nw;
        }
        if (edge.north) {
            int nh = Math.max(minHeight, h - dy);
            y += h - nh;
            h = nh;
        }
        return new Bounds(x, y, w, h);
    }
}
