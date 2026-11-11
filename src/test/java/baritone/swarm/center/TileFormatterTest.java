package baritone.swarm.center;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: tile text from execution and observation states. No display. */
public class TileFormatterTest {

    @Test public void verifiedOutcomeAndMissingMemory() {
        BotSnapshot bot = new BotSnapshot("Bot", 14, 18, "mine stone", ExecutionState.SUCCEEDED,
                ObservationState.STALE, "whisper", "", "example.org");
        List<String> lines = TileFormatter.lines(bot);
        assertEquals("Bot", lines.get(0));
        assertEquals("health 14", lines.get(1));
        assertEquals("hunger 18", lines.get(2));
        assertEquals("task mine stone", lines.get(3));
        assertEquals("outcome succeeded (verified)", lines.get(4));
        assertEquals("observation stale", lines.get(5));
        assertEquals("link whisper", lines.get(6));
        assertEquals("memory n/a", lines.get(7));
    }

    @Test public void unknownVitalsStayExplicit() {
        BotSnapshot child = LiveTiles.child("Bot", "localhost", true);
        assertEquals("n/a", TileFormatter.health(child));
        assertEquals("n/a", TileFormatter.hunger(child));
        assertEquals("n/a", TileFormatter.memory(child));
        assertEquals("local", TileFormatter.link(child));
        assertEquals("unknown", TileFormatter.observation(child));
        assertEquals("n/a", TileFormatter.outcome(child));
        assertEquals("process alive", TileFormatter.task(child));
        BotSnapshot dead = LiveTiles.child("Bot", "localhost", false);
        assertEquals("process dead", TileFormatter.task(dead));
        assertEquals("canceled (confirmed)", new BotSnapshot("Bot", -1, -1, "", ExecutionState.CANCELED,
                ObservationState.CONFIRMED, "local", "12", "").execution().label());
    }

    @Test public void composeKeepsTheLocalPlayerAndTheServerLabel() {
        BotSnapshot local = LiveTiles.localPlayer("Steve", 20, 20, "");
        List<BotSnapshot> tiles = LiveTiles.compose(local, List.of(LiveTiles.child("Bot", "127.0.0.1:25565", true)));
        assertEquals(List.of("Steve", "Bot"), tiles.stream().map(BotSnapshot::name).toList());
        assertEquals("127.0.0.1:25565", LiveTiles.serverLabel("", tiles));
        assertEquals("example.org", LiveTiles.serverLabel("example.org", tiles));
        assertEquals("no server", LiveTiles.serverLabel("", List.of()));
        assertTrue(TileFormatter.lines(local).contains("link local"));
    }
}
