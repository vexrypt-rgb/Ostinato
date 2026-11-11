package baritone.swarm.center;

import baritone.api.command.CommandPermission;
import baritone.api.command.FormPlacement;
import baritone.command.defaults.CenterCommand;
import baritone.command.defaults.GotoCommand;
import baritone.command.defaults.MineCommand;
import baritone.command.defaults.SwarmCommand;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: button lines, layout file, and close. No frame and no dispatch on a refusal. */
public class CommandCenterModelTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static UnifiedRegistry registry() {
        List<RegistryEntry> hash = List.of(
                Catalogs.fromDescribed(List.of("swarm"), "swarm", CommandPermission.OWNER, null,
                        SwarmCommand.declaredSchema(), SwarmCommand.windowForms()),
                Catalogs.fromDescribed(List.of("goto"), "goto", CommandPermission.SWARM, null,
                        GotoCommand.declaredSchema(), GotoCommand.windowForms()),
                Catalogs.fromDescribed(List.of("mine"), "mine", CommandPermission.SWARM, null,
                        MineCommand.declaredSchema(), MineCommand.windowForms()),
                Catalogs.fromDescribed(List.of("center"), "center", CommandPermission.OWNER, null,
                        CenterCommand.declaredSchema(), CenterCommand.windowForms())
        );
        return UnifiedRegistry.of(hash, List.of());
    }

    @Test public void buttonsComeFromTheRegistryAndDispatchTheChatLine() {
        CommandCenterModel model = new CommandCenterModel(registry(), temporary.getRoot().toPath());
        assertEquals(CenterLayout.WALL, model.layout());
        assertTrue(model.buttons(FormPlacement.WINDOW).stream().anyMatch(button -> "spawn".equals(button.form().id())));
        assertTrue(model.buttons(FormPlacement.HEADER).stream().anyMatch(button -> "stop-all".equals(button.form().id())));
        ButtonModel spawn = model.buttons(FormPlacement.WINDOW).stream()
                .filter(button -> "spawn".equals(button.form().id())).findFirst().orElseThrow();
        AtomicReference<String> ran = new AtomicReference<>();
        RegistryResult ok = model.press(RegistryCall.window("Steve", "Steve", ""), spawn,
                Map.of("name", "Bot"), null, line -> {
                    ran.set(line);
                    return true;
                });
        assertTrue(ok.executed());
        assertEquals("swarm spawn Bot", ran.get());
        AtomicInteger refused = new AtomicInteger();
        RegistryResult denied = model.press(RegistryCall.ai("Steve", "Steve", true, "goto,swarm", false, ""), spawn,
                Map.of("name", "Bot"), null, line -> {
                    refused.incrementAndGet();
                    return true;
                });
        assertEquals("unauthorized", denied.reason());
        assertEquals(0, refused.get());
    }

    @Test public void aRemoteGotoIsAdmittedAsTheButtonAndAsSwarmSend() {
        CommandCenterModel model = new CommandCenterModel(registry(), temporary.getRoot().toPath());
        ButtonModel go = model.buttons(FormPlacement.TILE).stream()
                .filter(button -> "goto".equals(button.form().id())).findFirst().orElseThrow();
        AtomicReference<String> ran = new AtomicReference<>();
        RegistryResult ok = model.press(RegistryCall.window("Steve", "", ""), go,
                Map.of("target", "1 2 3"), "Bot", line -> {
                    ran.set(line);
                    return true;
                });
        assertTrue(ok.executed());
        assertEquals("swarm send Bot goto 1 2 3", ran.get());
        assertTrue(model.registry().inFlight("Bot"));
        AtomicInteger ai = new AtomicInteger();
        RegistryResult refused = model.press(RegistryCall.ai("Steve", "Steve", true, "goto", false, ""), go,
                Map.of("target", "4 5 6"), "Other", line -> {
                    ai.incrementAndGet();
                    return true;
                });
        assertEquals("unauthorized", refused.reason());
        assertEquals(0, ai.get());
    }

    @Test public void layoutRememberUsesTheSameFileAsCenter() throws Exception {
        CenterRuntime.resetForTests();
        CommandCenterModel model = new CommandCenterModel(registry(), temporary.getRoot().toPath());
        model.remember(CenterLayout.MAP);
        assertEquals(CenterLayout.MAP, LayoutStore.load(model.layoutFile()));
        assertEquals(CenterLayout.MAP, CenterRuntime.layout());
        model.show(CenterLayout.WALL);
        assertEquals(CenterLayout.WALL, model.layout());
        assertEquals(CenterLayout.MAP, LayoutStore.load(model.layoutFile()));
    }

    @Test public void closingDoesNotDispatchOrStopBots() {
        CenterRuntime.resetForTests();
        AtomicInteger runs = new AtomicInteger();
        CenterRuntime.setHost(CenterRuntime.Host.NONE);
        CenterRuntime.open();
        assertTrue(CenterRuntime.isOpen());
        CommandCenterModel model = new CommandCenterModel(registry(), temporary.getRoot().toPath());
        ButtonModel stop = model.buttons(FormPlacement.HEADER).stream()
                .filter(button -> "stop-all".equals(button.form().id())).findFirst().orElseThrow();
        model.press(RegistryCall.window("Steve", "", ""), stop, Map.of(), null, line -> {
            runs.incrementAndGet();
            return true;
        });
        assertEquals(1, runs.get());
        CenterRuntime.close();
        assertFalse(CenterRuntime.isOpen());
        assertEquals(1, runs.get());
    }
}
