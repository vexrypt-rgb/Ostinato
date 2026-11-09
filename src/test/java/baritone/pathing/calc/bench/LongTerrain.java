/*
 * Ostinato 2.0 Phase 07: synthetic worlds for the long-distance corpus. New file, replaces no Baritone code.
 */
package baritone.pathing.calc.bench;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Ostinato 2.0 Phase 07: the terrain families of the long-distance corpus. Five reuse the {@link Terrain} generators
 * unchanged (at any world size); FOREST, MOUNTAINS and MAZE are new. All are seeded and synthetic, not real worlds.
 */
public enum LongTerrain {
    OPEN(Terrain.FLAT), FOREST(null), MOUNTAINS(null), CAVES(Terrain.CAVES), WATER(Terrain.WATER), MIXED(Terrain.HILLS), MAZE(null), BREAK_PLACE(Terrain.BREAK_PLACE);

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

    /** The PathBench terrain this family reuses, or null for one built here. */
    public final Terrain base;

    LongTerrain(Terrain base) {
        this.base = base;
    }

    public boolean underground() {
        return this == CAVES;
    }

    public SyntheticWorld build(int sizeX, int sizeZ, long seed) {
        if (base != null) {
            return base.build(sizeX, sizeZ, seed);
        }
        SyntheticWorld w = new SyntheticWorld(sizeX, sizeZ);
        Random r = new Random(seed * 31 + 100 + ordinal());
        switch (this) {
            case FOREST -> {
                // gentle ground, scattered stone boulders and dense trees: many small irregular obstacles
                double[][] h = Terrain.noise(r, sizeX, sizeZ, 32, 4);
                Terrain.fill(w, sizeX, sizeZ, (x, z) -> Terrain.GROUND - 1 + (int) h[x][z]);
                int n = (int) (sizeX * sizeZ * 0.0015);
                for (int i = 0; i < n; i++) {
                    int x = 3 + r.nextInt(sizeX - 6), z = 3 + r.nextInt(sizeZ - 6);
                    double rad = 1.5 + r.nextDouble() * 2;
                    int y = w.top(x, z);
                    int ri = (int) Math.ceil(rad);
                    for (int dx = -ri; dx <= ri; dx++) for (int dy = 0; dy <= ri; dy++) for (int dz = -ri; dz <= ri; dz++)
                        if (dx * dx + dy * dy + dz * dz <= rad * rad) w.set(x + dx, y + dy, z + dz, STONE);
                }
                Terrain.trees(w, r, sizeX, sizeZ, 0.05);
            }
            case MOUNTAINS -> {
                // broad high ridges with rough detail: long climbs, cliffs of one to three blocks, a few trees
                double[][] big = Terrain.noise(r, sizeX, sizeZ, 96, 34);
                double[][] small = Terrain.noise(r, sizeX, sizeZ, 12, 3);
                Terrain.fill(w, sizeX, sizeZ, (x, z) -> Terrain.GROUND - 12 + (int) (big[x][z] + small[x][z]));
                Terrain.trees(w, r, sizeX, sizeZ, 0.004);
            }
            case MAZE -> {
                // flat ground crossed by full-width, full-height bedrock walls (no way over, under or through) with one
                // to three narrow gaps each at random places: long detours with several alternatives per wall
                Terrain.fill(w, sizeX, sizeZ, (x, z) -> Terrain.GROUND);
                Terrain.trees(w, r, sizeX, sizeZ, 0.002);
                for (int x = 40 + r.nextInt(20); x < sizeX - 20; x += 48 + r.nextInt(25)) {
                    boolean[] open = new boolean[sizeZ];
                    int gaps = 1 + r.nextInt(3);
                    for (int g = 0; g < gaps; g++) {
                        int width = 1 + r.nextInt(3);
                        int z0 = 2 + r.nextInt(sizeZ - 4 - width);
                        for (int z = z0; z < z0 + width; z++) open[z] = true;
                    }
                    for (int z = 0; z < sizeZ; z++) {
                        if (open[z]) continue;
                        for (int y = 1; y < SyntheticWorld.HEIGHT; y++) w.set(x, y, z, BEDROCK);
                    }
                }
            }
            default -> throw new IllegalStateException(name());
        }
        return w;
    }
}
