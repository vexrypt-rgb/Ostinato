package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.channel.CenterRequest;
import org.junit.Test;

import javax.swing.JComponent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Ostinato: selecting bots on the war-room map and ordering them from the inspector. */
public class SelectViewTest {

    @Test public void clickShiftClickAndAShiftDragSelectMarkers() {
        UiHarness ui = map();
        MapView map = map(ui);
        JComponent canvas = canvas(ui);
        press(canvas, at(map, "Azalea"), MouseEvent.BUTTON1, 0);
        assertEquals(List.of("Azalea"), map.selected());
        press(canvas, at(map, "Copper"), MouseEvent.BUTTON1, 0);
        assertEquals(List.of("Copper"), map.selected());
        press(canvas, at(map, "Azalea"), MouseEvent.BUTTON1, InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("Copper", "Azalea"), map.selected());
        int[] spelunker = at(map, "Spelunker");
        dragBox(canvas, spelunker[0] - 8, spelunker[1] - 8, spelunker[0] + 8, spelunker[1] + 8, true);
        assertEquals(List.of("Copper", "Azalea", "Spelunker"), map.selected());
        assertTrue(ui.text("select:Spelunker:position").contains("-31, 40, 22"));
        assertTrue(ui.text("select:Azalea:task").contains("Azalea") || ui.text("select:Azalea:position").contains("overworld"));
        ui.refresh();
        assertEquals(List.of("Copper", "Azalea", "Spelunker"), map.selected());
    }

    @Test public void rightDragReplacesTheSelectionAndEscClearsIt() {
        UiHarness ui = map();
        MapView map = map(ui);
        JComponent canvas = canvas(ui);
        press(canvas, at(map, "Copper"), MouseEvent.BUTTON1, 0);
        int[] azalea = at(map, "Azalea");
        dragBox(canvas, azalea[0] - 8, azalea[1] - 8, azalea[0] + 8, azalea[1] + 8, false);
        assertEquals(List.of("Azalea"), map.selected());
        assertFalse(ui.find("map:menu").isVisible());
        key(canvas, KeyEvent.VK_ESCAPE, 0);
        assertTrue(map.selected().isEmpty());
        assertEquals("no bots selected", ui.text("select:empty"));
    }

    @Test public void groupsStoreAndRecallOnlyAfterTheMapIsClicked() {
        UiHarness ui = map();
        MapView map = map(ui);
        JComponent canvas = canvas(ui);
        map.groups().store(1, List.of("Azalea", "Spelunker"));
        key(canvas, KeyEvent.VK_1, 0);
        assertTrue("a group key before a click does nothing", map.selected().isEmpty());
        press(canvas, at(map, "Copper"), MouseEvent.BUTTON1, 0);
        key(canvas, KeyEvent.VK_1, 0);
        assertEquals(List.of("Azalea", "Spelunker"), map.selected());
        key(canvas, KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK);
        assertEquals(List.of("Azalea", "Spelunker"), map.groups().recall(2));
        key(canvas, KeyEvent.VK_ESCAPE, 0);
        assertTrue(map.selected().isEmpty());
        key(canvas, KeyEvent.VK_NUMPAD2, 0);
        assertEquals(List.of("Azalea", "Spelunker"), map.selected());
        press(canvas, at(map, "Copper"), MouseEvent.BUTTON1, 0);
        key(canvas, KeyEvent.VK_3, InputEvent.CTRL_DOWN_MASK);
        assertEquals(List.of("Copper"), map.groups().recall(3));
    }

    @Test public void theInspectorSendsOneRequestPerSelectedBot() throws Exception {
        UiHarness ui = map();
        MapView map = map(ui);
        JComponent canvas = canvas(ui);
        press(canvas, at(map, "Azalea"), MouseEvent.BUTTON1, 0);
        press(canvas, at(map, "Spelunker"), MouseEvent.BUTTON1, InputEvent.SHIFT_DOWN_MASK);
        ui.type("select:Azalea:field:goto:target", "100 64 200");
        assertEquals("#swarm send Azalea goto 100 64 200", ui.text("select:Azalea:preview"));
        ui.click("select:Azalea:button:goto");
        assertEquals(1, ui.sent().size());
        assertEquals("Azalea", ui.sent().get(0).bot());
        assertEquals("goto", ui.sent().get(0).formId());
        ui.click("select:Azalea:button:mine");
        assertEquals(1, ui.sent().size());
        assertTrue(ui.text("select:Azalea:result").startsWith("not sent:"));
        ui.click("select:Azalea:button:cancel"); // nothing running: not sent
        assertEquals(1, ui.sent().size());
        assertEquals("not sent: no running command to cancel", ui.text("select:Azalea:result"));
        ui.click("select:Spelunker:button:cancel");
        ui.click("select:Azalea:button:goto"); // sent again, so the request counts below stay as they were
        assertEquals(3, ui.sent().size());
        assertEquals("cancel", ui.sent().get(1).formId());
        assertEquals("Spelunker", ui.sent().get(1).bot());
        assertEquals("cmd-0", ui.sent().get(1).values().get("id"));
        assertEquals(CenterRequest.Op.PRESS, ui.sent().get(1).op());
        ui.click("select:Azalea:button:despawn");
        assertEquals(3, ui.sent().size());
        assertEquals("Confirm despawn", ui.text("select:Azalea:button:despawn"));
        ui.click("select:Azalea:button:despawn");
        assertEquals(4, ui.sent().size());
        assertEquals("Azalea", ui.sent().get(3).values().get("name"));
        ui.type("select:Spelunker:field:mine:block", "iron_ore");
        ui.refresh();
        assertEquals("iron_ore", ((javax.swing.text.JTextComponent) ui.find("select:Spelunker:field:mine:block")).getText());
        ui.screenshot("select-dark");
        assertTrue(ui.text("select:Spelunker:inventory").contains("free") || ui.text("select:Spelunker:inventory").contains("inventory"));
    }

