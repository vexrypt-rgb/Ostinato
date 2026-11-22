package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.SwarmView;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.text.JTextComponent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Ostinato: drawing an area on the war-room map and sending one order per selected bot. */
public class AreaViewTest {

    @Test public void oneSendMinesOneStripPerSelectedBot() throws Exception {
        UiHarness ui = drawn();
        ui.click("area:mode:mine");
        ui.type("area:ore", "iron_ore");
        AreaOrders.Box box = box(ui);
        List<String> lines = AreaOrders.mine(box, List.of("Azalea", "Spelunker"), "iron_ore").lines('o', "iron_ore");
        assertEquals(2, lines.size());
        assertTrue(ui.text("area:preview").contains(lines.get(0)));
        assertTrue(ui.text("area:preview").contains(lines.get(1)));
        assertTrue(ui.sent().isEmpty());
        ui.click("area:send");
        assertEquals(2, ui.sent().size());
        assertEquals(CenterRequest.Op.LINE, ui.sent().get(0).op());
        assertEquals(lines.get(0), ui.sent().get(0).line());
        assertEquals("Azalea", ui.sent().get(0).bot());
        assertEquals(lines.get(1), ui.sent().get(1).line());
        assertEquals("Spelunker", ui.sent().get(1).bot());
        assertTrue(ui.text("area:replies").contains("accepted"));
        assertTrue(ui.text("area:size").contains("x"));
        ui.screenshot("area-dark");
    }

    @Test public void oneSendSpreadsAGotoPerSelectedBot() {
        UiHarness ui = drawn();
        ui.click("area:mode:goto");
        AreaOrders.Box box = box(ui);
        List<AreaOrders.Assign> assigns = AreaOrders.spread(box, List.of(
                at(ui, "Azalea"), at(ui, "Spelunker")));
        assertTrue(ui.text("area:preview").contains(assigns.get(0).target()));
        assertTrue(ui.sent().isEmpty());
        ui.click("area:send");
        assertEquals(assigns.size(), ui.sent().size());
        for (int i = 0; i < assigns.size(); i++) {
            assertEquals(CenterRequest.Op.PRESS, ui.sent().get(i).op());
            assertEquals("goto", ui.sent().get(i).formId());
            assertEquals(assigns.get(i).bot(), ui.sent().get(i).bot());
            assertEquals(assigns.get(i).target(), ui.sent().get(i).values().get("target"));
        }
        assertTrue(ui.text("area:replies").contains("accepted"));
    }

    @Test public void anOversizeBoxAndAHostThatIsNotCoordinatorAreNotSent() {
        UiHarness ui = drawn();
        ui.click("area:mode:mine");
        ui.type("area:ore", "iron_ore");
        ui.type("area:y1", "0").type("area:y2", "80");
        assertTrue(ui.text("area:reason").contains(AreaOrders.EDGE_REASON));
        ui.click("area:send");
        assertTrue(ui.sent().isEmpty());

        AtomicLong now = new AtomicLong(1_000_000);
        FakeSwarm swarm = new FakeSwarm(now::get);
        swarm.bot("Copper").host().at(0.5, 64, 0.5);
        swarm.bot("Azalea").at(10, 64, 10);
        UiHarness quiet = new UiHarness(now, swarm, CenterTheme.DEFAULT_DARK, CenterLayout.MAP).refresh();
        press(canvas(quiet), atScreen(map(quiet), "Azalea"), MouseEvent.BUTTON1, 0);
        quiet.click("area:tool");
        drag(canvas(quiet), 20, 20, 140, 80);
        quiet.click("area:mode:mine");
        quiet.type("area:ore", "diamond_ore");
        assertEquals(AreaOrders.HOST_REASON, quiet.text("area:reason").trim());
        quiet.click("area:send");
        assertTrue(quiet.sent().isEmpty());
        quiet.click("area:mode:goto");
        quiet.click("area:send");
        assertEquals(1, quiet.sent().size());
        assertEquals("goto", quiet.sent().get(0).formId());
        assertEquals("Azalea", quiet.sent().get(0).bot());
        assertFalse(quiet.swarm.bot("Copper").build(now.get(), 1).coordinator());
    }

    private static UiHarness drawn() {
        UiHarness ui = UiHarness.threeBots();
        ui.shell.show(CenterLayout.MAP);
        ui.refresh();
        MapView map = map(ui);
        press(canvas(ui), atScreen(map, "Azalea"), MouseEvent.BUTTON1, 0);
        press(canvas(ui), atScreen(map, "Spelunker"), MouseEvent.BUTTON1, InputEvent.SHIFT_DOWN_MASK);
        ui.click("area:tool");
        map.camera().setZoom(4);
        map.camera().setCenter(200, 200);
        drag(canvas(ui), 24, 24, 180, 100);
        assertNotNull(map.area());
        assertEquals("44", ((JTextComponent) ui.find("area:y1")).getText());
        assertEquals("60", ((JTextComponent) ui.find("area:y2")).getText());
        return ui;
    }

    private static AreaOrders.Box box(UiHarness ui) {
        int[] a = map(ui).area();
        int yLo = Integer.parseInt(((JTextComponent) ui.find("area:y1")).getText().trim());
        int yHi = Integer.parseInt(((JTextComponent) ui.find("area:y2")).getText().trim());
        return new AreaOrders.Box(a[0], yLo, a[1], a[2], yHi, a[3]);
    }

    private static AreaOrders.BotAt at(UiHarness ui, String name) {
        SwarmView.BotState b = ui.swarm.snapshot().bot(name);
        return new AreaOrders.BotAt(name, b.feed().pos().x(), b.feed().pos().y(), b.feed().pos().z());
    }

    private static MapView map(UiHarness ui) {
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof MapView m) {
                return m;
            }
        }
        throw new AssertionError("no map");
    }

    private static JComponent canvas(UiHarness ui) {
        return (JComponent) ui.find("map:canvas");
    }

    private static int[] atScreen(MapView map, String bot) {
        double[] p = map.markerScreen(bot);
        return new int[]{(int) Math.round(p[0]), (int) Math.round(p[1])};
    }

    private static void press(JComponent c, int[] at, int button, int mods) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), mods,
                at[0], at[1], 1, false, button));
    }

    private static void drag(JComponent c, int x1, int y1, int x2, int y2) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x1, y1, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, x2, y2, 0, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x2, y2, 1, false, MouseEvent.BUTTON1));
    }
}
