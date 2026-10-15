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
 * A small block grid. Cell 0 is air. The text form is the {@code .ostinato} schematic:
 * a header, then one {@code x y z block_id} line per placed block.
 */
public final class StudioVolume {

    public static final int MAX = 32;

    private final int sx;
    private final int sy;
    private final int sz;
    private final byte[] cells;

    public StudioVolume(int sx, int sy, int sz) {
        if (sx < 1 || sy < 1 || sz < 1 || sx > MAX || sy > MAX || sz > MAX) {
            throw new IllegalArgumentException("volume is limited to " + MAX);
        }
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = new byte[sx * sy * sz];
    }

    public int sizeX() {
        return sx;
    }

    public int sizeY() {
        return sy;
    }

    public int sizeZ() {
        return sz;
    }

    public int get(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return 0;
        }
        return cells[index(x, y, z)] & 0xFF;
    }

    /** Writes a cell and returns what was there, or -1 when the coordinate is outside. */
    public int set(int x, int y, int z, int cell) {
        if (!inside(x, y, z)) {
            return -1;
        }
        int i = index(x, y, z);
        int was = cells[i] & 0xFF;
        cells[i] = (byte) cell;
        return was;
    }

    public int count() {
        int n = 0;
        for (byte c : cells) {
            if (c != 0) {
                n++;
            }
        }
        return n;
    }

    public void clear() {
        for (int i = 0; i < cells.length; i++) {
            cells[i] = 0;
        }
    }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    public String write() {
        StringBuilder sb = new StringBuilder();
        sb.append("ost1\n").append(sx).append(' ').append(sy).append(' ').append(sz).append('\n');
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int cell = get(x, y, z);
                    if (cell != 0) {
                        sb.append(x).append(' ').append(y).append(' ').append(z).append(' ')
                                .append(BlockPalette.name(cell)).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    public static StudioVolume read(String text) {
        String[] lines = text.split("\\R");
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].trim().equals("ost1")) {
            throw new IllegalArgumentException("not an ostinato schematic");
        }
        i++;
        if (i >= lines.length) {
            throw new IllegalArgumentException("missing size");
        }
        String[] dim = lines[i++].trim().split(" ");
        if (dim.length != 3) {
            throw new IllegalArgumentException("size wants three numbers");
        }
        StudioVolume vol = new StudioVolume(Integer.parseInt(dim[0]), Integer.parseInt(dim[1]), Integer.parseInt(dim[2]));
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split(" ");
            if (p.length != 4) {
                throw new IllegalArgumentException("bad block line: " + line);
            }
            int cell = BlockPalette.cell(p[3]);
            if (cell == 0) {
                throw new IllegalArgumentException("unknown block " + p[3]);
            }
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            if (vol.set(x, y, z, cell) < 0) {
                throw new IllegalArgumentException("block outside the volume: " + line);
            }
        }
        return vol;
    }

    private int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }
}
