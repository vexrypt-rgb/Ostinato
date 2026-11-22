package baritone.swarm.center.app;

import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.app.fake.FakeCommandPort;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the window's command path. It sends only catalog forms or registry lines, shows the host's own routing as
 * the equivalent {@code #} line, sends off the Swing thread and delivers the reply back on it.
 */
public class CommandsTest {

    @Test public void previewUsesTheHostsRouting() {
        ButtonModel gotoForm = FakeSwarm.CATALOG.get(0).model();
        ButtonModel mine = FakeSwarm.CATALOG.get(1).model();
        assertEquals("#swarm send Azalea goto 1 2 3", Commands.preview(gotoForm, Map.of("target", "1 2 3"), "Azalea", "Copper"));
        assertEquals("#goto 1 2 3", Commands.preview(gotoForm, Map.of("target", "1 2 3"), "copper", "Copper"));
        assertEquals("missing target", Commands.preview(gotoForm, Map.of(), "Azalea", "Copper"));
        assertEquals("bad int", Commands.preview(mine, Map.of("count", "x", "block", "iron_ore"), "Azalea", "Copper"));
        assertFalse(Commands.ready(mine, Map.of(), "Azalea"));
        assertTrue(Commands.ready(FakeSwarm.CATALOG.get(2).model(), Map.of(), "Azalea")); // despawn fills name
    }

    @Test public void sendsOffTheUiThreadAndRepliesOnIt() {
        List<Runnable> background = new ArrayList<>(), ui = new ArrayList<>();
        FakeCommandPort port = new FakeCommandPort("Copper");
        Commands c = new Commands(port, background::add, ui::add, () -> 5);
        List<CenterReply> got = new ArrayList<>();
        c.press(FakeSwarm.CATALOG.get(0).model(), Map.of("target", "1 2 3"), "Azalea", got::add);
        assertTrue("nothing sent on the calling thread", port.sent().isEmpty());
        background.remove(0).run();
        assertEquals(1, port.sent().size());
        assertTrue("reply waits for the ui thread", got.isEmpty());
        ui.remove(0).run();
        assertEquals("swarm send Azalea goto 1 2 3", got.get(0).line());
        assertEquals(got.get(0), c.history().get(0));
    }

    @Test public void aThrowingPortIsARefusedReply() {
        Executor direct = Runnable::run;
        Commands c = new Commands(r -> {
            throw new IllegalStateException("x");
        }, direct, direct, () -> 5);
        List<CenterReply> got = new ArrayList<>();
        c.line("goto 1 2 3", "", got::add);
        assertFalse(got.get(0).admitted());
        assertEquals("send failed", got.get(0).reason());
    }

    @Test public void aLineIsSentAsALine() {
        FakeCommandPort port = new FakeCommandPort("Copper");
        Commands c = new Commands(port, Runnable::run, Runnable::run, () -> 5);
        c.line("swarm send Azalea goto 1 2 3", "", r -> { });
        assertEquals(CenterRequest.Op.LINE, port.sent().get(0).op());
        assertEquals("swarm send Azalea goto 1 2 3", port.sent().get(0).line());
    }

    @Test public void historyKeepsTheNewestTwenty() {
        Commands c = new Commands(new FakeCommandPort("Copper"), Runnable::run, Runnable::run, () -> 5);
        for (int i = 0; i < 25; i++) {
            c.line("goto " + i + " 64 0", "", r -> { });
        }
        assertEquals(Commands.HISTORY, c.history().size());
        assertEquals("goto 24 64 0", c.history().get(0).line());
    }
}
