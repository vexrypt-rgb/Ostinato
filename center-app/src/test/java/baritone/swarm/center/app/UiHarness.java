package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.fake.DemoSwarm;
import baritone.swarm.center.app.fake.FakeCommandPort;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.feed.SwarmSource;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: Phase 3 UI test harness. The whole window (a {@link CenterShell}) over a {@link FakeSwarm} and a
 * {@link FakeCommandPort}, headless, with commands sent and answered on the calling thread so every assertion follows
 * its click. Every slice's UI test uses this:
 *
 * <pre>
 *   UiHarness ui = UiHarness.threeBots();
 *   ui.swarm.bot("Azalea").health(4);
 *   ui.refresh();
 *   assertEquals("low health", ui.text("tile:Azalea:status"));
 *   ui.type("tile:Azalea:field:goto:target", "1 2 3");
 *   ui.click("tile:Azalea:button:goto");
 *   assertEquals("goto", ui.sent().get(0).formId());
 *   ui.screenshot("my-slice");   // build/screens/my-slice.png, for the review
 * </pre>
 */
public final class UiHarness {

    public static final int WIDTH = 1280, HEIGHT = 860;

    /** The clock of the fake swarm and of the commands. */
    public final AtomicLong now;
    public final FakeSwarm swarm;
    /** What {@link #refresh} reads: the fake swarm itself, or a {@link DemoSwarm} that moves it. */
    public final SwarmSource source;
    public final FakeCommandPort port;
    public final Commands commands;
    public final CenterShell shell;

    public UiHarness(AtomicLong now, FakeSwarm swarm, CenterTheme theme, CenterLayout layout) {
        this(now, swarm, swarm, theme, layout);
    }

    public UiHarness(AtomicLong now, FakeSwarm swarm, SwarmSource source, CenterTheme theme, CenterLayout layout) {
        this.now = now;
        this.swarm = swarm;
        this.source = source;
        this.port = new FakeCommandPort(swarm.hostName());
        this.commands = new Commands(port, Runnable::run, Runnable::run, now::get);
        this.shell = new CenterShell(new AppStyle(theme), commands, layout);
    }

    /** Copper (host), Azalea and Spelunker, in Default dark, on the wall. */
    public static UiHarness threeBots() {
        AtomicLong now = new AtomicLong(1_000_000);
        return new UiHarness(now, FakeSwarm.threeBots(now::get), CenterTheme.DEFAULT_DARK, CenterLayout.WALL);
    }

    /** {@code n} bots in varied states ({@link FakeSwarm#many}) in this theme. */
    public static UiHarness many(int n, CenterTheme theme) {
        AtomicLong now = new AtomicLong(1_000_000);
        return new UiHarness(now, FakeSwarm.many(n, now::get), theme, CenterLayout.WALL);
    }

    /** The moving demo swarm ({@link DemoSwarm}: paths, leases, observations, terrain) in this theme and layout. */
    public static UiHarness demo(int n, CenterTheme theme, CenterLayout layout) {
        AtomicLong now = new AtomicLong(1_000_000);
        DemoSwarm d = new DemoSwarm(n, now::get);
        return new UiHarness(now, d.swarm(), d, theme, layout);
    }

    public UiHarness advance(long ms) {
        now.addAndGet(ms);
        return this;
    }

    /** One window refresh with the swarm as it is now, laid out at {@link #WIDTH} x {@link #HEIGHT}. */
    public UiHarness refresh() {
        shell.update(source.snapshot());
        shell.component().setSize(WIDTH, HEIGHT);
        layout(shell.component());
        layout(shell.component()); // Ostinato: the wall picks its column count from the first pass
        return this;
    }

    public Component find(String name) {
        return CenterApp.find(shell.component(), name);
    }

    public boolean has(String name) {
        return find(name) != null;
    }

    public String text(String name) {
        Component c = find(name);
        assertNotNull("no component " + name, c);
        if (c instanceof javax.swing.text.JTextComponent area) {
            return area.getText();
        }
        return c instanceof JLabel l ? l.getText() : ((AbstractButton) c).getText();
    }

    public UiHarness type(String field, String text) {
        Component c = find(field);
        assertNotNull("no field " + field, c);
        ((JTextComponent) c).setText(text);
        return this;
    }

    public UiHarness click(String button) {
        Component c = find(button);
        assertNotNull("no button " + button, c);
        ((AbstractButton) c).doClick();
        return this;
    }

    public List<CenterRequest> sent() {
        return port.sent();
    }

    public BufferedImage render() {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        shell.component().paint(img.getGraphics());
        return img;
    }

    /** Renders to {@code build/screens/<name>.png} (kept for the reviewer) and returns the image. */
    public BufferedImage screenshot(String name) throws IOException {
        File out = new File("build/screens");
        out.mkdirs();
        BufferedImage img = render();
        assertTrue(ImageIO.write(img, "png", new File(out, name + ".png")));
        return img;
    }

    static void layout(Component c) {
        // Ostinato: off the Swing thread, setText defers its revalidate, so layouts keep stale cached sizes.
        invalidateAll(c);
        doLayoutAll(c);
    }

    private static void invalidateAll(Component c) {
        c.invalidate();
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                invalidateAll(child);
            }
        }
    }

    private static void doLayoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                doLayoutAll(child);
            }
        }
    }
}
