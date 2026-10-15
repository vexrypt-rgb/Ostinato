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

package baritone.gui.studio;

/** Ray against the volume. A miss on empty space can still land on the floor cell. */
public final class StudioPick {

    public final boolean solid;
    public final int x;
    public final int y;
    public final int z;
    public final int placeX;
    public final int placeY;
    public final int placeZ;
    public final boolean canPlace;

    private StudioPick(boolean solid, int x, int y, int z, int placeX, int placeY, int placeZ, boolean canPlace) {
        this.solid = solid;
        this.x = x;
        this.y = y;
        this.z = z;
        this.placeX = placeX;
        this.placeY = placeY;
        this.placeZ = placeZ;
        this.canPlace = canPlace;
    }

    public static StudioPick miss() {
        return new StudioPick(false, -1, -1, -1, -1, -1, -1, false);
    }

    public static StudioPick trace(double ox, double oy, double oz, double dx, double dy, double dz, StudioVolume vol) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) {
            return miss();
        }
        dx /= len;
        dy /= len;
        dz /= len;
        double[] span = {0, 1e6};
        if (!slab(ox, dx, 0, vol.sizeX(), span) || !slab(oy, dy, 0, vol.sizeY(), span) || !slab(oz, dz, 0, vol.sizeZ(), span)) {
            return floor(ox, oy, oz, dx, dy, dz, vol);
        }
        double t = Math.max(span[0], 0) + 1e-5;
        if (t > span[1]) {
            return floor(ox, oy, oz, dx, dy, dz, vol);
        }
        double px = ox + dx * t;
        double py = oy + dy * t;
        double pz = oz + dz * t;
        int x = (int) Math.floor(px);
        int y = (int) Math.floor(py);
        int z = (int) Math.floor(pz);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = Math.abs(dx) < 1e-9 ? 1e30 : 1.0 / Math.abs(dx);
        double tDeltaY = Math.abs(dy) < 1e-9 ? 1e30 : 1.0 / Math.abs(dy);
        double tDeltaZ = Math.abs(dz) < 1e-9 ? 1e30 : 1.0 / Math.abs(dz);
        double tMaxX = nextBoundary(px, dx, x, stepX);
        double tMaxY = nextBoundary(py, dy, y, stepY);
        double tMaxZ = nextBoundary(pz, dz, z, stepZ);
        int prevX = -1;
        int prevY = -1;
        int prevZ = -1;
        int guard = vol.sizeX() + vol.sizeY() + vol.sizeZ() + 4;
        for (int n = 0; n < guard; n++) {
            if (!vol.inside(x, y, z)) {
                break;
            }
            if (vol.get(x, y, z) != 0) {
                boolean place = vol.inside(prevX, prevY, prevZ) && vol.get(prevX, prevY, prevZ) == 0;
                return new StudioPick(true, x, y, z, prevX, prevY, prevZ, place);
            }
            prevX = x;
            prevY = y;
            prevZ = z;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return floor(ox, oy, oz, dx, dy, dz, vol);
    }

    private static double nextBoundary(double p, double d, int cell, int step) {
        if (Math.abs(d) < 1e-9) {
            return 1e30;
        }
        double edge = step > 0 ? cell + 1 : cell;
        return (edge - p) / d;
    }

    private static boolean slab(double o, double d, double min, double max, double[] t) {
        if (Math.abs(d) < 1e-9) {
            return o >= min && o <= max;
        }
        double a = (min - o) / d;
        double b = (max - o) / d;
        if (a > b) {
            double s = a;
            a = b;
            b = s;
        }
        t[0] = Math.max(t[0], a);
        t[1] = Math.min(t[1], b);
        return t[0] <= t[1];
    }

    /** The y = 0 plane, which is the floor of the first layer. */
    private static StudioPick floor(double ox, double oy, double oz, double dx, double dy, double dz, StudioVolume vol) {
        if (dy >= -1e-9) {
            return miss();
        }
        double t = (0 - oy) / dy;
        if (t < 0) {
            return miss();
        }
        double hx = ox + dx * t;
        double hz = oz + dz * t;
        int x = (int) Math.floor(hx);
        int z = (int) Math.floor(hz);
        if (!vol.inside(x, 0, z) || vol.get(x, 0, z) != 0) {
            return miss();
        }
        return new StudioPick(false, -1, -1, -1, x, 0, z, true);
    }
}
