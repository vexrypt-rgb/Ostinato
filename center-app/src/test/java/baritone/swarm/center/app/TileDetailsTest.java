package baritone.swarm.center.app;

import baritone.swarm.center.feed.Alert;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: focus-panel and tile-strip wording, including the empty and unknown cases. */
public class TileDetailsTest {

    @Test public void perfStripAndProgress() {
        assertEquals("20.0 tps · 3 ms · 900/2048 MB",
                TileDetails.perfStrip(new BotFeed.Perf(12, 200, 3, 900, 2048, 2, 30, 0, 0)));
        assertEquals("n/a tps · n/a · n/a/n/a MB", TileDetails.perfStrip(BotFeed.Perf.UNKNOWN));
        assertEquals("", TileDetails.progress(BotFeed.Route.IDLE));
        assertEquals("", TileDetails.progress(new BotFeed.Route("pathing", "g", null, 1, 0, 0, List.of())));
        assertEquals("3/40", TileDetails.progress(new BotFeed.Route("pathing", "g", null, 1, 3, 40, List.of())));
    }

    @Test public void pathExecutionsLeasesAndLink() {
        BotFeed.Route path = new BotFeed.Route("pathing", "GoalBlock", new int[]{10, 64, 20}, 15.5, 3, 40,
                List.of(new int[]{1, 64, 2}, new int[]{3, 64, 4}));
        assertEquals("pathing · GoalBlock · 15.5 blocks · 2 nodes · 3/40", TileDetails.path(path));
        assertEquals("idle · no goal · distance n/a · 0 nodes · 0/0", TileDetails.path(null));
        assertEquals("no commands", TileDetails.executions(List.of()));
        assertEquals("goto · Copper · running · unknown · 2 s · arrived",
                TileDetails.execution(new BotFeed.Execution("c", "goto", "Copper", "running", "unknown", 2500, "arrived")));
        assertEquals("no leases", TileDetails.leases(List.of()));
        assertEquals("r:o:0,60,0:4,70,4 · gen 7 · held · 12 s left",
                TileDetails.lease(new BotFeed.HeldLease("l", 7, "r:o:0,60,0:4,70,4", "held", 12_000, true)));
        assertEquals("down · peers 0 · no failure", TileDetails.link(null));
        assertEquals("up · peers 2 · no failure", TileDetails.link(new BotFeed.Link(true, 2, "")));
    }

    @Test public void dimensionAgeGatherAndAlerts() {
        BotFeed world = feed(new BotFeed.Pos('n', 1, 2, 3, 0));
        BotFeed absent = feed(null);
        assertEquals("nether", TileDetails.dimension(world));
        assertEquals("not in a world", TileDetails.dimension(absent));
        SwarmView.BotState fresh = new SwarmView.BotState(world, 0, SwarmView.BotState.freshness(0));
        SwarmView.BotState offline = new SwarmView.BotState(absent, 20_000, SwarmView.BotState.freshness(20_000));
        assertEquals("0 s · fresh", TileDetails.age(fresh));
        assertEquals("20 s · offline", TileDetails.age(offline));
        assertEquals("gather 40 µs", TileDetails.gather(world));
        assertEquals("gather n/a", TileDetails.gather(feed(null, -1)));
        assertEquals("no alerts", TileDetails.alerts(List.of()));
        String badge = TileDetails.alerts(List.of(new Alert("Azalea", Alert.Code.LOW_HEALTH, "health 4")));
        assertTrue(badge.contains("health 4"));
    }

    private static BotFeed feed(BotFeed.Pos pos) {
        return feed(pos, 40);
    }

    private static BotFeed feed(BotFeed.Pos pos, long gather) {
        return new BotFeed("Azalea", 0, 1, "", "headless", false, false, pos, 20, 20, 20, false, "idle",
                BotFeed.Route.IDLE, BotFeed.Inventory.UNKNOWN, List.of(), List.of(), BotFeed.Perf.UNKNOWN,
                BotFeed.Link.DOWN, gather);
    }
}
