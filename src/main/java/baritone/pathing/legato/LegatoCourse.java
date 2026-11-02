/*
 * Ostinato: new file (Legato). Not part of upstream Baritone.
 */
package baritone.pathing.legato;

import java.util.List;

/**
 * The Legato live A/B course: a walled stone arena, {@link #SIZE} blocks square with its floor at y {@link #F}, cut
 * into four strips by two-high walls, one leg per strip. Every goal is off the 45 degree grid.
 * <ul>
 * <li>open: nothing in the way;</li>
 * <li>wall: a wall across the strip with a gap at one end;</li>
 * <li>pillars: a fixed scatter of one-block pillars;</li>
 * <li>corridor: a three-wide corridor that opens into a room.</li>
 * </ul>
 * Pure geometry, so the headless test plans the same legs the live run walks.
 */
public final class LegatoCourse {
    public static final int F = 200, SIZE = 84;

    public record Leg(String name, int sx, int sz, int gx, int gz) {}

    public static final List<Leg> LEGS = List.of(
            new Leg("open", 3, 3, 81, 18),
            new Leg("wall", 3, 24, 81, 24),
            new Leg("pillars", 3, 45, 81, 60),
            new Leg("corridor", 3, 67, 81, 82));

    private LegatoCourse() {}

    /** Is there a two-high wall (feet and head) at this column of the arena? */
    public static boolean wall(int x, int z) {
        if (x <= 0 || z <= 0 || x >= SIZE || z >= SIZE) {
            return true; // the border
        }
        if (z == 21 || z == 42 || z == 63) {
            return true; // between the strips
        }
        if (x == 42 && z >= 22 && z <= 36) {
            return true; // the wall leg's wall, open at z 37..41
        }
        if (z >= 44 && z <= 61 && x >= 12 && x <= 72 && (mix(x, z) & 15) == 0) {
            return true; // the pillar field
        }
        return (z == 65 || z == 69) && x <= 30; // the corridor's sides
    }

    private static int mix(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return h ^ h >>> 15;
    }

    /** Is the block at (x, y, z) solid on the course? (floor at F, walls at F + 1 and F + 2, air above) */
    public static boolean solid(int x, int y, int z) {
        if (x < 0 || z < 0 || x > SIZE || z > SIZE) {
            return false;
        }
        if (y == F) {
            return true;
        }
        return (y == F + 1 || y == F + 2) && wall(x, z);
    }
}
