package baritone.swarm.center.app;

/**
 * Ostinato: Phase 3 window, the war-room camera. Top-down XZ: +X is right, +Z is down, one zoom pixel per block.
 * The conversion is its own inverse. Pan keeps the center inside the Minecraft world limit.
 */
public final class MapCamera {

    public static final int MIN_ZOOM = 1;
    public static final int MAX_ZOOM = 16;
    /** Inclusive center limit, either axis, in blocks. */
    public static final double WORLD_LIMIT = 30_000_000;

    private int zoom = 4;
    private double centerX;
    private double centerZ;
    private int width = 1;
    private int height = 1;

    public int zoom() { return zoom; }
    public double centerX() { return centerX; }
    public double centerZ() { return centerZ; }
    public int width() { return width; }
    public int height() { return height; }

    public void size(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public void setZoom(int zoom) {
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    public void setCenter(double x, double z) {
        centerX = x;
        centerZ = z;
        clamp();
    }

    /** Drag the map by screen pixels. The world point under the cursor follows the pointer. */
    public void panPixels(double dx, double dy) {
        centerX -= dx / zoom;
        centerZ -= dy / zoom;
        clamp();
    }

    /**
     * Zoom by {@code steps} (positive zooms in) keeping the world point under {@code (sx, sy)} fixed, then clamp.
     */
    public void zoomAt(double sx, double sy, int steps) {
        if (steps == 0) {
            return;
        }
        double[] world = screenToWorld(sx, sy);
        int next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + steps));
        if (next == zoom) {
            clamp();
            return;
        }
        zoom = next;
        centerX = world[0] - (sx - width / 2.0) / zoom;
        centerZ = world[1] - (sy - height / 2.0) / zoom;
        clamp();
    }

    public void clamp() {
        centerX = Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, centerX));
        centerZ = Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, centerZ));
    }

    public double screenX(double worldX) {
        return width / 2.0 + (worldX - centerX) * zoom;
    }

    public double screenZ(double worldZ) {
        return height / 2.0 + (worldZ - centerZ) * zoom;
    }

    public double[] worldToScreen(double worldX, double worldZ) {
        return new double[]{screenX(worldX), screenZ(worldZ)};
    }

    public double[] screenToWorld(double sx, double sy) {
        return new double[]{centerX + (sx - width / 2.0) / zoom, centerZ + (sy - height / 2.0) / zoom};
    }
}
