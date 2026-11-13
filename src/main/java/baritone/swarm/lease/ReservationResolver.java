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

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Ostinato: Phase 2b. Turns the operator's shorthand into a canonical reservation key on the coordinator:
 * {@code vein:x,y,z} (any block of the vein), {@code chest:x,y,z} (either half), {@code region:x,y,z:x,y,z}.
 * Anything else is returned unchanged (a 2a key or an already canonical v:/c:/r: key).
 */
public final class ReservationResolver {

    /** Largest vein the flood fill follows; bigger is rejected rather than truncated (a cut vein has no stable ID). */
    public static final int VEIN_CAP = 64;

    /** World view of the coordinator. Both return null for unloaded positions. */
    public interface World {
        /** Block id, e.g. {@code minecraft:iron_ore}, or null when not loaded. */
        String block(int x, int y, int z);

        /** For a double chest at the position, the other half; else null. */
        int[] otherChestHalf(int x, int y, int z);
    }

    /** Result: a canonical key, or an error. */
    public static final class Result {
        public final String key;
        public final String error;

        private Result(String key, String error) {
            this.key = key;
            this.error = error;
        }

        static Result ok(String k) {
            return new Result(k, null);
        }

        static Result fail(String e) {
            return new Result(null, e);
        }
    }

    private ReservationResolver() {}

    public static Result resolve(String spec, char dim, World w) {
        int c = spec.indexOf(':');
        String kind = c < 0 ? "" : spec.substring(0, c);
        String body = c < 0 ? "" : spec.substring(c + 1);
        try {
            switch (kind) {
                case "vein": {
                    int[] p = ints(body);
                    return vein(dim, p[0], p[1], p[2], w);
                }
                case "chest": {
                    int[] p = ints(body);
                    String b = w.block(p[0], p[1], p[2]);
                    if (b == null) {
                        return Result.fail("chest not loaded");
                    }
                    int[] o = w.otherChestHalf(p[0], p[1], p[2]);
                    ReservationKey k = o == null ? ReservationKey.chest(dim, p[0], p[1], p[2])
                            : ReservationKey.doubleChest(dim, p[0], p[1], p[2], o[0], o[1], o[2]);
                    return Result.ok(k.toString());
                }
                case "region": {
                    int m = body.indexOf(':');
                    if (m < 0) {
                        return Result.fail("region needs x,y,z:x,y,z");
                    }
                    int[] a = ints(body.substring(0, m));
                    int[] b = ints(body.substring(m + 1));
                    return Result.ok(ReservationKey.region(dim, a[0], a[1], a[2], b[0], b[1], b[2]).toString());
                }
                default:
                    return Result.ok(spec);
            }
        } catch (RuntimeException e) {
            return Result.fail("bad " + kind + " spec: " + e.getMessage());
        }
    }

    /** Six-neighbour flood fill over blocks with the seed's id; the key is the bounding box. */
    static Result vein(char dim, int sx, int sy, int sz, World w) {
        String ore = w.block(sx, sy, sz);
        if (ore == null) {
            return Result.fail("vein not loaded");
        }
        if (!ore.endsWith("_ore") && !ore.endsWith("ancient_debris")) {
            return Result.fail("not an ore: " + ore);
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sy, sz});
        seen.add(pack(sx, sy, sz));
        int x1 = sx, y1 = sy, z1 = sz, x2 = sx, y2 = sy, z2 = sz;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            x1 = Math.min(x1, p[0]);
            y1 = Math.min(y1, p[1]);
            z1 = Math.min(z1, p[2]);
            x2 = Math.max(x2, p[0]);
            y2 = Math.max(y2, p[1]);
            z2 = Math.max(z2, p[2]);
            for (int[] d : dirs) {
                int nx = p[0] + d[0], ny = p[1] + d[1], nz = p[2] + d[2];
                if (seen.contains(pack(nx, ny, nz))) {
                    continue;
                }
                String b = w.block(nx, ny, nz);
                if (b == null) {
                    return Result.fail("vein runs into unloaded chunks");
                }
                if (b.equals(ore)) {
                    seen.add(pack(nx, ny, nz));
                    if (seen.size() > VEIN_CAP) {
                        return Result.fail("vein over " + VEIN_CAP + " blocks");
                    }
                    q.add(new int[]{nx, ny, nz});
                }
            }
        }
        return Result.ok(ReservationKey.vein(dim, x1, y1, z1, x2, y2, z2).toString());
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int[] ints(String s) {
        String[] f = s.split(",", -1);
        if (f.length != 3) {
            throw new IllegalArgumentException("need x,y,z");
        }
        return new int[]{Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim())};
    }
}
