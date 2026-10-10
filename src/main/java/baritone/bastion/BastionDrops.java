package baritone.bastion;

import java.util.List;

/**
 * Pure fall logic. A "verified" drop is a ledge whose landing column has been checked (solid floor, no lava or magma beside it)
 * and whose fall damage leaves a margin; those may exceed the planner's 3-block cap, which is how the fast speedrun lines
 * (treasure: straight down into the central room) beat the long stairs.
 */
public final class BastionDrops {
    private BastionDrops() {}

    /** Hearts margin (half-hearts) a verified drop must leave. */
    public static final float MARGIN = 6;

    /** Vanilla fall damage: one per block past three (no feather falling or armour assumed: conservative). */
    public static float damage(double fallBlocks) {
        return (float) Math.max(0, Math.ceil(fallBlocks - 3));
    }

    /** One candidate: stand on (x, y, z), step off in direction (dx, dz), land with feet at landY. */
    public record Ledge(int x, int y, int z, int dx, int dz, int landY, boolean landingSafe) {
        public int height() { return y - landY; }
    }

    public static boolean survivable(Ledge l, float health) {
        return l.landingSafe && damage(l.height()) <= health - MARGIN;
    }

    /**
     * Best ledge for getting from (mx, my, mz) to a target (tx, ty, tz) that sits below us: walk to the ledge, drop, walk on.
     * Only drops that save height worth the detour are taken; null when walking is as good.
     */
    public static Ledge choose(List<Ledge> ledges, int mx, int my, int mz, int tx, int ty, int tz, float health, int cap) {
        Ledge best = null;
        double bestCost = Double.MAX_VALUE;
        for (Ledge l : ledges) {
            if (l.height() <= cap || !survivable(l, health)) continue;
            // must actually bring us down toward the target, not past it
            if (l.landY < ty - 2) continue;
            double walk = Math.hypot(l.x - mx, l.z - mz) + 2.0 * Math.max(0, l.y - my);
            double after = Math.hypot(l.x + l.dx - tx, l.z + l.dz - tz) + 2.0 * Math.max(0, ty - l.landY);
            double c = walk + after + 0.5 * damage(l.height());
            if (c < bestCost) { bestCost = c; best = l; }
        }
        // walking down costs about two blocks of path per block of height: the drop must beat that clearly
        double walkDown = Math.hypot(tx - mx, tz - mz) + 2.0 * Math.max(0, my - ty);
        return best != null && bestCost < walkDown - 4 ? best : null;
    }

    public enum Clutch { NONE, PLACE }

    /**
     * While falling: place a block to stop now when the rest of the fall would hurt too much. Damage counts the whole fall, so
     * the clutch has to happen early: a block under us after 2 blocks lands us with no damage; one at the bottom saves nothing.
     *
     * @param fallen     blocks already fallen this fall
     * @param remaining  blocks still to fall (to the first solid floor, lava counted as no floor)
     * @param intoLava   the floor below is lava
     * @param planned    this fall is a verified drop we chose
     */
    public static Clutch clutch(double fallen, double remaining, boolean intoLava, boolean planned, float health, int cap, boolean haveBlock) {
        if (!haveBlock || remaining < 1.2) return Clutch.NONE;
        if (planned && !intoLava) return Clutch.NONE;
        float dmg = damage(fallen + remaining);
        if (intoLava || dmg >= health - 2 || fallen + remaining > cap + 1 && dmg > 0) {
            // too late to help: we would land on our own block with the same damage, but still out of lava
            return damage(fallen + 1) < dmg || intoLava ? Clutch.PLACE : Clutch.NONE;
        }
        return Clutch.NONE;
    }
}