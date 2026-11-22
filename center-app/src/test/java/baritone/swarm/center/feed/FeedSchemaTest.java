package baritone.swarm.center.feed;

import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.channel.HostInfo;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: read API v1 schema. Every feed kind round-trips; the golden bot feed pins the field names (a rename here
 * breaks every window in the field, which is exactly what v1 promises not to do); other versions are refused with a
 * reason; unknown keys are ignored.
 */
public class FeedSchemaTest {

    static BotFeed sample() {
        return new BotFeed("Azalea", 1_000, 7, "localhost:25565", "headless", false, false,
                new BotFeed.Pos('o', 10.5, 64, -3.25, 90f), 18, 20, 15, false, "Goto",
                new BotFeed.Route("pathing", "GoalBlock{x=100,y=64,z=200}", new int[]{100, 64, 200}, 120.5, 3, 40,
                        List.of(new int[]{11, 64, -3}, new int[]{12, 64, -3})),
                new BotFeed.Inventory(20, 36, 5, List.of(new BotFeed.Item("cobblestone", 128)), 34, "iron_pickaxe"),
                List.of(new BotFeed.Execution("c1", "goto", "Copper", "running", "unknown", 1500, "")),
                List.of(new BotFeed.HeldLease("l1", 2, "r:o:0,60,0:15,70,15", "valid", 12000, true)),
                new BotFeed.Perf(12, 200, 3, 800, 2048, 4, 30, 0, 1), new BotFeed.Link(true, 2, ""), 85);
    }

    /** Golden v1 bot feed. Change only by adding keys at the end of an object. */
    static final String GOLDEN = "{\"schema\":\"ostinato.center\",\"v\":1,\"kind\":\"bot\",\"writtenAtMs\":1000,"
            + "\"seq\":7,\"name\":\"Azalea\",\"server\":\"localhost:25565\",\"runMode\":\"headless\","
            + "\"commandHost\":false,\"coordinator\":false,\"pos\":{\"dim\":\"o\",\"x\":10.5,\"y\":64,\"z\":-3.25,"
            + "\"yaw\":90},\"health\":18,\"maxHealth\":20,\"hunger\":15,\"dead\":false,\"process\":\"Goto\","
            + "\"path\":{\"state\":\"pathing\",\"goal\":\"GoalBlock{x=100,y=64,z=200}\",\"goalPos\":[100,64,200],"
            + "\"distance\":120.5,\"position\":3,\"length\":40,\"nodes\":[[11,64,-3],[12,64,-3]]},"
            + "\"inventory\":{\"freeSlots\":20,\"totalSlots\":36,\"food\":5,\"top\":[{\"id\":\"cobblestone\","
            + "\"count\":128}],\"lowestToolPct\":34,\"lowestTool\":\"iron_pickaxe\"},\"executions\":[{\"id\":\"c1\","
            + "\"command\":\"goto\",\"issuer\":\"Copper\",\"execution\":\"running\",\"observation\":\"unknown\","
            + "\"ageMs\":1500,\"endReason\":\"\"}],\"leases\":[{\"id\":\"l1\",\"gen\":2,\"resource\":"
            + "\"r:o:0,60,0:15,70,15\",\"phase\":\"valid\",\"leftMs\":12000,\"active\":true}],\"perf\":{\"pathMs\":12,"
            + "\"tpsX10\":200,\"pingMs\":3,\"ramUsedMb\":800,\"ramMaxMb\":2048,\"chatUsed\":4,\"chatCap\":30,"
            + "\"dropped\":0,\"retried\":1},\"link\":{\"up\":true,\"peersSeen\":2,\"failure\":\"\"},\"gatherUs\":85}";

    @Test public void botFeedMatchesTheGoldenFile() {
        assertEquals(GOLDEN, sample().toJson());
    }

    @Test public void botFeedRoundTrips() {
        BotFeed back = BotFeed.fromJson(sample().toJson());
        assertEquals("Azalea", back.name());
        assertEquals(10, back.pos().blockX());
        assertEquals(-4, back.pos().blockZ());
        assertEquals("pathing", back.path().state());
        assertArrayEquals(new int[]{100, 64, 200}, back.path().goalPos());
        assertEquals(2, back.path().nodes().size());
        assertEquals(128, back.inventory().top().get(0).count());
        assertEquals("running", back.executions().get(0).execution());
        assertEquals("r:o:0,60,0:15,70,15", back.leases().get(0).resource());
        assertEquals(200, back.perf().tpsX10());
        assertTrue(back.link().up());
        assertEquals(sample().toJson(), back.toJson());
    }

