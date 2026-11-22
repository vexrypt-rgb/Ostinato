package baritone.swarm.center.host;

import baritone.api.command.CommandPermission;
import baritone.command.defaults.CenterCommand;
import baritone.command.defaults.GotoCommand;
import baritone.command.defaults.MineCommand;
import baritone.command.defaults.SwarmCommand;
import baritone.command.defaults.ThemeCommand;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.Catalogs;
import baritone.swarm.center.RegistryEntry;
import baritone.swarm.center.UnifiedRegistry;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.feed.CatalogFeed;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Ostinato: the window's tests run against {@link FakeSwarm#CATALOG}; this keeps it equal to the forms the real
 * commands declare, so a form change in a command fails here instead of passing in the window tests.
 */
public class FakeCatalogMatchesRegistryTest {

    /** The registry of the commands that declare window forms, as the bots build it. */
    public static UnifiedRegistry registry() {
        List<RegistryEntry> hash = List.of(
                Catalogs.fromDescribed(List.of("swarm"), "swarm", CommandPermission.OWNER, null,
                        SwarmCommand.declaredSchema(), SwarmCommand.windowForms()),
                Catalogs.fromDescribed(List.of("goto"), "goto", CommandPermission.SWARM, null,
                        GotoCommand.declaredSchema(), GotoCommand.windowForms()),
                Catalogs.fromDescribed(List.of("mine"), "mine", CommandPermission.SWARM, null,
                        MineCommand.declaredSchema(), MineCommand.windowForms()),
                Catalogs.fromDescribed(List.of("center"), "center", CommandPermission.OWNER, null,
                        CenterCommand.declaredSchema(), CenterCommand.windowForms()),
                Catalogs.fromDescribed(List.of("theme"), "theme", CommandPermission.OWNER, null,
                        List.of(), ThemeCommand.windowForms()),
                Catalogs.fromDescribed(List.of("cancel"), "cancel", CommandPermission.OWNER, null, List.of(), List.of()));
        return UnifiedRegistry.of(hash, List.of());
    }

    @Test public void fakeCatalogMatchesTheRegistry() {
        List<String> real = new ArrayList<>();
        for (ButtonModel b : registry().buttons()) {
            real.add(b.command() + " " + b.form());
        }
        List<String> fake = new ArrayList<>();
        for (CatalogFeed.Button b : FakeSwarm.CATALOG) {
            fake.add(b.command() + " " + b.form());
        }
        real.sort(null);
        fake.sort(null);
        assertEquals(real, fake);
    }
}
