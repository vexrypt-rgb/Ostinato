package baritone.bastion;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure route planner: orders a bastion's chests (and gold blocks) for the detected layout. No Minecraft types, so it is unit tested.
 * Costs are horizontal distance plus a height term, so a target three floors down is "farther" than one across the room.
 */
public final class BastionPlan {
    private BastionPlan() {}

    public enum Kind { CHEST, GOLD }

    public record Point(int x, int y, int z, Kind kind) {
        double horiz(int ox, int oz) { return Math.hypot(x - ox, z - oz); }
    }

    /** Horizontal radius of a treasure bastion's central room (gold blocks, the loot chests at the bottom). */
    static final int TREASURE_CORE = 12;

    /**
     * @param layout housing, stables, treasure, bridge or empty
     * @param cx,cy,cz bastion centre (the structure start)
     * @param sx,sy,sz where we start (the player)
     */
    public static List<Point> order(String layout, int cx, int cy, int cz, int sx, int sy, int sz, List<Point> points) {
        List<Point> todo = new ArrayList<>(points);
        List<Point> out = new ArrayList<>();
        int px = sx, py = sy, pz = sz;
        while (!todo.isEmpty()) {
            Point best = null;
            double bestCost = Double.MAX_VALUE;
            for (Point p : todo) {
                double c = cost(layout, cx, cz, px, py, pz, p);
                if (c < bestCost) { bestCost = c; best = p; }
            }
            out.add(best);
            todo.remove(best);
            px = best.x; py = best.y; pz = best.z;
        }
        return out;
    }

    static double cost(String layout, int cx, int cz, int px, int py, int pz, Point p) {
        double horiz = Math.hypot(p.x - px, p.z - pz);
        int dy = p.y - py;
        // climbing is slow, dropping is fast but every block past a safe drop has to be walked down
        double vert = dy > 0 ? 2.0 * dy : 0.7 * -dy;
        double c = horiz + vert;
        boolean chest = p.kind == Kind.CHEST;
        switch (layout == null ? "" : layout) {
            case "treasure" -> {
                // the central room holds the good chests and most gold: everything in it first, outer ramparts only after
                if (p.horiz(cx, cz) > TREASURE_CORE) c += 60;
            }
            case "bridge" -> {
                // the bridge chest (and the gold under it) is the prize: chests first, stray gold on the way back
                if (!chest) c += 25;
            }
            case "stables" -> {
                // stables gold sits along the ramps; chests are scattered and often guarded: plain nearest-first, chests slightly ahead
                if (chest) c -= 4;
            }
            default -> {
                // housing: nearest-first; chests are many and cheap, gold is mined only when we run out
                if (!chest) c += 8;
            }
        }
        return c;
    }
}
