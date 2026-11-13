/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.lease;

import java.util.Locale;

/**
 * Ostinato: Phase 2b reservation keys. A reservation is a lease whose resource string names a box in one dimension:
 * {@code v:<dim>:x1,y1,z1:x2,y2,z2} (ore vein bounding box), {@code c:<dim>:x,y,z} (one container) or
 * {@code r:<dim>:x1,y1,z1:x2,y2,z2} (build region). {@code dim} is o, n or e. Any other resource is an untyped 2a key
 * with no geometry; {@link #parse} returns null for it.
 *
 * <p>Keys are canonical: corners are min then max, so the same box always gives the same string, and two keys
 * conflict when their boxes intersect in the same dimension ({@link #conflicts}).
 */
public final class ReservationKey {

    public enum Type { VEIN, CHEST, REGION }

    /** Largest region edge, in blocks. */
    public static final int MAX_EDGE = 64;

    public final Type type;
    public final char dim;
    public final int x1, y1, z1, x2, y2, z2;

    private ReservationKey(Type type, char dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.type = type;
        this.dim = dim;
        this.x1 = Math.min(x1, x2);
        this.y1 = Math.min(y1, y2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.y2 = Math.max(y1, y2);
        this.z2 = Math.max(z1, z2);
    }

    public static ReservationKey vein(char dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        return checked(new ReservationKey(Type.VEIN, dim, x1, y1, z1, x2, y2, z2));
    }

    public static ReservationKey region(char dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        return checked(new ReservationKey(Type.REGION, dim, x1, y1, z1, x2, y2, z2));
    }

    public static ReservationKey chest(char dim, int x, int y, int z) {
        return checked(new ReservationKey(Type.CHEST, dim, x, y, z, x, y, z));
    }

    /**
     * A double chest: both halves name the same reservation, keyed by the half with the smaller (x, z). The box
     * covers both halves.
     */
    public static ReservationKey doubleChest(char dim, int ax, int ay, int az, int bx, int by, int bz) {
        // Ostinato: the constructor sorts corners, so either half gives the same key.
        return checked(new ReservationKey(Type.CHEST, dim, ax, ay, az, bx, by, bz));
    }

    private static ReservationKey checked(ReservationKey k) {
        if (k.dim != 'o' && k.dim != 'n' && k.dim != 'e') {
            throw new IllegalArgumentException("dimension must be o, n or e");
        }
        if (k.x2 - k.x1 >= MAX_EDGE || k.y2 - k.y1 >= MAX_EDGE || k.z2 - k.z1 >= MAX_EDGE) {
            throw new IllegalArgumentException("box edge over " + MAX_EDGE);
        }
        if (k.type == Type.CHEST && (k.y1 != k.y2 || (k.x2 - k.x1) + (k.z2 - k.z1) > 1)) {
            throw new IllegalArgumentException("a chest is one block or two side by side");
        }
        return k;
    }

    /** The key, or null for an untyped resource. Malformed typed keys (right prefix, bad body) are null too. */
    public static ReservationKey parse(String resource) {
        if (resource == null || resource.length() < 4 || resource.charAt(1) != ':') {
            return null;
        }
        char t = resource.charAt(0);
        Type type = t == 'v' ? Type.VEIN : t == 'c' ? Type.CHEST : t == 'r' ? Type.REGION : null;
        if (type == null) {
            return null;
        }
        String[] p = resource.split(":", -1);
        try {
            // Chest: 3 parts (single) or 4 (double). Vein and region: always 4.
            boolean okLen = p.length == 4 || (p.length == 3 && type == Type.CHEST);
            if (!okLen || p[1].length() != 1) {
                return null;
            }
            char dim = p[1].charAt(0);
            int[] a = ints(p[2]);
            int[] b = p.length == 4 ? ints(p[3]) : null;
            ReservationKey k = b == null ? new ReservationKey(type, dim, a[0], a[1], a[2], a[0], a[1], a[2])
                    : new ReservationKey(type, dim, a[0], a[1], a[2], b[0], b[1], b[2]);
            checked(k);
            return k.toString().equals(resource) ? k : null; // Ostinato: only the canonical spelling is a key
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] ints(String s) {
        String[] f = s.split(",", -1);
        if (f.length != 3) {
            throw new IllegalArgumentException("need x,y,z");
        }
        return new int[]{Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])};
    }

    /** True when both boxes are in one dimension and share at least one block. */
    public boolean overlaps(ReservationKey o) {
        return dim == o.dim && x1 <= o.x2 && o.x1 <= x2 && y1 <= o.y2 && o.y1 <= y2 && z1 <= o.z2 && o.z1 <= z2;
    }

    public boolean contains(char d, int x, int y, int z) {
        return d == dim && x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    /**
     * Two resource strings conflict when they are equal, or both are reservation keys whose boxes intersect.
     * Untyped keys only conflict with themselves.
     */
    public static boolean conflicts(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        ReservationKey ka = parse(a);
        if (ka == null) {
            return false;
        }
        ReservationKey kb = parse(b);
        return kb != null && ka.overlaps(kb);
    }

    @Override
    public String toString() {
        String c = type == Type.VEIN ? "v" : type == Type.CHEST ? "c" : "r";
        String a = x1 + "," + y1 + "," + z1;
        if (type == Type.CHEST && x1 == x2 && z1 == z2) {
            return c + ":" + dim + ":" + a;
        }
        return c + ":" + dim + ":" + a + ":" + x2 + "," + y2 + "," + z2;
    }

    public String typeName() {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
