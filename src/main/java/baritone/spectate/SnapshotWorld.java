/*
 * Ostinato: new file (spectator learning). Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.pathing.kinematic.PlayerSim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ostinato: a frozen copy of the blocks around one watched player, taken on the game thread so the decoder can run
 * {@link PlayerSim} on a worker thread without touching the live world. Cells outside the copied box read as air
 * with plain friction, which only matters for a player who moves further in one interval than the margin covers.
 */
public final class SnapshotWorld implements PlayerSim.World {
    private final int x0, y0, z0, nx, ny, nz;
    private final List<double[]>[] boxes;
    private final float[] slip, speed, jump;
    private final byte[] flags; // 1 water, 2 climbable, 4 bouncy, 8 sticky

    @SuppressWarnings("unchecked")
    private SnapshotWorld(int x0, int y0, int z0, int nx, int ny, int nz) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        int n = nx * ny * nz;
        boxes = new List[n];
        slip = new float[n];
        speed = new float[n];
        jump = new float[n];
        flags = new byte[n];
    }

    /** Copies every cell of {@code src} from (x0,y0,z0) to (x1,y1,z1) inclusive. */
    public static SnapshotWorld capture(PlayerSim.World src, int x0, int y0, int z0, int x1, int y1, int z1) {
        SnapshotWorld w = new SnapshotWorld(x0, y0, z0, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
        List<double[]> tmp = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int i = w.index(x, y, z);
                    tmp.clear();
                    if (!src.cellBoxes(x, y, z, tmp)) {
                        src.collect(x + 1e-7, y + 1e-7, z + 1e-7, x + 1 - 1e-7, y + 1 - 1e-7, z + 1 - 1e-7, tmp);
                    }
                    w.boxes[i] = tmp.isEmpty() ? Collections.emptyList() : new ArrayList<>(tmp);
                    w.slip[i] = src.slipperiness(x, y, z);
                    w.speed[i] = src.speedFactor(x, y, z);
                    w.jump[i] = src.jumpFactor(x, y, z);
                    w.flags[i] = (byte) ((src.climbable(x, y, z) ? 2 : 0) | (src.bouncy(x, y, z) ? 4 : 0) | (src.sticky(x, y, z) ? 8 : 0));
                }
            }
        }
        return w;
    }

    /** The cells a player at (x,y,z) can reach within {@code margin} blocks, standing box included. */
    public static SnapshotWorld around(PlayerSim.World src, double x, double y, double z, double margin) {
        return capture(src, PlayerSim.floor(x - 0.3 - margin), PlayerSim.floor(y - 1 - margin), PlayerSim.floor(z - 0.3 - margin),
                PlayerSim.floor(x + 0.3 + margin), PlayerSim.floor(y + 1.8 + margin), PlayerSim.floor(z + 0.3 + margin));
    }

    private int index(int x, int y, int z) {
        return ((x - x0) * ny + (y - y0)) * nz + (z - z0);
    }

    private int at(int x, int y, int z) {
        if (x < x0 || y < y0 || z < z0 || x >= x0 + nx || y >= y0 + ny || z >= z0 + nz) return -1;
        return index(x, y, z);
    }

    @Override
    public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
        for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
            for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) {
                for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                    int i = at(x, y, z);
                    if (i >= 0) out.addAll(boxes[i]);
                }
            }
        }
    }

    @Override
    public boolean cellBoxes(int x, int y, int z, List<double[]> out) {
        int i = at(x, y, z);
        if (i >= 0) out.addAll(boxes[i]);
        return true;
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 0.6f : slip[i];
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 1 : speed[i];
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        int i = at(x, y, z);
        return i < 0 ? 1 : jump[i];
    }

    // Ostinato: 1.21.4's PlayerSim has no water; the bit stays clear
    public boolean water(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[i] & 1) != 0;
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[i] & 2) != 0;
    }

    @Override
    public boolean bouncy(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[i] & 4) != 0;
    }

    @Override
    public boolean sticky(int x, int y, int z) {
        int i = at(x, y, z);
        return i >= 0 && (flags[i] & 8) != 0;
    }
}
