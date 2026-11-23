package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import org.junit.Test;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ostinato: a tile or inspector Cancel names the command it cancels. Live check p3w run 5 found that a Cancel with no
 * id went out as {@code swarm send <bot> cancel}, which the bot rejects ({@code bad-cancel-target}) while the window
 * showed "accepted", so nothing stopped.
 */
public class CancelTargetTest {

    private static BotFeed.Execution exec(String id, String command, String state, long ageMs) {
        return new BotFeed.Execution(id, command, "Copper", state, "confirmed", ageMs, "");
    }

    @Test public void theTargetIsTheNewestCommandStillRunning() {
        assertEquals("b", TileText.cancelTarget(List.of(exec("a", "goto", "running", 9000), exec("b", "mine", "running", 2000),
                exec("c", "goto", "succeeded", 100))));
        assertEquals("a", TileText.cancelTarget(List.of(exec("a", "goto", "accepted", 500), exec("x", "cancel", "running", 100))));
        assertNull(TileText.cancelTarget(List.of(exec("c", "goto", "succeeded", 100), exec("d", "goto", "canceled", 50))));
        assertNull(TileText.cancelTarget(List.of()));
        assertNull(TileText.cancelTarget(null));
    }

    @Test public void aCancelWithoutAnIdIsNotARoute() {
        UiHarness ui = UiHarness.threeBots().refresh();
        ButtonModel cancel = form(ui, "cancel");
        try {
            TileRoute.route(cancel, "Azalea", Map.of());
            fail("a blank cancel was routed");
        } catch (IllegalArgumentException e) {
            assertEquals("no running command to cancel", e.getMessage());
        }
        assertEquals("swarm cancel 6c6e", TileRoute.route(cancel, "Azalea", Map.of("id", "6c6e")));
        assertTrue(!Commands.ready(cancel, Map.of(), "Azalea"));
    }

    @Test public void theTileCancelsTheRunningCommandOnTheHost() {
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.click("tile:Spelunker:button:cancel");
        assertEquals(1, ui.sent().size());
        assertEquals("cmd-0", ui.sent().get(0).values().get("id"));
        assertEquals("● Copper: #swarm cancel cmd-0 - accepted", ui.text("tile:Spelunker:result"));
    }

    @Test public void aTileWithNothingRunningSendsNothing() {
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.click("tile:Azalea:button:cancel");
        assertTrue(ui.sent().isEmpty());
        assertEquals("not sent: no running command to cancel", ui.text("tile:Azalea:result"));
    }

    @Test public void theInspectorCancelsEachSelectedBotsRunningCommand() {
        AtomicLong now = new AtomicLong(1_000_000);
        FakeSwarm swarm = FakeSwarm.threeBots(now::get);
        swarm.bot("Azalea").execution("goto", "running", 1_500);
        UiHarness ui = new UiHarness(now, swarm, CenterTheme.DEFAULT_DARK, CenterLayout.MAP).refresh();
        assertEquals("selected=[Azalea, Spelunker]", CenterApp.mapAuto(ui.shell, "select:Azalea+Spelunker"));
        ui.refresh();
        ui.click("select:Azalea:button:cancel");
        ui.click("select:Spelunker:button:cancel");
        assertEquals(2, ui.sent().size());
        assertEquals("Azalea", ui.sent().get(0).bot());
        assertEquals("cmd-0", ui.sent().get(0).values().get("id"));
        assertEquals("Spelunker", ui.sent().get(1).bot());
        assertEquals("cmd-0", ui.sent().get(1).values().get("id"));
        assertTrue(ui.text("select:Azalea:result").contains("#swarm cancel cmd-0 - accepted"));
    }

    private static ButtonModel form(UiHarness ui, String id) {
        for (ButtonModel b : ui.swarm.snapshot().catalog().models(baritone.api.command.FormPlacement.TILE)) {
            if (id.equals(b.form().id())) {
                return b;
            }
        }
        throw new AssertionError("no form " + id);
    }
}
