/*
 * Ostinato: development and held-out worlds for the lattice experiment. Not part of upstream Baritone.
 * The held-out list is fixed here, before any result is read.
 */
package baritone.lattice;

import java.util.ArrayList;
import java.util.List;

/** One headless scenario. Feet start at y=0 on a block whose top is y=0, unless the case says otherwise. */
public record LatticeCase(String split, String id, LatticeBlocks world, double startX, double startZ, float startYaw,
                          double goalX, double goalY, double goalZ) {
    public static List<LatticeCase> development() {
        List<LatticeCase> cases = new ArrayList<>();
        cases.add(flat("dev", "flat-4", -1, 6, 4.5));
        cases.add(flat("dev", "flat-5", -1, 8, 5.5));
        cases.add(of("dev", "turn-90", floor(-1, 6, -1, -1, 2), 0.5, 0.5, 0f, 4.5, 0, 0.5));
        cases.add(of("dev", "turn-180", floor(-1, 1, -1, -1, 6), 0.5, 4.5, 0f, 0.5, 0, 0.5));
        LatticeBlocks ascend = step(-1, 1, -1, 3, 4, 6);
        cases.add(of("dev", "ascend", ascend, 0.5, 0.5, 0f, 0.5, 1, 5.5));
        cases.add(of("dev", "ascend-short", ascend, 0.5, 0.5, 0f, 0.5, 1, 4.5));
        LatticeBlocks gap1 = new LatticeBlocks();
        gap1.fill(-1, 1, -1, -1, 2);
        gap1.fill(-1, 1, -1, 4, 7);
        cases.add(of("dev", "gap-1", gap1, 0.5, 0.5, 0f, 0.5, 0, 5.5));
        LatticeBlocks gap2 = new LatticeBlocks();
        gap2.fill(0, 0, -1, -2, 2);
        gap2.fill(0, 0, -1, 5, 8);
        cases.add(of("dev", "gap-2", gap2, 0.5, 0.5, 0f, 0.5, 0, 6.5));
        LatticeBlocks corner = new LatticeBlocks();
        corner.fill(0, 0, -1, 0, 3);
        corner.fill(0, 4, -1, 3, 3);
        cases.add(of("dev", "corner", corner, 0.5, 0.5, 0f, 3.5, 0, 3.5));
        cases.add(of("dev", "diagonal", floor(-1, 8, -1, -1, 8), 0.5, 0.5, 0f, 4.5, 0, 4.5));
        cases.add(flat("dev", "walk-8", -1, 10, 8.5));
        LatticeBlocks ascendX = new LatticeBlocks();
        ascendX.fill(-1, 3, -1, -1, 1);
        ascendX.fill(4, 6, -1, -1, 1);
        ascendX.fill(4, 6, 0, -1, 1);
        cases.add(of("dev", "ascend-x", ascendX, 0.5, 0.5, 0f, 5.5, 1, 0.5));
        return List.copyOf(cases);
    }

    /** Defined with the dev list. The bench runs it only when a dev method reaches 0.7. */
    public static List<LatticeCase> heldOut() {
        List<LatticeCase> cases = new ArrayList<>();
        cases.add(flat("held", "flat-3", -1, 5, 3.5));
        cases.add(flat("held", "flat-6", -1, 8, 6.5));
        cases.add(flat("held", "flat-7", -1, 9, 7.5));
        cases.add(flat("held", "flat-9", -1, 11, 9.5));
        cases.add(of("held", "diagonal-3", floor(-1, 5, -1, -1, 5), 0.5, 0.5, 0f, 3.5, 0, 3.5));
        cases.add(of("held", "diagonal-6", floor(-1, 8, -1, -1, 8), 0.5, 0.5, 0f, 6.5, 0, 6.5));
        cases.add(of("held", "turn-neg-x", floor(-6, 2, -1, -1, 2), 0.5, 0.5, 0f, -4.5, 0, 0.5));
        LatticeBlocks gapLong = new LatticeBlocks();
        gapLong.fill(0, 0, -1, -1, 4);
        gapLong.fill(0, 0, -1, 6, 9);
        cases.add(of("held", "gap-1-long", gapLong, 0.5, 0.5, 0f, 0.5, 0, 7.5));
        LatticeBlocks gapFar = new LatticeBlocks();
        gapFar.fill(0, 0, -1, -1, 4);
        gapFar.fill(0, 0, -1, 7, 10);
        cases.add(of("held", "gap-2-far", gapFar, 0.5, 0.5, 0f, 0.5, 0, 8.5));
        cases.add(of("held", "ascend-far", step(-1, 1, -1, 5, 6, 9), 0.5, 0.5, 0f, 0.5, 1, 8.5));
        LatticeBlocks cornerLong = new LatticeBlocks();
        cornerLong.fill(0, 0, -1, 0, 5);
        cornerLong.fill(0, 6, -1, 5, 5);
        cases.add(of("held", "corner-long", cornerLong, 0.5, 0.5, 0f, 5.5, 0, 5.5));
        cases.add(of("held", "wide-sprint", floor(-2, 2, -1, -1, 12), 0.5, 0.5, 0f, 0.5, 0, 10.5));
        cases.add(of("held", "short-step", step(-1, 1, -1, 1, 2, 4), 0.5, 0.5, 0f, 0.5, 1, 2.5));
        cases.add(of("held", "diag-short", floor(-1, 4, -1, -1, 4), 0.5, 0.5, 0f, 2.5, 0, 2.5));
        cases.add(of("held", "yaw-90-to-z", floor(-1, 1, -1, -1, 6), 0.5, 0.5, 90f, 0.5, 0, 4.5));
        LatticeBlocks tight = new LatticeBlocks();
        tight.fill(0, 0, -1, -1, 2);
        tight.fill(0, 0, -1, 4, 7);
        cases.add(of("held", "gap-1-tight", tight, 0.5, 0.5, 0f, 0.5, 0, 5.5));
        return List.copyOf(cases);
    }

    private static LatticeCase flat(String split, String id, int z0, int z1, double goalZ) {
        return of(split, id, floor(-1, 1, -1, z0, z1), 0.5, 0.5, 0f, 0.5, 0, goalZ);
    }

    /** Low floor through {@code lowZ1}, then a one-block step from {@code highZ0} through {@code highZ1}. */
    private static LatticeBlocks step(int x0, int x1, int z0, int lowZ1, int highZ0, int highZ1) {
        LatticeBlocks blocks = new LatticeBlocks();
        blocks.fill(x0, x1, -1, z0, lowZ1);
        blocks.fill(x0, x1, -1, highZ0, highZ1);
        blocks.fill(x0, x1, 0, highZ0, highZ1);
        return blocks;
    }

    private static LatticeBlocks floor(int x0, int x1, int y, int z0, int z1) {
        return new LatticeBlocks().fill(x0, x1, y, z0, z1);
    }

    private static LatticeCase of(String split, String id, LatticeBlocks world, double sx, double sz, float yaw,
                                  double gx, double gy, double gz) {
        return new LatticeCase(split, id, world, sx, sz, yaw, gx, gy, gz);
    }
}
