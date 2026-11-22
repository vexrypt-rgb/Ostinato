package baritone.swarm.center.app;

import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.feed.BotFeed;
import org.junit.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: window placement, theme names and tile wording. */
public class CenterAppTest {

    @Test public void placementKeepsAPointOnAScreenElseUsesTheSecondScreen() {
        List<Rectangle> two = List.of(new Rectangle(0, 0, 1920, 1080), new Rectangle(1920, 0, 1920, 1080));
        assertArrayEquals(new int[]{2600, 40}, CenterApp.place(two, "2600,40"));
        assertArrayEquals(new int[]{1960, 40}, CenterApp.place(two, "9000,40"));
        assertArrayEquals(new int[]{2600, 40}, CenterApp.place(two, "junk"));
        assertArrayEquals(new int[]{9000, 40}, CenterApp.place(List.of(new Rectangle(0, 0, 1920, 1080)), "9000,40"));
    }

    @Test public void themeNamesMatchTheChatCommand() {
        assertEquals(CenterTheme.LIGHT, CenterApp.theme("light"));
        assertEquals(CenterTheme.HIGH_CONTRAST, CenterApp.theme("high-contrast"));
        assertEquals(CenterTheme.COLORBLIND_SAFE, CenterApp.theme("colorblind"));
        assertEquals(CenterTheme.DEFAULT_DARK, CenterApp.theme(null));
    }

    @Test public void tileWording() {
        BotFeed f = FakeSwarm.threeBots(() -> 0).snapshot().bot("Spelunker").feed();
        assertEquals("Mine · mine running", TileText.task(f));
        assertEquals("inventory n/a", TileText.inventory(BotFeed.Inventory.UNKNOWN));
        assertEquals("nether", TileText.dim('n'));
    }
}
