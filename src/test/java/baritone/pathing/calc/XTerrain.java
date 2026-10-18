/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Shared file, identical in every fork.
 */
package baritone.pathing.calc;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/** Three-way bench: the Ostinato PathBench terrains, verbatim, over each fork's {@link XVoxels}. */
public enum XTerrain {
    FLAT, HILLS, CAVES, WATER, BREAK_PLACE;

    public static final int GROUND = 64;
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState WATER_S = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

    public XVoxels build(int sizeX, int sizeZ, long seed) {
        XVoxels w = new XVoxels(sizeX, sizeZ);
        Random r = new Random(seed * 31 + ordinal());
        switch (this) {
            case FLAT -> {
                fill(w, sizeX, sizeZ, (x, z) -> GROUND);
                trees(w, r, sizeX, sizeZ, 0.004);
            }
            case HILLS -> {
                double[][] h = noise(r, sizeX, sizeZ, 24, 14);
                fill(w, sizeX, sizeZ, (x, z) -> GROUND - 6 + (int) h[x][z]);
                trees(w, r, sizeX, sizeZ, 0.01);
            }
            case WATER -> {
                double[][] h = noise(r, sizeX, sizeZ, 40, 10);
                fill(w, sizeX, sizeZ, (x, z) -> GROUND - 6 + (int) h[x][z]);
                // sea level a little above the mean: lakes, channels and islands
                for (int x = 0; x < sizeX; x++) for (int z = 0; z < sizeZ; z++) for (int y = w.top(x, z) + 1; y <= GROUND - 1; y++) w.set(x, y, z, WATER_S);
            }
            case CAVES -> {
                // solid stone from 1 to GROUND+20, a sealed roof, carved worm tunnels; routes run inside
                for (int x = 0; x < sizeX; x++) for (int z = 0; z < sizeZ; z++) {
                    w.set(x, 0, z, BEDROCK);
                    for (int y = 1; y <= GROUND + 20; y++) w.set(x, y, z, STONE);
                }
                int worms = Math.max(8, sizeX * sizeZ / 1500);
                for (int i = 0; i < worms; i++) worm(w, r, r.nextInt(sizeX), GROUND - 10 + r.nextInt(20), r.nextInt(sizeZ), 120 + r.nextInt(200));
                // a guaranteed meandering main tunnel along x so routes exist (but not straight)
                double z = sizeZ / 2.0, y = GROUND;
                for (int x = 0; x < sizeX; x++) {
                    z += (r.nextDouble() - 0.5) * 1.6;
                    y += (r.nextDouble() - 0.5) * 0.8;
                    z = Math.max(4, Math.min(sizeZ - 5, z));
                    y = Math.max(GROUND - 12, Math.min(GROUND + 10, y));
                    carve(w, x, (int) y, (int) z, 1.6);
                }
            }
            case BREAK_PLACE -> {
                fill(w, sizeX, sizeZ, (x, z) -> GROUND);
                // full-width stone walls (must break through or pillar/ascend over with blocks) and 4-wide ravines (must bridge)
                for (int x = 20; x < sizeX - 10; x += 37) {
                    if ((x / 37) % 2 == 0) {
                        for (int z = 0; z < sizeZ; z++) for (int y = GROUND + 1; y <= GROUND + 6; y++) for (int t = 0; t < 2; t++) w.set(x + t, y, z, STONE);
                    } else {
                        for (int z = 0; z < sizeZ; z++) for (int y = 1; y <= GROUND; y++) for (int t = 0; t < 4; t++) w.set(x + t, y, z, AIR);
                    }
                }
            }
        }
        return w;
    }

    interface H {
        int at(int x, int z);
    }

    private static void fill(XVoxels w, int sx, int sz, H h) {
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {
            int top = Math.max(2, Math.min(XVoxels.HEIGHT - 20, h.at(x, z)));
            w.set(x, 0, z, BEDROCK);
            for (int y = 1; y <= top; y++) w.set(x, y, z, y < top - 3 ? STONE : y < top ? DIRT : GRASS);
        }
    }

    private static void trees(XVoxels w, Random r, int sx, int sz, double density) {
        int n = (int) (sx * sz * density);
        for (int i = 0; i < n; i++) {
            int x = 2 + r.nextInt(sx - 4), z = 2 + r.nextInt(sz - 4);
            int y = w.top(x, z);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 5; dy++)
                if (Math.abs(dx) + Math.abs(dz) < 4) w.set(x + dx, y + dy, z + dz, LEAVES);
            for (int dy = 1; dy <= 4; dy++) w.set(x, y + dy, z, LOG);
        }
    }

    private static double[][] noise(Random r, int sx, int sz, int cell, double amp) {
        int gx = sx / cell + 2, gz = sz / cell + 2;
        double[][] g = new double[gx][gz];
        for (int i = 0; i < gx; i++) for (int j = 0; j < gz; j++) g[i][j] = r.nextDouble();
        double[][] h = new double[sx][sz];
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {
            double fx = (double) x / cell, fz = (double) z / cell;
            int ix = (int) fx, iz = (int) fz;
            double tx = smooth(fx - ix), tz = smooth(fz - iz);
            double a = g[ix][iz] + (g[ix + 1][iz] - g[ix][iz]) * tx;
            double b = g[ix][iz + 1] + (g[ix + 1][iz + 1] - g[ix][iz + 1]) * tx;
            h[x][z] = (a + (b - a) * tz) * amp * 2 - amp / 2;
        }
        return h;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static void worm(XVoxels w, Random r, double x, double y, double z, int len) {
        double yaw = r.nextDouble() * Math.PI * 2, pitch = 0;
        for (int i = 0; i < len; i++) {
            yaw += (r.nextDouble() - 0.5) * 0.5;
            pitch = Math.max(-0.4, Math.min(0.4, pitch + (r.nextDouble() - 0.5) * 0.2));
            x += Math.cos(yaw);
            z += Math.sin(yaw);
            y += Math.sin(pitch);
            y = Math.max(XTerrain.GROUND - 14, Math.min(XTerrain.GROUND + 14, y));
            carve(w, (int) x, (int) y, (int) z, 1.2 + r.nextDouble() * 1.3);
        }
    }

    private static void carve(XVoxels w, int cx, int cy, int cz, double rad) {
        int ri = (int) Math.ceil(rad);
        for (int dx = -ri; dx <= ri; dx++) for (int dy = -ri; dy <= ri; dy++) for (int dz = -ri; dz <= ri; dz++)
            if (dx * dx + dy * dy + dz * dz <= rad * rad) w.set(cx + dx, cy + dy, cz + dz, AIR);
        w.set(cx, cy - ri - 1, cz, w.real(cx, cy - ri - 1, cz)); // keep floor
    }
}
