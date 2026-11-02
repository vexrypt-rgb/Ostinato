/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import java.util.IdentityHashMap;
import java.util.Map;

/** Ostinato: hand-built terrains for the engine's tests (no Minecraft needed). */
final class PpeWorlds {
    private PpeWorlds() {}

    interface Cells {
        BlockInfo at(int x, int y, int z);
    }

    static final BlockInfo STONE = new BlockInfo.Builder("stone").boxes(0, 0, 0, 1, 1, 1).resistance(6).build();
    static final BlockInfo OBSIDIAN = new BlockInfo.Builder("obsidian").boxes(0, 0, 0, 1, 1, 1).resistance(1200).build();
    static final BlockInfo HAY = new BlockInfo.Builder("hay_block").boxes(0, 0, 0, 1, 1, 1).resistance(0.5f).fall(BlockInfo.FALL_HAY).build();
    static final BlockInfo SLIME = new BlockInfo.Builder("slime_block").boxes(0, 0, 0, 1, 1, 1).friction(0.8f).bounce(1.0f)
            .fall(BlockInfo.FALL_SLIME).effect(BlockInfo.EFFECT_SLIME).build();
    static final BlockInfo TNT = new BlockInfo.Builder("tnt").boxes(0, 0, 0, 1, 1, 1).resistance(0).flags(BlockInfo.TNT).build();
    static final BlockInfo WATER = new BlockInfo.Builder("water").flags(BlockInfo.WATER_BLOCK).fluid(BlockInfo.FLUID_WATER, 0.8888889f, 8, false)
            .resistance(100).build();

    /** Sections [sx0, sx1] x [sy0, sy1] x [sz0, sz1] filled from {@code cells}; build limits -64..320. */
    static Terrain build(int sx0, int sx1, int sy0, int sy1, int sz0, int sz1, Cells cells) {
        Terrain.Builder b = Terrain.builder(-64, 320, 0, (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1));
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sy = sy0; sy <= sy1; sy++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    Map<BlockInfo, Integer> ids = new IdentityHashMap<>();
                    BlockInfo[] palette = new BlockInfo[4096];
                    short[] idx = new short[4096];
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                BlockInfo info = cells.at(sx * 16 + lx, sy * 16 + ly, sz * 16 + lz);
                                Integer id = ids.get(info);
                                if (id == null) {
                                    id = ids.size();
                                    ids.put(info, id);
                                    palette[id] = info;
                                }
                                idx[ly << 8 | lz << 4 | lx] = (short) (int) id;
                            }
                        }
                    }
                    BlockInfo[] p = java.util.Arrays.copyOf(palette, ids.size());
                    b.put(sx, sy, sz, p.length == 1 ? Terrain.Section.uniform(p[0], 0, 0) : new Terrain.Section(p, idx, 0, 0));
                }
            }
        }
        return b.build();
    }

    /** Flat ground: {@code ground} below y=64, air above, around the origin. */
    static Terrain flat(BlockInfo ground) {
        return build(-2, 1, 2, 6, -2, 1, (x, y, z) -> y < 64 ? ground : BlockInfo.AIR_INFO);
    }

    static Terrain flat(Cells extra) {
        return build(-2, 1, 2, 6, -2, 1, (x, y, z) -> {
            BlockInfo e = extra.at(x, y, z);
            return e != null ? e : y < 64 ? STONE : BlockInfo.AIR_INFO;
        });
    }
}
