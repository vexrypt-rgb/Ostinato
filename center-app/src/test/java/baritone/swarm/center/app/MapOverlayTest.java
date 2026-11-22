package baritone.swarm.center.app;

import baritone.swarm.center.feed.CoordinatorFeed;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: lease rectangles, label placement and which leases are drawn. */
public class MapOverlayTest {

    @Test public void screenBoxCoversTheInclusiveBlocks() {
        MapCamera camera = new MapCamera();
        camera.size(200, 200);
        camera.setZoom(4);
        camera.setCenter(0, 0);
        MapOverlay.Rect r = MapOverlay.screenBox(camera, new int[]{0, 64, 0, 3, 70, 3});
        assertEquals((int) Math.round(camera.screenX(0)), r.x());
        assertEquals((int) Math.round(camera.screenZ(0)), r.y());
        assertEquals((int) Math.round(camera.screenX(4) - camera.screenX(0)), r.w());
        assertEquals((int) Math.round(camera.screenZ(4) - camera.screenZ(0)), r.h());
        assertEquals(new MapOverlay.Rect(0, 0, 1, 1), MapOverlay.screenBox(camera, null));
    }

    @Test public void labelStaysInsideTheView() {
        int[] below = MapOverlay.labelAt(new MapOverlay.Rect(10, 0, 40, 30), 200, 200, 80, 12);
        assertTrue(below[1] >= 12);
        int[] above = MapOverlay.labelAt(new MapOverlay.Rect(10, 40, 40, 30), 200, 200, 80, 12);
        assertTrue(above[1] < 40);
        int[] clamped = MapOverlay.labelAt(new MapOverlay.Rect(190, 40, 20, 20), 100, 80, 40, 12);
        assertTrue(clamped[0] >= 2 && clamped[0] <= 100 - 2);
        assertTrue(clamped[1] >= 12 && clamped[1] <= 80);
    }

    @Test public void drawnLeasesSkipKeysAndOtherDimensions() {
        CoordinatorFeed feed = feed();
        List<MapOverlay.LeaseMark> drawn = MapOverlay.leases(feed, 'o');
        assertEquals(4, drawn.size());
        assertTrue(drawn.stream().anyMatch(l -> "region".equals(l.type()) && l.live() && l.label().contains("Azalea · gen 3 · granted")));
        assertTrue(drawn.stream().anyMatch(l -> "vein".equals(l.type()) && l.live()));
        assertTrue(drawn.stream().anyMatch(l -> "chest".equals(l.type())));
        assertTrue(drawn.stream().anyMatch(l -> !l.live() && l.tip().contains("lease-expired")));
        assertTrue(drawn.stream().noneMatch(l -> "key".equals(l.type())));
        assertTrue(MapOverlay.leases(feed, 'n').stream().allMatch(l -> l.dim() == 'n'));
        assertEquals(1, MapOverlay.leases(feed, 'n').size());
        assertTrue(MapOverlay.leases(null, 'o').isEmpty());
        List<String> keys = MapOverlay.keys(feed);
        assertEquals(1, keys.size());
        assertTrue(keys.get(0).contains("Copper · gen 2 · expired · lease-expired"));
        assertTrue(MapOverlay.keys(null).isEmpty());
    }

    @Test public void observationsKeepKindStateAndTooltip() {
        List<MapOverlay.ObsMark> obs = MapOverlay.observations(feed(), 'o');
        assertEquals(5, obs.size());
        assertTrue(obs.stream().anyMatch(o -> "vein".equals(o.kind()) && "confirmed".equals(o.state()) && !o.suspect()));
        assertTrue(obs.stream().anyMatch(o -> "chest".equals(o.kind()) && "stale".equals(o.state())));
        assertTrue(obs.stream().anyMatch(o -> "block".equals(o.kind()) && "gone".equals(o.state())));
        assertTrue(obs.stream().anyMatch(o -> "structure".equals(o.kind()) && o.suspect() && o.tip().contains("spawner")));
        assertTrue(obs.stream().anyMatch(o -> "waypoint".equals(o.kind()) && o.tip().contains("100%")));
        assertFalse(MapOverlay.observations(feed(), 'e').stream().anyMatch(o -> o.box() != null && o.dim() != 'e'));
        assertTrue(MapOverlay.observations(null, 'o').isEmpty());
    }

    static CoordinatorFeed feed() {
        int[] region = {0, 60, 0, 7, 70, 7};
        int[] vein = {20, 40, 20, 23, 42, 23};
        int[] chest = {30, 64, -4, 30, 64, -4};
        int[] ended = {40, 60, 40, 44, 64, 44};
        int[] nether = {1, 40, 1, 2, 42, 2};
        return new CoordinatorFeed("Copper", 1, 1, "active", "world", List.of(
                new CoordinatorFeed.Lease("r1", "r:o:0,60,0:7,70,7", "region", 'o', region, "Azalea", 3, "granted",
                        "mine iron_ore", "running", "unknown", ""),
                new CoordinatorFeed.Lease("v1", "v:o:20,40,20:23,42,23", "vein", 'o', vein, "Spelunker", 1, "offered",
                        "mine coal_ore", "", "", ""),
                new CoordinatorFeed.Lease("c1", "c:o:30,64,-4", "chest", 'o', chest, "Azalea", 4, "granted",
                        "", "", "", ""),
                new CoordinatorFeed.Lease("old", "r:o:40,60,40:44,64,44", "region", 'o', ended, "Azalea", 2, "expired",
                        "mine", "failed", "unknown", "lease-expired"),
                new CoordinatorFeed.Lease("key1", "ore1", "key", '?', null, "Copper", 2, "expired",
                        "mine diamond_ore", "failed", "unknown", "lease-expired"),
                new CoordinatorFeed.Lease("n1", "r:n:1,40,1:2,42,2", "region", 'n', nether, "Oak", 1, "revoking",
                        "", "", "", "")),
                List.of(
                        new CoordinatorFeed.Observation("vein", "vein", 'o', vein, "Copper", 90, "coal_ore.6",
                                "confirmed", false, 12_000),
                        new CoordinatorFeed.Observation("chest", "chest", 'o', chest, "Azalea", 80, "chest",
                                "stale", false, 4_000),
                        new CoordinatorFeed.Observation("block", "block", 'o', new int[]{5, 63, 5, 5, 63, 5},
                                "Spelunker", 70, "spawner", "gone", false, 9_000),
                        new CoordinatorFeed.Observation("struct", "structure", 'o', new int[]{8, 60, 8, 12, 80, 12},
                                "Copper", 60, "spawner", "confirmed", true, 30_000),
                        new CoordinatorFeed.Observation("wp", "waypoint", 'o', new int[]{2, 64, 2, 2, 64, 2},
                                "Copper", 100, "home", "confirmed", false, 1_000)),
                5);
    }
}
