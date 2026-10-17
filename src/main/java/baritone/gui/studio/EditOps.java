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

/**
 * Box edits shared by the schematic studio and the freecam canvas.
 * Counts are cells written. A negative count means the edit does not fit and nothing was changed.
 */
public final class EditOps {

    public enum Tool {
        BRUSH("brush"),
        SELECT("select"),
        FILL("fill"),
        REPLACE("replace"),
        LINE("line"),
        SPHERE("sphere"),
        HOLLOW("hollow"),
        WALL("wall"),
        COPY("copy"),
        PASTE("paste"),
        ROTATE("rotate"),
        FLIP("flip");

        public final String wire;

        Tool(String wire) {
            this.wire = wire;
        }

        public static Tool byName(String name) {
            if (name == null) {
                return null;
            }
            for (Tool tool : values()) {
                if (tool.wire.equalsIgnoreCase(name)) {
                    return tool;
                }
            }
            return null;
        }
    }

    /** Inclusive box. */
    public static final class Box {
        public final int x0;
        public final int y0;
        public final int z0;
        public final int x1;
        public final int y1;
        public final int z1;

        private Box(int x0, int y0, int z0, int x1, int y1, int z1) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
        }

        public static Box of(int ax, int ay, int az, int bx, int by, int bz) {
            return new Box(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                    Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
        }
    }

    /** A copied region, including air, so a paste can put the shape back exactly. */
    public static final class Clipboard {
        public final int sx;
        public final int sy;
        public final int sz;
        public final int[] cells;

        Clipboard(int sx, int sy, int sz, int[] cells) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.cells = cells;
        }

        public Clipboard rotateY() {
            int nsx = sz;
            int nsz = sx;
            int[] next = new int[cells.length];
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        int nx = sz - 1 - z;
                        int nz = x;
                        next[(y * nsz + nz) * nsx + nx] = cells[(y * sz + z) * sx + x];
                    }
                }
            }
            return new Clipboard(nsx, sy, nsz, next);
        }

        public Clipboard flipX() {
            int[] next = new int[cells.length];
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        int nx = sx - 1 - x;
                        next[(y * sz + z) * sx + nx] = cells[(y * sz + z) * sx + x];
                    }
                }
            }
            return new Clipboard(sx, sy, sz, next);
        }
    }

    private EditOps() {}

    public static int fill(StudioVolume vol, Box box, int cell) {
        int n = 0;
        for (int y = box.y0; y <= box.y1; y++) {
            for (int z = box.z0; z <= box.z1; z++) {
                for (int x = box.x0; x <= box.x1; x++) {
                    if (put(vol, x, y, z, cell)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public static int replace(StudioVolume vol, Box box, int from, int to) {
        int n = 0;
        for (int y = box.y0; y <= box.y1; y++) {
            for (int z = box.z0; z <= box.z1; z++) {
                for (int x = box.x0; x <= box.x1; x++) {
                    if (vol.inside(x, y, z) && vol.get(x, y, z) == from && put(vol, x, y, z, to)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** The six faces of the box, one block thick. */
    public static int wall(StudioVolume vol, Box box, int cell) {
        int n = 0;
        for (int y = box.y0; y <= box.y1; y++) {
            for (int z = box.z0; z <= box.z1; z++) {
                for (int x = box.x0; x <= box.x1; x++) {
                    boolean skin = x == box.x0 || x == box.x1 || y == box.y0 || y == box.y1 || z == box.z0 || z == box.z1;
                    if (skin && put(vol, x, y, z, cell)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public static int line(StudioVolume vol, int x0, int y0, int z0, int x1, int y1, int z1, int cell) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int dz = Math.abs(z1 - z0);
        int steps = Math.max(dx, Math.max(dy, dz));
        int n = 0;
        if (steps == 0) {
            return put(vol, x0, y0, z0, cell) ? 1 : 0;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (int) Math.round((x1 - x0) * (i / (double) steps));
            int y = y0 + (int) Math.round((y1 - y0) * (i / (double) steps));
            int z = z0 + (int) Math.round((z1 - z0) * (i / (double) steps));
            if (put(vol, x, y, z, cell)) {
                n++;
            }
        }
        return n;
    }

    public static int sphere(StudioVolume vol, int cx, int cy, int cz, int radius, int cell, boolean hollow) {
        int r = Math.max(1, radius);
        int r2 = r * r;
        int inner = hollow ? (r - 1) * (r - 1) : -1;
        int n = 0;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    int d = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
                    if (d <= r2 && d >= inner && put(vol, x, y, z, cell)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public static Clipboard copy(StudioVolume vol, Box box) {
        int sx = box.x1 - box.x0 + 1;
        int sy = box.y1 - box.y0 + 1;
        int sz = box.z1 - box.z0 + 1;
        int[] cells = new int[sx * sy * sz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    cells[(y * sz + z) * sx + x] = vol.get(box.x0 + x, box.y0 + y, box.z0 + z);
                }
            }
        }
        return new Clipboard(sx, sy, sz, cells);
    }

    /** Writes every clipboard cell, including air. Cells that fall outside the volume are skipped. */
    public static int paste(StudioVolume vol, int x, int y, int z, Clipboard clip) {
        if (clip == null) {
            return 0;
        }
        int n = 0;
        for (int iy = 0; iy < clip.sy; iy++) {
            for (int iz = 0; iz < clip.sz; iz++) {
                for (int ix = 0; ix < clip.sx; ix++) {
                    if (put(vol, x + ix, y + iy, z + iz, clip.cells[(iy * clip.sz + iz) * clip.sx + ix])) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public static int rotateSelection(StudioVolume vol, Box box) {
        Clipboard turned = copy(vol, box).rotateY();
        if (!fits(vol, box.x0, box.y0, box.z0, turned)) {
            return -1;
        }
        fill(vol, box, 0);
        return paste(vol, box.x0, box.y0, box.z0, turned);
    }

    public static int flipSelection(StudioVolume vol, Box box) {
        Clipboard flipped = copy(vol, box).flipX();
        fill(vol, box, 0);
        return paste(vol, box.x0, box.y0, box.z0, flipped);
    }

    private static boolean fits(StudioVolume vol, int x, int y, int z, Clipboard clip) {
        return x >= 0 && y >= 0 && z >= 0
                && x + clip.sx <= vol.sizeX()
                && y + clip.sy <= vol.sizeY()
                && z + clip.sz <= vol.sizeZ();
    }

    private static boolean put(StudioVolume vol, int x, int y, int z, int cell) {
        int was = vol.set(x, y, z, cell);
        return was >= 0 && was != Math.max(0, cell);
    }
}
