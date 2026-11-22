package baritone.swarm.center.app;

import baritone.swarm.center.feed.TerrainFeed;

import java.util.Collection;

/**
 * Ostinato: Phase 3 window, which terrain column the map draws. Slices in another dimension are ignored. Where two
 * slices in the same dimension overlap, the one with the later {@code writtenAtMs} wins. An uncached column stays
 * {@link TerrainFeed#UNCACHED}.
 */
public final class MapTerrain {

    private MapTerrain() {}

    public static String block(Collection<TerrainFeed> slices, char dim, int x, int z) {
        if (slices == null || slices.isEmpty()) {
            return TerrainFeed.UNCACHED;
        }
        TerrainFeed best = null;
        String id = TerrainFeed.UNCACHED;
        for (TerrainFeed t : slices) {
            if (t == null || t.dim() != dim || !t.contains(x, z)) {
                continue;
            }
            String at = t.blockAt(x, z);
            if (TerrainFeed.UNCACHED.equals(at)) {
                continue;
            }
            if (best == null || t.writtenAtMs() >= best.writtenAtMs()) {
                best = t;
                id = at;
            }
        }
        return id;
    }
}