    @Test public void groupsRoundTripThroughThePrefsFile() throws Exception {
        Path dir = Files.createTempDirectory("p3-select");
        try {
            UiHarness ui = map();
            ui.shell.setPrefsDir(dir);
            JComponent canvas = canvas(ui);
            press(canvas, at(map(ui), "Azalea"), MouseEvent.BUTTON1, 0);
            key(canvas, KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK);
            assertTrue(Files.isRegularFile(MapGroups.file(dir)));
            assertTrue(Files.readString(MapGroups.file(dir)).contains("Azalea"));
            UiHarness again = map();
            again.shell.setPrefsDir(dir);
            again.refresh();
            assertTrue(again.shell.views().stream().anyMatch(v -> v instanceof MapView m && m.groups().recall(1).equals(List.of("Azalea"))));
            JComponent canvas2 = canvas(again);
            press(canvas2, at(map(again), "Copper"), MouseEvent.BUTTON1, 0);
            key(canvas2, KeyEvent.VK_1, 0);
            assertEquals(List.of("Azalea"), map(again).selected());
            assertTrue(again.text("select:Azalea:position").contains("40, 64, -13"));
        } finally {
            delete(dir);
        }
    }

    @Test public void anotherDimensionAndAnEmptySwarmStayOut() {
        UiHarness ui = map();
        ui.swarm.bot("Oak").dim('n').at(40.5, 64, -12.5);
        ui.refresh();
        MapView map = map(ui);
        assertNull(map.markerScreen("Oak"));
        int[] azalea = at(map, "Azalea");
        dragBox(canvas(ui), azalea[0] - 6, azalea[1] - 6, azalea[0] + 6, azalea[1] + 6, false);
        assertEquals(List.of("Azalea"), map.selected());
        assertFalse(map.selected().contains("Oak"));
        UiHarness one = UiHarness.many(1, CenterTheme.DEFAULT_DARK);
        one.shell.show(CenterLayout.MAP);
        one.refresh();
        press(canvas(one), at(map(one), "Copper"), MouseEvent.BUTTON1, 0);
        assertEquals(List.of("Copper"), map(one).selected());
        assertTrue(one.has("select:Copper:button:cancel"));
        UiHarness empty = UiHarness.many(0, CenterTheme.DEFAULT_DARK);
        empty.shell.show(CenterLayout.MAP);
        empty.refresh();
        assertEquals("no bots selected", empty.text("select:empty"));
        assertEquals("0 bots", empty.text("map:bots"));
    }

    private static UiHarness map() {
        UiHarness ui = UiHarness.threeBots();
        ui.shell.show(CenterLayout.MAP);
        return ui.refresh();
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

    private static int[] at(MapView map, String bot) {
        double[] p = map.markerScreen(bot);
        return new int[]{(int) Math.round(p[0]), (int) Math.round(p[1])};
    }

    private static void press(JComponent c, int[] at, int button, int mods) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), mods,
                at[0], at[1], 1, false, button));
    }

    /** {@code shift} draws with the left button and adds; otherwise the right button replaces. */
    private static void dragBox(JComponent c, int x1, int y1, int x2, int y2, boolean shift) {
        int mods = shift ? InputEvent.SHIFT_DOWN_MASK : 0;
        int button = shift ? MouseEvent.BUTTON1 : MouseEvent.BUTTON3;
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), mods, x1, y1, 1, false, button));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), mods, x2, y2, 0, false, button));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), mods, x2, y2, 1, false, button));
    }

    private static void key(JComponent canvas, int code, int mods) {
        KeyEvent e = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods, code, KeyEvent.CHAR_UNDEFINED);
        canvas.dispatchEvent(e);
        for (KeyListener listener : canvas.getKeyListeners()) {
            listener.keyPressed(e);
        }
    }

    private static void delete(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        }
    }
}