    @Test public void unknownKeysAreIgnoredAndMissingOnesDefault() {
        BotFeed b = BotFeed.fromJson("{\"schema\":\"ostinato.center\",\"v\":1,\"kind\":\"bot\",\"name\":\"X\","
                + "\"futureField\":{\"a\":[1,2]},\"writtenAtMs\":5}");
        assertEquals("X", b.name());
        assertNull(b.pos());
        assertEquals(-1, b.health());
        assertEquals("idle", b.process());
        assertEquals(BotFeed.Inventory.UNKNOWN, b.inventory());
        assertFalse(b.link().up());
    }

    @Test public void otherVersionsAndKindsAreRefusedWithAReason() {
        String v2 = GOLDEN.replace("\"v\":1", "\"v\":2");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BotFeed.fromJson(v2));
        assertEquals("feed v2 is newer than this window (v1)", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CoordinatorFeed.fromJson(GOLDEN));
        assertEquals("not an ostinato.center file", CenterFeed.check(Map.of("v", 1L), "bot"));
    }

    @Test public void coordinatorFeedRoundTrips() {
        CoordinatorFeed c = new CoordinatorFeed("Copper", 9, 1, "active", "local/world",
                List.of(new CoordinatorFeed.Lease("L", "r:o:0,60,0:15,70,15", "region", 'o', new int[]{0, 60, 0, 15, 70, 15},
                        "Azalea", 3, "granted", "mine iron_ore", "running", "unknown", "")),
                List.of(new CoordinatorFeed.Observation("v:o:1,2,3:4,5,6", "vein", 'o', new int[]{1, 2, 3, 4, 5, 6},
                        "Spelunker", 90, "iron_ore.12", "confirmed", false, 3000)), 41);
        CoordinatorFeed back = CoordinatorFeed.fromJson(c.toJson());
        assertEquals("active", back.mode());
        assertTrue(back.leases().get(0).live());
        assertArrayEquals(new int[]{0, 60, 0, 15, 70, 15}, back.leases().get(0).box());
        assertEquals('o', back.observations().get(0).dim());
        assertEquals(41, back.observationTotal());
        assertEquals(c.toJson(), back.toJson());
    }

    @Test public void catalogFeedRoundTripsTheRegistryForms() {
        CatalogFeed c = new CatalogFeed("Copper", 1, 1, FakeSwarm.CATALOG);
        CatalogFeed back = CatalogFeed.fromJson(c.toJson());
        assertEquals(FakeSwarm.CATALOG, back.buttons());
        assertEquals(4, back.models(baritone.api.command.FormPlacement.TILE).size());
        assertEquals("mine 16 iron_ore", back.models(baritone.api.command.FormPlacement.TILE).get(1)
                .line(Map.of("count", "16", "block", "iron_ore")));
    }

    @Test public void terrainFeedRoundTripsAndIndexesRowMajor() {
        byte[] blocks = new byte[32 * 16];
        short[] heights = new short[32 * 16];
        java.util.Arrays.fill(heights, TerrainFeed.UNKNOWN_Y);
        int i = (20 - 16) * 32 + (40 - 32); // x=40, z=20 with origin 32,16
        blocks[i] = 1;
        heights[i] = -12;
        TerrainFeed t = new TerrainFeed("Copper", 1, 1, 'o', 32, 16, 32, 16, List.of("?", "stone"), blocks, heights);
        TerrainFeed back = TerrainFeed.fromJson(t.toJson());
        assertEquals("stone", back.blockAt(40, 20));
        assertEquals(-12, back.heightAt(40, 20));
        assertEquals(TerrainFeed.UNCACHED, back.blockAt(41, 20));
        assertEquals(TerrainFeed.UNKNOWN_Y, back.heightAt(0, 0));
        assertFalse(back.contains(64, 20));
    }

    @Test public void fullSizeTerrainStaysWellUnderTheReadCap() {
        int w = (TerrainFeed.RADIUS_CHUNKS * 2 + 1) * 16;
        TerrainFeed t = new TerrainFeed("Copper", 1, 1, 'o', 0, 0, w, w, List.of("?"), new byte[w * w], new short[w * w]);
        assertTrue(t.toJson().length() < CenterFeed.MAX_BYTES / 4);
    }

    @Test public void hostInfoRoundTrips() {
        HostInfo h = HostInfo.fromJson(new HostInfo("Copper", 1, 2, 51234, 999, 5).toJson());
        assertEquals(51234, h.port());
        assertEquals("Copper", h.bot());
    }

    @Test public void namesThatEscapeTheDirectoryAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> CenterFeed.botFile(java.nio.file.Path.of("x"), "../evil"));
        assertThrows(IllegalArgumentException.class, () -> CenterFeed.botFile(java.nio.file.Path.of("x"), ""));
        assertTrue(CenterFeed.safeName("Spelunker"));
    }
}
